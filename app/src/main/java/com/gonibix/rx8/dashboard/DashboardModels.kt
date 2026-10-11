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

internal val primaryDashboardMetrics = listOf(
    ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE,
)
internal val extraDashboardMetrics = listOf(
    ObdMetric.INTAKE_TEMPERATURE, ObdMetric.THROTTLE_POSITION, ObdMetric.ENGINE_LOAD,
)

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
    val ecus = capabilities.map { (ecuId, pids) ->
        val visible = primaryDashboardMetrics + extraDashboardMetrics.filter { it in selectedExtras }
        DashboardEcu(ecuId, visible.filter { it.pid in pids }.map { metric ->
            val reading = snapshot.readings.firstOrNull { it.ecuId == ecuId && it.metric == metric }
            val age = reading?.let { (snapshot.elapsedMillis - it.elapsedMillis).coerceAtLeast(0L) }
            val status = when {
                reading?.status == ObdReadingStatus.VALID && reading.value?.isFinite() != true -> ObdReadingStatus.NO_DATA
                reading?.status == ObdReadingStatus.VALID && (stopped || (age ?: 0L) >= 5_000L) -> ObdReadingStatus.STALE
                else -> reading?.status
            }
            val value = reading?.value?.takeIf {
                it.isFinite() && (status == ObdReadingStatus.VALID || status == ObdReadingStatus.STALE)
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
    return ObdSessionSnapshot(
        phase = if (connected) ObdPhase.LIVE else ObdPhase.STOPPED,
        statusMessage = if (connected) "Synthetic sensor values. No vehicle is queried." else
            "Simulation disconnected. Return to Simulator to generate new values.",
        ecus = listOf(ObdEcuCapabilities("SIMULATOR", ObdMetric.entries.map { it.pid }.toSet())),
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
        supportedPidCount = ObdMetric.entries.size,
        elapsedMillis = snapshot.elapsedMs,
    )
}
