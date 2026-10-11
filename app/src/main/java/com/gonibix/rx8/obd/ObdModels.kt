package com.gonibix.rx8.obd

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.telemetry.obd.ObdPid
import com.gonibix.rx8.telemetry.obd.VinResponse
import java.io.IOException

/** Real Mode 01 parameters; formulas and canonical units are owned by the pure parser module. */
enum class ObdMetric(val parameter: ObdPid) {
    ENGINE_RPM(ObdPid.ENGINE_RPM),
    SPEED(ObdPid.SPEED),
    COOLANT_TEMPERATURE(ObdPid.COOLANT_TEMPERATURE),
    INTAKE_TEMPERATURE(ObdPid.INTAKE_TEMPERATURE),
    THROTTLE_POSITION(ObdPid.THROTTLE_POSITION),
    ENGINE_LOAD(ObdPid.ENGINE_LOAD);

    val pid: Int get() = parameter.pid
    val label: String get() = parameter.label
    val unit: String get() = parameter.unit
}

enum class ObdPhase { IDLE, INITIALIZING, DISCOVERING, LIVE, ERROR, STOPPED }
enum class ObdReadingStatus { VALID, UNSUPPORTED, NO_DATA, TIMEOUT, PARSE_ERROR, ADAPTER_ERROR, STALE }

enum class ObdVinStatus { NOT_REQUESTED, READING, AVAILABLE, NO_DATA, ERROR }

data class ObdVinSnapshot(
    val status: ObdVinStatus = ObdVinStatus.NOT_REQUESTED,
    val message: String = "VIN reading is optional. A vehicle can use its saved ID without a VIN.",
    val candidates: List<VinResponse> = emptyList(),
)

data class ObdReading(
    val metric: ObdMetric,
    val value: Double?,
    val status: ObdReadingStatus,
    val ecuId: String,
    val observedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val monotonicMillis: Long? = null,
)

data class ObdEcuCapabilities(val ecuId: String, val supportedPids: Set<Int>)

data class ObdSessionSnapshot(
    val phase: ObdPhase = ObdPhase.IDLE,
    val statusMessage: String = "Readings have not started.",
    val adapterIdentity: String? = null,
    val protocolLabel: String? = null,
    val ecus: List<ObdEcuCapabilities> = emptyList(),
    val readings: List<ObdReading> = emptyList(),
    val supportedPidCount: Int = 0,
    val effectiveResponsesPerSecond: Double = 0.0,
    val elapsedMillis: Long = 0L,
    val vin: ObdVinSnapshot = ObdVinSnapshot(),
)

interface ObdSession {
    /** Runs until cancelled or a terminal failure; either outcome closes the owned connection. */
    suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit)

    /** Queues one optional VIN request between complete sensor exchanges. */
    fun requestVin(): Boolean = false
}

fun interface ObdSessionFactory {
    fun create(connection: AdapterConnection): ObdSession
}

class ObdSessionException(
    message: String,
    val linkClosed: Boolean = true,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Wall time timestamps a sample; monotonic time measures its age independently of clock changes. */
interface ObdClock {
    fun epochMillis(): Long
    fun monotonicMillis(): Long
}

object SystemObdClock : ObdClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
}
