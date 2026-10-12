package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import java.util.Locale

/** Display preferences only: never a source of acquisition capabilities or sensor values. */
enum class DashboardCardStyle { DIGITAL, DIAL, BAR }
enum class DashboardCardSize { COMPACT, LARGE }
enum class DashboardColumns { ONE, TWO }
data class DashboardCardConfig(
    val metric: ObdMetric,
    val style: DashboardCardStyle = DashboardCardStyle.DIGITAL,
    val size: DashboardCardSize = DashboardCardSize.COMPACT,
    val showInOverlay: Boolean = false,
)

internal fun defaultCard(metric: ObdMetric) = DashboardCardConfig(metric,
    style = if (metric == ObdMetric.ENGINE_RPM) DashboardCardStyle.DIAL else DashboardCardStyle.DIGITAL,
    size = if (metric == ObdMetric.ENGINE_RPM) DashboardCardSize.LARGE else DashboardCardSize.COMPACT)

internal object DashboardLayoutCodec {
    fun encode(cards: List<DashboardCardConfig>): String = cards.joinToString("\n") {
        "${it.metric.name}:${it.style.name}:${it.size.name}:${if (it.showInOverlay) 1 else 0}"
    }
    fun decode(raw: String): List<DashboardCardConfig> {
        if (raw.length > 32_768) return emptyList()
        val seen = mutableSetOf<ObdMetric>()
        var overlayCount = 0
        return raw.lineSequence().take(240).mapNotNull { row ->
            val fields = row.split(':')
            val metric = ObdMetric.entries.firstOrNull { it.name == fields.firstOrNull() } ?: return@mapNotNull null
            if (!seen.add(metric)) return@mapNotNull null
            val style = DashboardCardStyle.entries.firstOrNull { it.name == fields.getOrNull(1) } ?: DashboardCardStyle.DIGITAL
            val size = DashboardCardSize.entries.firstOrNull { it.name == fields.getOrNull(2) } ?: DashboardCardSize.COMPACT
            val overlay = fields.getOrNull(3) == "1" && overlayCount < MAX_OVERLAY_METRICS
            if (overlay) overlayCount++
            DashboardCardConfig(metric, style, size, overlay)
        }.toList()
    }
    const val MAX_OVERLAY_METRICS = 6
}

internal fun modularDashboardModel(snapshot: ObdSessionSnapshot, cards: List<DashboardCardConfig>): DashboardModel {
    val selected = cards.map { it.metric }.distinct()
    val base = dashboardModel(snapshot, selected.toSet())
    val capabilities = snapshot.ecus.groupBy { it.ecuId }.mapValues { (_, values) -> values.flatMap { it.supportedPids }.toSet() }
    val known = capabilities.values.flatten().toSet()
    val sources = capabilities.map { (ecu, pids) ->
        val existing = base.ecus.first { it.ecuId == ecu }.readings.associateBy { it.metric }
        DashboardEcu(ecu, selected.map { metric ->
            if (metric.pid in pids) existing.getValue(metric).copy(isFavorite = true)
            else DashboardReading(metric, ecu, null, ObdReadingStatus.UNSUPPORTED, null, true)
        })
    }
    val visibleSources = if (sources.isEmpty() && selected.isNotEmpty()) listOf(DashboardEcu("Unknown", selected.map {
        DashboardReading(it, "Unknown", null, null, null, true)
    })) else sources
    return base.copy(ecus = visibleSources, availableExtras = ObdMetric.entries.filter { it.pid in known }.toSet(),
        unavailableFavorites = selected.filter { it.pid !in known }.toSet(), unavailablePrimary = emptyList())
}

internal fun metricDisplayValue(reading: DashboardReading): String = reading.value?.let {
    String.format(Locale.ROOT, "%.${reading.metric.displayDecimals}f", it)
} ?: "—"

internal fun metricStatusLabel(status: ObdReadingStatus?): String = when (status) {
    ObdReadingStatus.VALID -> "Current"
    ObdReadingStatus.STALE -> "Stale · last value"
    ObdReadingStatus.NO_DATA -> "No data"
    ObdReadingStatus.TIMEOUT -> "Timed out"
    ObdReadingStatus.PARSE_ERROR -> "Unreadable response"
    ObdReadingStatus.ADAPTER_ERROR -> "Adapter error"
    ObdReadingStatus.UNSUPPORTED -> "Unsupported"
    null -> "Awaiting reading"
}

/** Canonical decoder domain, not an engine safety limit. Values are never clipped numerically. */
internal fun metricProgress(reading: DashboardReading): Float? = reading.value?.takeIf(reading.metric::isValueInRange)?.let {
    ((it - reading.metric.minimumValue) / (reading.metric.maximumValue - reading.metric.minimumValue)).toFloat().coerceIn(0f, 1f)
}
