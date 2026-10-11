package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class TripChartPoint(val elapsedMillis: Long, val value: Double?, val ecuId: String)

data class TripAnalysis(
    val session: TripSession,
    val sampleCount: Long,
    val validSampleCount: Long,
    val estimatedDistanceKm: Double?,
    val speedEcuId: String?,
    val coveredMillis: Long,
    val maxRpm: Double?,
    val maxSpeedKph: Double?,
    val chart: List<TripChartPoint>,
    val seriesOptions: List<TripSeriesKey> = emptyList(),
)

/** Analyze immutable, finished trips without loading their complete sample history. */
suspend fun analyzeTrip(repository: TripRepository, session: TripSession): TripAnalysis = withContext(Dispatchers.Default) {
    requireFinishedTrip(session)
    var sampleCount = 0L
    var validCount = 0L
    var maxRpm: Double? = null
    var anySpeedEcu: String? = null
    var validSpeedEcu: String? = null
    val series = linkedSetOf<TripSeriesKey>()
    forEachTripSample(repository, session.id) { sample ->
        series += TripSeriesKey(sample.metric, sample.ecuId)
        if (series.size > MAX_TRIP_SERIES) throw IOException("The trip contains too many sensor/source combinations.")
        sampleCount++
        if (sample.isValidNumber()) validCount++
        if (sample.metric == ObdMetric.ENGINE_RPM && sample.isCanonicalNonnegative()) {
            maxRpm = maxOf(maxRpm ?: sample.value!!, sample.value!!)
        }
        if (sample.metric == ObdMetric.SPEED) {
            anySpeedEcu = earlierEcu(anySpeedEcu, sample.ecuId)
            if (sample.isUsableSpeed(session)) validSpeedEcu = earlierEcu(validSpeedEcu, sample.ecuId)
        }
    }
    val speedEcu = validSpeedEcu ?: anySpeedEcu
    var coveredMillis = 0L
    var distanceKm = 0.0
    var maxSpeed: Double? = null
    var previous: TripSample? = null
    var lastChartTime = 0L
    val chart = BoundedTripChart()
    if (speedEcu != null) forEachTripSample(repository, session.id) { sample ->
        if (sample.metric != ObdMetric.SPEED || sample.ecuId != speedEcu) return@forEachTripSample
        // Corrupt/reordered timestamps break a segment rather than making a backward line.
        val chartTime = sample.elapsedMillis.coerceIn(0L, session.durationMillis).coerceAtLeast(lastChartTime)
        val prior = previous
        if (!sample.isUsableSpeed(session) || sample.elapsedMillis < lastChartTime) {
            chart.add(TripChartPoint(chartTime, null, speedEcu))
            previous = null
        } else {
            val delta = prior?.let { sample.elapsedMillis - it.elapsedMillis }
            if (delta != null && delta in 1L..MAX_SPEED_INTERVAL_MILLIS) {
                coveredMillis += delta
                // km/h * milliseconds / milliseconds-per-hour; no endpoint extrapolation.
                distanceKm += (prior!!.value!! / 2.0 + sample.value!! / 2.0) * (delta / 3_600_000.0)
            } else if (prior != null) {
                chart.add(TripChartPoint(chartTime, null, speedEcu))
            }
            maxSpeed = maxOf(maxSpeed ?: sample.value!!, sample.value!!)
            chart.add(TripChartPoint(chartTime, sample.value, speedEcu))
            previous = sample
        }
        lastChartTime = chartTime
    }
    TripAnalysis(
        session, sampleCount, validCount,
        estimatedDistanceKm = distanceKm.takeIf { coveredMillis > 0L && it.isFinite() },
        speedEcuId = speedEcu,
        coveredMillis = coveredMillis,
        maxRpm = maxRpm,
        maxSpeedKph = maxSpeed,
        chart = chart.points(),
        seriesOptions = series.sortedWith(tripSeriesOrder),
    )
}

private fun earlierEcu(existing: String?, candidate: String): String =
    if (existing == null || candidate < existing) candidate else existing

private fun TripSample.isValidNumber(): Boolean = quality == ObdReadingStatus.VALID && value?.isFinite() == true

private fun TripSample.isCanonicalNonnegative(): Boolean =
    isValidNumber() && value!! >= 0.0 && unit == metric.unit

private fun TripSample.isUsableSpeed(session: TripSession): Boolean =
    isCanonicalNonnegative() && elapsedMillis in 0L..session.durationMillis

/** Nulls survive compaction, so dropping chart detail can never bridge a recorded gap. */
internal class BoundedTripChart {
    private var samples = mutableListOf<TripChartPoint>()

    fun add(point: TripChartPoint) {
        samples += point
        if (samples.size <= 500) return
        val reduced = mutableListOf(samples.first())
        var index = 1
        while (index < samples.lastIndex) {
            val first = samples[index]
            val second = samples.getOrNull(index + 1)?.takeIf { index + 1 < samples.lastIndex }
            reduced += when {
                second == null -> first
                first.value == null -> first
                else -> second
            }
            index += 2
        }
        reduced += samples.last()
        samples = reduced
    }

    fun points(): List<TripChartPoint> = samples.toList()
}

internal fun requireFinishedTrip(session: TripSession) {
    require(session.status != TripStatus.RECORDING) { "Stop recording before analyzing or exporting the trip." }
    require(session.durationMillis >= 0L) { "Trip duration must not be negative." }
}

/** Sequence keyset paging also rejects a broken repository that would repeat pages forever. */
internal suspend fun forEachTripSample(
    repository: TripRepository,
    tripId: String,
    consume: suspend (TripSample) -> Unit,
) {
    var afterSequence = -1L
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = repository.samples(tripId, afterSequence, SAMPLE_PAGE_SIZE)
        if (page.isEmpty()) return
        if (page.size > SAMPLE_PAGE_SIZE) throw IOException("The stored sample page exceeds its requested size.")
        for (sample in page) {
            currentCoroutineContext().ensureActive()
            if (sample.tripId != tripId || sample.sequence <= afterSequence) {
                throw IOException("Stored samples have an invalid trip or sequence order.")
            }
            consume(sample)
            afterSequence = sample.sequence
        }
    }
}

private const val SAMPLE_PAGE_SIZE = 500
private const val MAX_SPEED_INTERVAL_MILLIS = 5_000L
