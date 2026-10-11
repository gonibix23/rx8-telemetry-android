package com.gonibix.rx8.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import java.util.Locale

/** Renders one recorded source. It neither observes live telemetry nor reads the repository. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripSensorChart(
    options: List<TripSeriesKey>,
    selected: TripSeriesKey?,
    series: TripSeriesAnalysis?,
    isLoading: Boolean,
    error: String?,
    durationMillis: Long,
    onSelectSeries: (TripSeriesKey) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("trip_sensor_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recorded sensors", style = MaterialTheme.typography.titleMedium)
        Text("Choose a sensor and its recorded ECU source. This does not request new vehicle data.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        if (options.isEmpty()) {
            Text("No sensor readings were recorded in this trip.", color = MutedColor, modifier = Modifier.testTag("empty_trip_series"))
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            options.map { it.metric }.distinct().forEach { metric ->
                FilterChip(
                    selected = selected?.metric == metric,
                    onClick = {
                        val next = options.firstOrNull { it.metric == metric && it.ecuId == selected?.ecuId }
                            ?: options.first { it.metric == metric }
                        if (next != selected) onSelectSeries(next)
                    },
                    label = { Text(metric.chartChoiceLabel()) },
                    modifier = Modifier.testTag("trip_metric_${metric.name.lowercase(Locale.ROOT)}"),
                )
            }
        }
        if (selected != null && selected in options) {
            val sources = options.filter { it.metric == selected.metric }
            if (sources.size > 1) {
                Text("Recorded ECU", color = MutedColor, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { key ->
                        FilterChip(
                            selected = key == selected,
                            onClick = { if (key != selected) onSelectSeries(key) },
                            label = { Text("ECU ${key.ecuId}") },
                            modifier = Modifier.testTag("trip_source_${key.ecuId}"),
                        )
                    }
                }
            }
            Text("${selected.metric.label} · ECU ${selected.ecuId} · ${selected.metric.unit}", color = MintColor, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("trip_series_source"))
        }
        when {
            isLoading -> {
                Text("Loading saved sensor readings…", color = MutedColor, modifier = Modifier.testTag("trip_series_loading"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trip_series_error"))
                TextButton(onClick = onRetry, enabled = selected != null, modifier = Modifier.testTag("retry_trip_series")) { Text("Retry chart") }
            }
            series != null && series.key == selected && selected in options -> SeriesContent(series, durationMillis)
            else -> Text("Choose a recorded sensor to view its chart.", color = MutedColor, modifier = Modifier.testTag("trip_series_prompt"))
        }
    }
}

@Composable
private fun SeriesContent(series: TripSeriesAnalysis, durationMillis: Long) {
    val metric = series.key.metric
    val segments = remember(series, durationMillis) { tripChartSegments(series, durationMillis) }
    val minimum = series.minValue?.takeIf(Double::isFinite)
    val maximum = series.maxValue?.takeIf(Double::isFinite)
    Text("${series.sampleCount} saved readings · ${series.validSampleCount} valid", modifier = Modifier.testTag("trip_series_counts"))
    Text(
        "Observed range: ${if (minimum != null && maximum != null) "${metric.chartValue(minimum)} to ${metric.chartValue(maximum)} ${metric.unit}" else "Not available"}",
        modifier = Modifier.testTag("trip_series_range"),
    )
    Text(
        "Valid interval coverage: ${chartDuration(series.coveredMillis)} · uncovered: ${chartDuration((durationMillis - series.coveredMillis).coerceAtLeast(0L))}",
        color = MutedColor,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("trip_series_coverage"),
    )
    if (series.validSampleCount == 0L || minimum == null || maximum == null || segments.isEmpty()) {
        Text("No valid recorded values for this sensor and ECU.", color = MutedColor, modifier = Modifier.testTag("empty_trip_chart"))
        return
    }
    val axis = tripChartAxis(metric, minimum, maximum)
    val end = durationMillis.coerceAtLeast(1L)
    Text("Scale: ${metric.chartValue(axis.minimum)} to ${metric.chartValue(axis.maximum)} ${metric.unit}", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trip_series_axis"))
    Box(if (metric == ObdMetric.SPEED) Modifier.testTag("trip_speed_chart") else Modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(160.dp).testTag("trip_sensor_chart").semantics {
                contentDescription = "Recorded ${metric.label.lowercase(Locale.ENGLISH)} chart from ECU ${series.key.ecuId}. ${series.validSampleCount} valid of ${series.sampleCount} saved readings. Observed minimum ${metric.chartValue(minimum)} ${metric.unit}, maximum ${metric.chartValue(maximum)} ${metric.unit}. Gaps are not joined."
            },
        ) {
            val inset = 5.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            val height = (size.height - 2 * inset).coerceAtLeast(1f)
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = inset + height * fraction
                drawLine(MutedColor.copy(alpha = 0.35f), Offset(inset, y), Offset(width + inset, y), 1.dp.toPx())
            }
            segments.forEach { segment ->
                var previous: Offset? = null
                segment.forEach { point ->
                    val current = Offset(
                        inset + width * (point.elapsedMillis.toDouble() / end).toFloat().coerceIn(0f, 1f),
                        inset + height * (1.0 - (point.value!! - axis.minimum) / (axis.maximum - axis.minimum)).toFloat().coerceIn(0f, 1f),
                    )
                    previous?.let { drawLine(AccentColor, it, current, 2.dp.toPx()) }
                    drawCircle(AccentColor, 2.dp.toPx(), current)
                    previous = current
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0:00", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        Text("${chartDuration(durationMillis)} elapsed", color = MutedColor, style = MaterialTheme.typography.bodySmall)
    }
    Text("This chart shows a reduced selection of saved readings. Gaps are not joined; statistics use all valid readings from this sensor and ECU.", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trip_series_chart_note"))
}

internal data class TripChartAxis(val minimum: Double, val maximum: Double)

internal fun tripChartAxis(metric: ObdMetric, minimum: Double, maximum: Double): TripChartAxis {
    val temperature = metric == ObdMetric.COOLANT_TEMPERATURE || metric == ObdMetric.INTAKE_TEMPERATURE
    return if (temperature) {
        if (minimum == maximum) TripChartAxis(minimum - 1.0, maximum + 1.0) else TripChartAxis(minimum, maximum)
    } else TripChartAxis(0.0, maximum.coerceAtLeast(1.0))
}

/** Compaction already preserves gap barriers: elapsed distance between retained points is not a new gap. */
internal fun tripChartSegments(series: TripSeriesAnalysis, durationMillis: Long): List<List<TripChartPoint>> {
    val segments = mutableListOf<List<TripChartPoint>>()
    var current = mutableListOf<TripChartPoint>()
    fun finish() { if (current.isNotEmpty()) segments += current; current = mutableListOf() }
    for (point in series.points) {
        if (point.ecuId != series.key.ecuId || point.value?.isFinite() != true || point.elapsedMillis !in 0L..durationMillis) {
            finish()
            continue
        }
        if (current.lastOrNull()?.let { point.elapsedMillis <= it.elapsedMillis } == true) finish()
        current += point
    }
    finish()
    return segments
}

private fun ObdMetric.chartChoiceLabel(): String = when (this) {
    ObdMetric.ENGINE_RPM -> "RPM"
    ObdMetric.SPEED -> "Speed"
    ObdMetric.COOLANT_TEMPERATURE -> "Coolant"
    ObdMetric.INTAKE_TEMPERATURE -> "Intake"
    ObdMetric.THROTTLE_POSITION -> "Throttle"
    ObdMetric.ENGINE_LOAD -> "Load"
}
private fun ObdMetric.chartValue(value: Double): String =
    String.format(Locale.ROOT, if (this == ObdMetric.THROTTLE_POSITION || this == ObdMetric.ENGINE_LOAD) "%.1f" else "%.0f", value)
private fun chartDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
