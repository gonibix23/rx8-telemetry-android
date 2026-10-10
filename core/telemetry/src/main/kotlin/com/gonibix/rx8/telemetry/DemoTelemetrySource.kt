package com.gonibix.rx8.telemetry

import kotlin.math.round
import kotlin.math.sin

/**
 * Synthetic telemetry driven only by explicit commands, with no device, clock, or network access.
 * Values demonstrate the interface; they do not establish support for any real vehicle parameter.
 * The caller serializes commands and decides when to advance the simulated clock.
 */
class DemoTelemetrySource {
    private var currentSnapshot = TelemetrySnapshot(
        connection = ConnectionState.DISCONNECTED,
        scenario = SimulationScenario.NORMAL,
        elapsedMs = 0L,
        readings = emptyList(),
    )

    val snapshot: TelemetrySnapshot
        get() = currentSnapshot

    fun connect(): TelemetrySnapshot = update(connection = ConnectionState.CONNECTED)

    fun disconnect(): TelemetrySnapshot = update(connection = ConnectionState.DISCONNECTED)

    fun setScenario(scenario: SimulationScenario): TelemetrySnapshot = update(scenario = scenario)

    fun advanceBy(deltaMs: Long = 1_000L): TelemetrySnapshot {
        require(deltaMs >= 0L) { "The simulated interval must not be negative." }
        if (snapshot.connection == ConnectionState.DISCONNECTED || deltaMs == 0L) {
            return snapshot
        }
        require(deltaMs <= Long.MAX_VALUE - snapshot.elapsedMs) {
            "The simulated interval would overflow elapsed time."
        }
        return update(elapsedMs = snapshot.elapsedMs + deltaMs)
    }

    fun reset(): TelemetrySnapshot = update(
        connection = ConnectionState.DISCONNECTED,
        scenario = SimulationScenario.NORMAL,
        elapsedMs = 0L,
    )

    private fun update(
        connection: ConnectionState = snapshot.connection,
        scenario: SimulationScenario = snapshot.scenario,
        elapsedMs: Long = snapshot.elapsedMs,
    ): TelemetrySnapshot {
        currentSnapshot = TelemetrySnapshot(
            connection = connection,
            scenario = scenario,
            elapsedMs = elapsedMs,
            readings = if (connection == ConnectionState.CONNECTED) {
                readingsAt(elapsedMs, scenario)
            } else {
                emptyList()
            },
        )
        return snapshot
    }

    private fun readingsAt(
        elapsedMs: Long,
        scenario: SimulationScenario,
    ): List<TelemetryReading> {
        val seconds = (elapsedMs % 120_000L) / 1_000.0
        val speed = when {
            seconds < 12.0 -> 0.0
            seconds < 42.0 -> (seconds - 12.0) * 2.0
            seconds < 85.0 -> 60.0 + 6.0 * sin((seconds - 42.0) / 8.0)
            seconds < 115.0 -> (60.0 - (seconds - 85.0) * 2.0).coerceAtLeast(0.0)
            else -> 0.0
        }
        val warmup = elapsedMs.coerceAtMost(180_000L) / 180_000.0

        return TelemetryMetric.entries.map { metric ->
            val status = when {
                scenario == SimulationScenario.TIMEOUT -> ReadingStatus.TIMEOUT
                scenario == SimulationScenario.MISSING_DATA &&
                    (metric == TelemetryMetric.COOLANT_TEMPERATURE ||
                        metric == TelemetryMetric.INTAKE_TEMPERATURE) -> ReadingStatus.UNAVAILABLE
                else -> ReadingStatus.VALID
            }
            val value = if (status == ReadingStatus.VALID) {
                val syntheticValue = when (metric) {
                    TelemetryMetric.ENGINE_RPM -> if (speed < 1.0) {
                        850.0 + 30.0 * sin(seconds / 2.0)
                    } else {
                        1_400.0 + speed * 28.0 + 120.0 * sin(seconds / 4.0)
                    }
                    TelemetryMetric.SPEED -> speed
                    TelemetryMetric.COOLANT_TEMPERATURE ->
                        78.0 + 10.0 * warmup + 0.6 * sin(seconds / 15.0)
                    TelemetryMetric.INTAKE_TEMPERATURE -> 28.0 + 3.0 * sin(seconds / 25.0)
                    TelemetryMetric.THROTTLE_POSITION -> if (speed < 1.0) {
                        3.0 + 0.4 * sin(seconds)
                    } else {
                        18.0 + speed * 0.25 + 5.0 * sin(seconds / 6.0)
                    }
                    TelemetryMetric.ENGINE_LOAD ->
                        16.0 + speed * 0.6 + 2.0 * sin(seconds / 7.0)
                }
                round(syntheticValue * 10.0) / 10.0
            } else {
                null
            }
            TelemetryReading(
                metric = metric,
                value = value,
                status = status,
                elapsedMs = elapsedMs,
            )
        }
    }
}
