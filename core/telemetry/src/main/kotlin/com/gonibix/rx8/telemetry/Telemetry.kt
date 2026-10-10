package com.gonibix.rx8.telemetry

enum class TelemetryMetric(val label: String, val unit: String) {
    ENGINE_RPM("Engine speed", "rpm"),
    SPEED("Vehicle speed", "km/h"),
    COOLANT_TEMPERATURE("Coolant temperature", "°C"),
    INTAKE_TEMPERATURE("Intake air temperature", "°C"),
    THROTTLE_POSITION("Throttle position", "%"),
    ENGINE_LOAD("Engine load", "%"),
}

enum class ReadingStatus {
    VALID,
    TIMEOUT,
    UNAVAILABLE,
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTED,
}

enum class SimulationScenario {
    NORMAL,
    TIMEOUT,
    MISSING_DATA,
}

enum class DataOrigin {
    SIMULATED,
}

data class TelemetryReading(
    val metric: TelemetryMetric,
    val value: Double?,
    val status: ReadingStatus,
    val elapsedMs: Long,
)

data class TelemetrySnapshot(
    val connection: ConnectionState,
    val scenario: SimulationScenario,
    val elapsedMs: Long,
    val readings: List<TelemetryReading>,
    val origin: DataOrigin = DataOrigin.SIMULATED,
)
