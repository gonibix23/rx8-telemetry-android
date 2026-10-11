package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalysisTest {
    @Test fun emptyTripHasNoInventedDistanceOrPeaks() = runTest {
        val analysis = analyzeTrip(AnalyticsExportRepository(), analysisTrip())
        assertEquals(0L, analysis.sampleCount)
        assertEquals(0L, analysis.validSampleCount)
        assertEquals(0L, analysis.coveredMillis)
        assertNull(analysis.estimatedDistanceKm)
        assertNull(analysis.maxRpm)
        assertNull(analysis.maxSpeedKph)
        assertNull(analysis.speedEcuId)
        assertTrue(analysis.chart.isEmpty())
    }

    @Test fun validStationaryZeroIsPreservedButOnePointCannotEstimateDistance() = runTest {
        val analysis = analyzeTrip(AnalyticsExportRepository(listOf(analysisSample(0, 2_000, 0.0))), analysisTrip())
        assertEquals(0.0, analysis.maxSpeedKph!!, 0.0)
        assertEquals(0.0, analysis.chart.single().value!!, 0.0)
        assertNull(analysis.estimatedDistanceKm)
        assertEquals(1L, analysis.validSampleCount)
    }

    @Test fun integratesAdjacentSpeedWithoutExtrapolationAndUsesOnlyValidPeaks() = runTest {
        val repository = AnalyticsExportRepository(listOf(
            analysisSample(0, 1_000, 36.0),
            analysisSample(1, 3_000, 72.0),
            analysisSample(2, 3_500, 1_200.0, metric = ObdMetric.ENGINE_RPM),
            analysisSample(3, 4_000, 9_000.0, quality = ObdReadingStatus.STALE, metric = ObdMetric.ENGINE_RPM),
            analysisSample(4, 4_500, null, quality = ObdReadingStatus.TIMEOUT, metric = ObdMetric.COOLANT_TEMPERATURE),
        ))
        val analysis = analyzeTrip(repository, analysisTrip())
        assertEquals(0.03, analysis.estimatedDistanceKm!!, 0.0000001)
        assertEquals(2_000L, analysis.coveredMillis)
        assertEquals(8_000L, analysis.session.durationMillis - analysis.coveredMillis)
        assertEquals(1_200.0, analysis.maxRpm!!, 0.0)
        assertEquals(72.0, analysis.maxSpeedKph!!, 0.0)
        assertEquals(5L, analysis.sampleCount)
        assertEquals(3L, analysis.validSampleCount)
    }

    @Test fun invalidSamplesAndIntervalsOverFiveSecondsBreakDistanceAndChartSegments() = runTest {
        val samples = listOf(
            analysisSample(0, 0, 36.0), analysisSample(1, 1_000, 36.0),
            analysisSample(2, 2_000, 0.0, quality = ObdReadingStatus.TIMEOUT),
            analysisSample(3, 3_000, 72.0), analysisSample(4, 8_000, 72.0),
            analysisSample(5, 13_001, 90.0),
        )
        val analysis = analyzeTrip(AnalyticsExportRepository(samples), analysisTrip(20_000L))
        assertEquals(6_000L, analysis.coveredMillis)
        assertEquals(0.11, analysis.estimatedDistanceKm!!, 0.0000001)
        assertEquals(2, analysis.chart.count { it.value == null })
        assertFalse(analysis.chart.any { it.elapsedMillis == 2_000L && it.value == 0.0 })
    }

    @Test fun deterministicSourceSelectionNeverCombinesDifferentEcuSpeeds() = runTest {
        val samples = listOf(
            analysisSample(0, 0, 200.0, ecu = "7E9"), analysisSample(1, 0, 20.0, ecu = "7E8"),
            analysisSample(2, 1_000, 210.0, ecu = "7E9"), analysisSample(3, 1_000, 40.0, ecu = "7E8"),
        )
        val analysis = analyzeTrip(AnalyticsExportRepository(samples), analysisTrip())
        assertEquals("7E8", analysis.speedEcuId)
        assertEquals(40.0, analysis.maxSpeedKph!!, 0.0)
        assertEquals(30.0 / 3_600.0, analysis.estimatedDistanceKm!!, 0.0000001)
        assertTrue(analysis.chart.all { it.ecuId == "7E8" })
    }

    @Test fun sourceWithValidDataIsPreferredOverAnEarlierEcuWithOnlyMissingData() = runTest {
        val repository = AnalyticsExportRepository(listOf(
            analysisSample(0, 0, null, ecu = "7E7", quality = ObdReadingStatus.NO_DATA),
            analysisSample(1, 0, 0.0, ecu = "7E9"), analysisSample(2, 1_000, 0.0, ecu = "7E9"),
        ))
        val analysis = analyzeTrip(repository, analysisTrip())
        assertEquals("7E9", analysis.speedEcuId)
        assertEquals(0.0, analysis.estimatedDistanceKm!!, 0.0)
        assertEquals(1_000L, analysis.coveredMillis)
    }

    @Test fun badUnitsNonfiniteNumbersNegativeValuesAndReorderedTimesDoNotCreateDistance() = runTest {
        val samples = listOf(
            analysisSample(0, 0, 36.0),
            analysisSample(1, 1_000, 36.0).copy(unit = "mph"),
            analysisSample(2, 2_000, 36.0),
            analysisSample(3, 3_000, Double.NaN),
            analysisSample(4, 4_000, -10.0),
            analysisSample(5, 5_000, 36.0),
            analysisSample(6, 4_500, 36.0),
            analysisSample(7, 6_000, 36.0),
        )
        val analysis = analyzeTrip(AnalyticsExportRepository(samples), analysisTrip())
        assertEquals(0L, analysis.coveredMillis)
        assertNull(analysis.estimatedDistanceKm)
        assertTrue(analysis.chart.zipWithNext().all { (first, second) -> second.elapsedMillis >= first.elapsedMillis })
        assertTrue(analysis.chart.count { it.value == null } >= 4)
    }

    @Test fun pagingAndChartCompactionStayBoundedWithoutDroppingGapBarriers() = runTest {
        val samples = (0L..1_204L).map { index ->
            analysisSample(index * 2L, index * 1_000L, if (index == 600L) null else 36.0,
                quality = if (index == 600L) ObdReadingStatus.NO_DATA else ObdReadingStatus.VALID)
        }
        val repository = AnalyticsExportRepository(samples)
        val analysis = analyzeTrip(repository, analysisTrip(1_204_000L))
        assertEquals(1_205L, analysis.sampleCount)
        assertEquals(1_204L, analysis.validSampleCount)
        assertEquals(1_202_000L, analysis.coveredMillis)
        assertTrue(analysis.chart.size <= 500)
        assertTrue(analysis.chart.any { it.value == null })
        assertEquals(0L, analysis.chart.first().elapsedMillis)
        assertEquals(1_204_000L, analysis.chart.last().elapsedMillis)
        assertEquals(listOf(-1L, 998L, 1_998L, 2_408L, -1L, 998L, 1_998L, 2_408L), repository.requests.map { it.first })
        assertTrue(repository.requests.all { it.second == 500 })
    }

    @Test fun repeatedOrForeignPagesFailInsteadOfLoopingOrMixingTrips() = runTest {
        for (broken in listOf(
            listOf(analysisSample(0, 0, 1.0), analysisSample(0, 1_000, 1.0)),
            listOf(analysisSample(0, 0, 1.0).copy(tripId = "another-trip")),
        )) {
            val repository = AnalyticsExportRepository().apply { fixedPage = broken }
            var failure: Throwable? = null
            try { analyzeTrip(repository, analysisTrip()) } catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IOException)
            assertEquals(1, repository.requests.size)
        }
    }

    @Test fun activeTripAnalysisIsRejectedBeforeReadingAnUnstableHistory() = runTest {
        val repository = AnalyticsExportRepository()
        var failure: Throwable? = null
        try { analyzeTrip(repository, analysisTrip().copy(status = TripStatus.RECORDING)) } catch (caught: Throwable) { failure = caught }
        assertTrue(failure is IllegalArgumentException)
        assertTrue(repository.requests.isEmpty())
    }

    @Test fun cancellationIsCheckedWithinReturnedPages() = runTest {
        val job = Job()
        val repository = AnalyticsExportRepository(listOf(analysisSample(0, 0, 1.0))).apply { beforePage = { job.cancel() } }
        var cancelled = false
        try { withContext(job) { analyzeTrip(repository, analysisTrip()) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, repository.requests.size)
    }

    @Test fun seriesChoicesComeFromSavedSourcesIncludingErrorsAndPreferExistingSpeedSummary() = runTest {
        val samples = listOf(
            analysisSample(0, 0, null, ecu = "7E7", quality = ObdReadingStatus.NO_DATA),
            analysisSample(1, 0, 20.0, ecu = "7E9"),
            analysisSample(2, 1_000, 40.0, ecu = "7E9"),
            analysisSample(3, 2_000, null, ecu = "7E8", quality = ObdReadingStatus.TIMEOUT, metric = ObdMetric.ENGINE_RPM),
            analysisSample(4, 3_000, -10.0, metric = ObdMetric.INTAKE_TEMPERATURE),
        )
        val repository = AnalyticsExportRepository(samples)
        val analysis = analyzeTrip(repository, analysisTrip())
        assertEquals(listOf(
            TripSeriesKey(ObdMetric.ENGINE_RPM, "7E8"), TripSeriesKey(ObdMetric.SPEED, "7E7"),
            TripSeriesKey(ObdMetric.SPEED, "7E9"), TripSeriesKey(ObdMetric.INTAKE_TEMPERATURE, "7E8"),
        ), analysis.seriesOptions)
        assertEquals(TripSeriesKey(ObdMetric.SPEED, "7E9"), analysis.defaultSeries())
        assertEquals(1_000L, analysis.coveredMillis)
        assertEquals(30.0 / 3_600.0, analysis.estimatedDistanceKm!!, 0.0000001)
        assertEquals(listOf(-1L, 4L, -1L, 4L), repository.requests.map { it.first })
    }

    @Test fun noSpeedDefaultsToStableMetricThenEcuAndEmptyTripHasNoSeries() = runTest {
        val analysis = analyzeTrip(AnalyticsExportRepository(listOf(
            analysisSample(0, 0, null, ecu = "7E9", quality = ObdReadingStatus.NO_DATA, metric = ObdMetric.INTAKE_TEMPERATURE),
            analysisSample(1, 0, 10.0, ecu = "7E9", metric = ObdMetric.COOLANT_TEMPERATURE),
            analysisSample(2, 0, 20.0, ecu = "7E8", metric = ObdMetric.COOLANT_TEMPERATURE),
        )), analysisTrip())
        assertEquals(TripSeriesKey(ObdMetric.COOLANT_TEMPERATURE, "7E8"), analysis.defaultSeries())
        assertNull(analyzeTrip(AnalyticsExportRepository(), analysisTrip()).defaultSeries())
        assertTrue(analysis.chart.isEmpty())
        assertNull(analysis.estimatedDistanceKm)
    }

    @Test fun excessiveRecordedSeriesFailClearlyInsteadOfGrowingUnboundedChoices() = runTest {
        val samples = (0L..192L).map { analysisSample(it, it, null, ecu = "source-$it", quality = ObdReadingStatus.NO_DATA) }
        val repository = AnalyticsExportRepository(samples)
        var failure: Throwable? = null
        try { analyzeTrip(repository, analysisTrip()) } catch (caught: Throwable) { failure = caught }
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("too many sensor/source combinations"))
        assertEquals(1, repository.requests.size)
    }
}

