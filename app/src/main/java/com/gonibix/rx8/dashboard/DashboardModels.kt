package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.ReadingStatus
import com.gonibix.rx8.telemetry.TelemetrySnapshot
import com.gonibix.rx8.telemetry.TelemetryMetric

internal val primaryDashboardMetrics = listOf(
    ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE,
)
internal val extraDashboardMetrics = ObdMetric.entries.filterNot { it in primaryDashboardMetrics }

internal data class DashboardReading(
    val metric: ObdMetric,
    val ecuId: String,
    val value: Double?,
    val status: ObdReadingStatus?,
    val ageMillis: Long?,
    val isFavorite: Boolean,
)

internal data class DashboardEcu(val ecuId: String, val readings: List<DashboardReading>)

internal data class DashboardModel(
    val ecus: List<DashboardEcu>,
    val availableExtras: Set<ObdMetric>,
    val unavailableFavorites: Set<ObdMetric>,
    val unavailablePrimary: List<ObdMetric>,
    val hasCapabilities: Boolean,
    val stopped: Boolean,
)

/** Capability authority is per ECU; readings alone never establish sensor support. */
internal fun dashboardModel(snapshot: ObdSessionSnapshot, favorites: Set<ObdMetric>): DashboardModel {
    val selectedExtras = favorites.intersect(extraDashboardMetrics.toSet())
    val capabilities = snapshot.ecus.groupBy { it.ecuId }.mapValues { (_, entries) ->
        entries.flatMap { it.supportedPids }.toSet()
    }
    val knownPids = capabilities.values.flatten().toSet()
    val availableExtras = extraDashboardMetrics.filter { it.pid in knownPids }.toSet()
    val stopped = snapshot.phase == ObdPhase.STOPPED || snapshot.phase == ObdPhase.ERROR
    val readingsBySource = snapshot.readings.groupBy { it.ecuId to it.metric }
    val ecus = capabilities.map { (ecuId, pids) ->
        val visible = primaryDashboardMetrics + extraDashboardMetrics.filter { it in selectedExtras }
        DashboardEcu(ecuId, visible.filter { it.pid in pids }.map { metric ->
            val reading = readingsBySource[ecuId to metric]?.singleOrNull()
            val age = reading?.takeIf { it.elapsedMillis >= 0L && snapshot.elapsedMillis >= it.elapsedMillis }
                ?.let { snapshot.elapsedMillis - it.elapsedMillis }
            val status = when {
                reading?.status == ObdReadingStatus.VALID && reading.value?.let(metric::isValueInRange) != true -> ObdReadingStatus.NO_DATA
                reading?.status == ObdReadingStatus.VALID && (stopped || age == null || age >= metric.freshnessMillis) -> ObdReadingStatus.STALE
                else -> reading?.status
            }
            val value = reading?.value?.takeIf {
                metric.isValueInRange(it) && (status == ObdReadingStatus.VALID || status == ObdReadingStatus.STALE)
            }
            DashboardReading(metric, ecuId, value, status, age, metric in selectedExtras)
        })
    }
    return DashboardModel(
        ecus = ecus,
        availableExtras = availableExtras,
        unavailableFavorites = selectedExtras - availableExtras,
        unavailablePrimary = primaryDashboardMetrics.filter { it.pid !in knownPids },
        hasCapabilities = capabilities.isNotEmpty(),
        stopped = stopped,
    )
}

/** Synthetic capabilities are known from the demo, never inferred for the real vehicle. */
fun simulatorDashboardSnapshot(snapshot: TelemetrySnapshot): ObdSessionSnapshot {
    val connected = snapshot.connection == ConnectionState.CONNECTED
    val fixturePids = ObdMetric.entries.filter { metric -> TelemetryMetric.entries.any { it.name == metric.name } }.map { it.pid }.toSet()
    return ObdSessionSnapshot(
        phase = if (connected) ObdPhase.LIVE else ObdPhase.STOPPED,
        statusMessage = if (connected) "Synthetic sensor values. No vehicle is queried." else
            "Synthetic fixture disconnected. No current measurements are available.",
        ecus = listOf(ObdEcuCapabilities("SIMULATOR", fixturePids)),
        readings = snapshot.readings.map { reading ->
            val status = when (reading.status) {
                ReadingStatus.VALID -> if (connected) ObdReadingStatus.VALID else ObdReadingStatus.STALE
                ReadingStatus.TIMEOUT -> ObdReadingStatus.TIMEOUT
                ReadingStatus.UNAVAILABLE -> ObdReadingStatus.NO_DATA
            }
            ObdReading(
                metric = ObdMetric.valueOf(reading.metric.name),
                value = reading.value,
                status = status,
                ecuId = "SIMULATOR",
                observedAtEpochMillis = 0L, // Simulation has elapsed time, not a real sample timestamp.
                elapsedMillis = reading.elapsedMs,
            )
        },
        supportedPidCount = fixturePids.size,
        elapsedMillis = snapshot.elapsedMs,
    )
}
