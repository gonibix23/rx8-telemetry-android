package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TripSeriesKey(val metric: ObdMetric, val ecuId: String)

data class TripSeriesAnalysis(
    val key: TripSeriesKey,
    val sampleCount: Long,
    val validSampleCount: Long,
    val minValue: Double?,
    val maxValue: Double?,
    val coveredMillis: Long,
    val points: List<TripChartPoint>,
)

/** Prefer the existing distance source, then a stable metric/source order independent of the UI. */
fun TripAnalysis.defaultSeries(): TripSeriesKey? =
    speedEcuId?.let { TripSeriesKey(ObdMetric.SPEED, it) }?.takeIf { it in seriesOptions }
        ?: seriesOptions.minWithOrNull(tripSeriesOrder)

/** One finished-trip source, one bounded pass. Statistics use original samples, not reduced points. */
suspend fun analyzeTripSeries(
    repository: TripRepository,
    session: TripSession,
    key: TripSeriesKey,
): TripSeriesAnalysis = withContext(Dispatchers.Default) {
    requireFinishedTrip(session)
    var sampleCount = 0L
    var validCount = 0L
    var minValue: Double? = null
    var maxValue: Double? = null
    var coveredMillis = 0L
    var latestInRangeTime: Long? = null
    var previousValidTime: Long? = null
    val chart = BoundedTripChart()
    forEachTripSample(repository, session.id) { sample ->
        if (sample.metric != key.metric || sample.ecuId != key.ecuId) return@forEachTripSample
        sampleCount++
        val inTimeRange = sample.elapsedMillis in 0L..session.durationMillis
        val latest = latestInRangeTime
        val increasingTime = latest == null || sample.elapsedMillis > latest
        // An impossible timestamp gets a barrier at the last known position, never a backward line.
        val chartTime = if (inTimeRange) maxOf(sample.elapsedMillis, latest ?: 0L) else latest ?: 0L
        if (!inTimeRange || !increasingTime || !sample.isUsableSeriesValue()) {
            chart.add(TripChartPoint(chartTime, null, key.ecuId))
            previousValidTime = null
        } else {
            val value = requireNotNull(sample.value)
            validCount++
            minValue = minOf(minValue ?: value, value)
            maxValue = maxOf(maxValue ?: value, value)
            val delta = previousValidTime?.let { sample.elapsedMillis - it }
            if (delta != null) {
                if (delta in 1L..key.metric.seriesIntervalMillis()) coveredMillis += delta
                else chart.add(TripChartPoint(chartTime, null, key.ecuId))
            }
            chart.add(TripChartPoint(chartTime, value, key.ecuId))
            previousValidTime = sample.elapsedMillis
        }
        if (inTimeRange) latestInRangeTime = chartTime
    }
    TripSeriesAnalysis(key, sampleCount, validCount, minValue, maxValue, coveredMillis, chart.points())
}

/** Physical decoder domains from the existing Mode 01 byte formulas, not inferred car limits. */
private fun TripSample.isUsableSeriesValue(): Boolean {
    val number = value ?: return false
    if (quality != ObdReadingStatus.VALID || unit != metric.unit || !number.isFinite()) return false
    return metric.isValueInRange(number)
}

internal val tripSeriesOrder: Comparator<TripSeriesKey> = compareBy<TripSeriesKey> { it.metric.ordinal }.thenBy { it.ecuId }
internal val MAX_TRIP_SERIES = 32 * ObdMetric.entries.size

// Preserve the original six series' coverage semantics and speed-distance integration.
private fun ObdMetric.seriesIntervalMillis(): Long = if (ordinal < 6) 5_000L else freshnessMillis