internal fun analysisTrip(duration: Long = 10_000L) = TripSession(
    id = "00000000-0000-4000-8000-000000000001",
    vehicleId = "00000000-0000-4000-8000-000000000002",
    vehicleAlias = "Synthetic RX-8",
    startedAtEpochMillis = 0L,
    endedAtEpochMillis = duration,
    durationMillis = duration,
    status = TripStatus.COMPLETED,
    endReason = "USER_STOP",
    protocolLabel = "Synthetic protocol",
)

internal fun analysisSample(
    sequence: Long,
    elapsed: Long,
    value: Double?,
    ecu: String = "7E8",
    quality: ObdReadingStatus = ObdReadingStatus.VALID,
    metric: ObdMetric = ObdMetric.SPEED,
) = TripSample("sample-$sequence", analysisTrip().id, sequence, elapsed, elapsed, metric, value, metric.unit, ecu, quality)

internal class AnalyticsExportRepository(private val history: List<TripSample> = emptyList()) : TripRepository {
    val requests = mutableListOf<Pair<Long, Int>>()
    var fixedPage: List<TripSample>? = null
    var beforePage: suspend (Int) -> Unit = {}
    override suspend fun samples(tripId: String, afterSequence: Long, limit: Int): List<TripSample> {
        requests += afterSequence to limit
        beforePage(requests.size)
        return fixedPage ?: history.filter { it.sequence > afterSequence }.take(limit)
    }
    override suspend fun recoverInterrupted() = Unit
    override suspend fun create(session: TripSession) = error("Read-only test")
    override suspend fun append(tripId: String, samples: List<TripSample>) = error("Read-only test")
    override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) = error("Read-only test")
    override suspend fun list(limit: Int): List<TripSession> = emptyList()
    override suspend fun find(tripId: String): TripSession? = null
    override suspend fun delete(tripId: String) = error("Read-only test")
    override suspend fun storageBytes(): Long = 0L
}
