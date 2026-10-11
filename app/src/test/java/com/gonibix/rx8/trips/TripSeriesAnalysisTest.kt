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

class TripSeriesAnalysisTest {
    @Test fun absentSeriesHasNoFabricatedStatisticsOrPoints() = runTest {
        val analysis = analyzeTripSeries(AnalyticsExportRepository(listOf(analysisSample(0, 0, 10.0))), analysisTrip(), temperature)
        assertEquals(temperature, analysis.key)
        assertEquals(0L, analysis.sampleCount)
        assertEquals(0L, analysis.validSampleCount)
        assertNull(analysis.minValue)
        assertNull(analysis.maxValue)
        assertEquals(0L, analysis.coveredMillis)
        assertTrue(analysis.points.isEmpty())
    }

    @Test fun negativeCelsiusZeroAndConstantSeriesArePreservedWithoutEndpointExtrapolation() = runTest {
        val samples = listOf(-20.0, 0.0, 0.0).mapIndexed { index, value ->
            analysisSample(index.toLong(), (index + 1) * 1_000L, value, metric = temperature.metric)
        }
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), temperature)
        assertEquals(3L, analysis.validSampleCount)
        assertEquals(-20.0, analysis.minValue!!, 0.0)
        assertEquals(0.0, analysis.maxValue!!, 0.0)
        assertEquals(2_000L, analysis.coveredMillis)
        assertEquals(listOf(-20.0, 0.0, 0.0), analysis.points.map { it.value })
        val constant = analyzeTripSeries(AnalyticsExportRepository(samples.map { it.copy(value = -5.0) }), analysisTrip(), temperature)
        assertEquals(-5.0, constant.minValue!!, 0.0)
        assertEquals(constant.minValue, constant.maxValue)
    }

    @Test fun oneUsablePointHasExtremaButNoCoveredInterval() = runTest {
        val analysis = analyzeTripSeries(AnalyticsExportRepository(listOf(analysisSample(0, 4_000, 0.0))), analysisTrip(), speed)
        assertEquals(1L, analysis.validSampleCount)
        assertEquals(0.0, analysis.minValue!!, 0.0)
        assertEquals(0.0, analysis.maxValue!!, 0.0)
        assertEquals(0L, analysis.coveredMillis)
        assertEquals(1, analysis.points.size)
    }

    @Test fun everyExistingDecoderDomainIncludesItsEndpointsAndRejectsValuesOutsideThem() = runTest {
        val ranges = mapOf(
            ObdMetric.ENGINE_RPM to (0.0 to 16_383.75), ObdMetric.SPEED to (0.0 to 255.0),
            ObdMetric.COOLANT_TEMPERATURE to (-40.0 to 215.0), ObdMetric.INTAKE_TEMPERATURE to (-40.0 to 215.0),
            ObdMetric.THROTTLE_POSITION to (0.0 to 100.0), ObdMetric.ENGINE_LOAD to (0.0 to 100.0),
        )
        for ((metric, range) in ranges) {
            val values = listOf(range.first, range.second, range.first - 0.01, range.second + 0.01)
            val samples = values.mapIndexed { index, value -> analysisSample(index.toLong(), index * 1_000L, value, metric = metric) }
            val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), TripSeriesKey(metric, "7E8"))
            assertEquals(4L, analysis.sampleCount)
            assertEquals(2L, analysis.validSampleCount)
            assertEquals(range.first, analysis.minValue!!, 0.0)
            assertEquals(range.second, analysis.maxValue!!, 0.0)
            assertEquals(1_000L, analysis.coveredMillis)
            assertEquals(listOf(range.first, range.second, null, null), analysis.points.map { it.value })
        }
    }

    @Test fun allFailureQualitiesAndMissingOrNonfiniteValuesBreakContinuity() = runTest {
        val failures = ObdReadingStatus.entries.filter { it != ObdReadingStatus.VALID }.map { it to 100.0 } +
            listOf(ObdReadingStatus.VALID to null, ObdReadingStatus.VALID to Double.NaN, ObdReadingStatus.VALID to Double.POSITIVE_INFINITY)
        for ((quality, value) in failures) {
            val samples = listOf(analysisSample(0, 0, 10.0), analysisSample(1, 1_000, value, quality = quality), analysisSample(2, 2_000, 20.0))
            val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), speed)
            assertEquals(3L, analysis.sampleCount)
            assertEquals(2L, analysis.validSampleCount)
            assertEquals(0L, analysis.coveredMillis)
            assertEquals(listOf(10.0, null, 20.0), analysis.points.map { it.value })
        }
    }

    @Test fun onlyCanonicalUnitsEnterStatisticsOrCoverage() = runTest {
        val samples = listOf(
            analysisSample(0, 0, -10.0, metric = temperature.metric),
            analysisSample(1, 1_000, 100.0, metric = temperature.metric).copy(unit = "Cel"),
            analysisSample(2, 2_000, -5.0, metric = temperature.metric),
        )
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), temperature)
        assertEquals(2L, analysis.validSampleCount)
        assertEquals(-5.0, analysis.maxValue!!, 0.0)
        assertEquals(0L, analysis.coveredMillis)
        assertNull(analysis.points[1].value)
    }

    @Test fun intervalOfFiveSecondsIsCoveredButLongerIntervalHasAnExplicitBarrier() = runTest {
        val samples = listOf(analysisSample(0, 0, 10.0), analysisSample(1, 5_000, 20.0), analysisSample(2, 10_001, 30.0))
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(15_000), speed)
        assertEquals(3L, analysis.validSampleCount)
        assertEquals(5_000L, analysis.coveredMillis)
        assertEquals(listOf(10.0, 20.0, null, 30.0), analysis.points.map { it.value })
        assertEquals(30.0, analysis.maxValue!!, 0.0)
    }

    @Test fun duplicateBackwardAndOutOfTripTimesBreakSegmentsWithoutPoisoningLaterValidTimes() = runTest {
        val times = listOf(0L, 1_000L, 1_000L, 900L, 2_000L, -1L, 3_000L, 11_000L, 4_000L, 5_000L)
        val values = listOf(10.0, 20.0, 240.0, 245.0, 30.0, 250.0, 40.0, 255.0, 50.0, 60.0)
        val samples = times.mapIndexed { index, time -> analysisSample(index.toLong(), time, values[index]) }
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), speed)
        assertEquals(10L, analysis.sampleCount)
        assertEquals(6L, analysis.validSampleCount)
        assertEquals(2_000L, analysis.coveredMillis)
        assertEquals(10.0, analysis.minValue!!, 0.0)
        assertEquals(60.0, analysis.maxValue!!, 0.0)
        assertEquals(4, analysis.points.count { it.value == null })
        assertTrue(analysis.points.zipWithNext().all { (a, b) -> b.elapsedMillis >= a.elapsedMillis })
    }

    @Test fun selectedSourceNeverMixesOtherEcuOrMetricValuesAndErrors() = runTest {
        val samples = listOf(
            analysisSample(0, 0, 20.0), analysisSample(1, 0, 100.0, ecu = "7E9"),
            analysisSample(2, 500, null, ecu = "7E9", quality = ObdReadingStatus.NO_DATA),
            analysisSample(3, 800, 10_000.0, metric = ObdMetric.ENGINE_RPM),
            analysisSample(4, 1_000, 40.0),
        )
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), speed)
        assertEquals(2L, analysis.sampleCount)
        assertEquals(2L, analysis.validSampleCount)
        assertEquals(1_000L, analysis.coveredMillis)
        assertEquals(40.0, analysis.maxValue!!, 0.0)
        assertEquals(listOf(20.0, 40.0), analysis.points.map { it.value })
        assertTrue(analysis.points.all { it.ecuId == "7E8" })
    }

    @Test fun errorOnlySeriesKeepsRecordedCountAndGapsWithoutExtrema() = runTest {
        val samples = listOf(analysisSample(0, 0, null, quality = ObdReadingStatus.NO_DATA), analysisSample(1, 1_000, 100.0, quality = ObdReadingStatus.STALE))
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(), speed)
        assertEquals(2L, analysis.sampleCount)
        assertEquals(0L, analysis.validSampleCount)
        assertNull(analysis.minValue)
        assertNull(analysis.maxValue)
        assertEquals(0L, analysis.coveredMillis)
        assertTrue(analysis.points.all { it.value == null })
    }

    @Test fun fullStreamExtremaCoverageAndCountsSurviveBoundedGapPreservingCompaction() = runTest {
        val samples = (0L..1_204L).map { index ->
            val value = when (index) { 1L -> 0.0; 3L -> 6_000.0; 600L -> null; else -> 1_000.0 }
            analysisSample(index * 2, index * 1_000, value, metric = ObdMetric.ENGINE_RPM,
                quality = if (value == null) ObdReadingStatus.NO_DATA else ObdReadingStatus.VALID)
        }
        val repository = AnalyticsExportRepository(samples)
        val analysis = analyzeTripSeries(repository, analysisTrip(1_204_000), TripSeriesKey(ObdMetric.ENGINE_RPM, "7E8"))
        assertEquals(1_205L, analysis.sampleCount)
        assertEquals(1_204L, analysis.validSampleCount)
        assertEquals(0.0, analysis.minValue!!, 0.0)
        assertEquals(6_000.0, analysis.maxValue!!, 0.0)
        assertEquals(1_202_000L, analysis.coveredMillis)
        assertTrue(analysis.points.size <= 500)
        assertTrue(analysis.points.any { it.value == null })
        assertFalse(analysis.points.any { it.value == 0.0 || it.value == 6_000.0 })
        assertEquals(0L, analysis.points.first().elapsedMillis)
        assertEquals(1_204_000L, analysis.points.last().elapsedMillis)
        assertEquals(listOf(-1L, 998L, 1_998L, 2_408L), repository.requests.map { it.first })
        assertTrue(repository.requests.all { it.second == 500 })
    }

    @Test fun repeatedCompactionCannotReconnectAcrossFrequentGaps() = runTest {
        val samples = (0L..2_005L).map { index -> analysisSample(index, index * 1_000, if (index % 3 == 1L) null else 30.0,
            quality = if (index % 3 == 1L) ObdReadingStatus.NO_DATA else ObdReadingStatus.VALID) }
        val analysis = analyzeTripSeries(AnalyticsExportRepository(samples), analysisTrip(2_005_000), speed)
        assertTrue(analysis.points.size <= 500)
        for ((first, second) in analysis.points.zipWithNext()) {
            if (first.value != null && second.value != null) assertTrue(second.elapsedMillis - first.elapsedMillis <= 1_000L)
        }
    }

    @Test fun malformedPagesAreRejectedEvenIfTheirSamplesAreOutsideTheSelectedSeries() = runTest {
        val foreign = analysisSample(0, 0, 1.0, ecu = "7E9").copy(tripId = "another-trip")
        val duplicate = listOf(analysisSample(0, 0, 1.0), analysisSample(0, 1_000, 1.0))
        for (page in listOf(listOf(foreign), duplicate, List(501) { analysisSample(it.toLong(), it.toLong(), 1.0) })) {
            val repository = AnalyticsExportRepository().apply { fixedPage = page }
            var failure: Throwable? = null
            try { analyzeTripSeries(repository, analysisTrip(), temperature) } catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IOException)
            assertEquals(1, repository.requests.size)
        }
    }

    @Test fun activeOrNegativeDurationTripsAreRejectedBeforeAnySampleRead() = runTest {
        for (session in listOf(analysisTrip().copy(status = TripStatus.RECORDING), analysisTrip(-1))) {
            val repository = AnalyticsExportRepository()
            var failure: Throwable? = null
            try { analyzeTripSeries(repository, session, speed) } catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IllegalArgumentException)
            assertTrue(repository.requests.isEmpty())
        }
    }

    @Test fun cancellationIsCheckedInsideTheSelectedSeriesScan() = runTest {
        val job = Job()
        val repository = AnalyticsExportRepository(listOf(analysisSample(0, 0, 1.0))).apply { beforePage = { job.cancel() } }
        var cancelled = false
        try { withContext(job) { analyzeTripSeries(repository, analysisTrip(), speed) } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, repository.requests.size)
    }

    private val speed = TripSeriesKey(ObdMetric.SPEED, "7E8")
    private val temperature = TripSeriesKey(ObdMetric.INTAKE_TEMPERATURE, "7E8")
}
