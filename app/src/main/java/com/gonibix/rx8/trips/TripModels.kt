package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus

enum class TripStatus { RECORDING, COMPLETED, INTERRUPTED }

data class TripSession(
    val id: String,
    val vehicleId: String,
    val vehicleAlias: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val durationMillis: Long = 0,
    val status: TripStatus = TripStatus.RECORDING,
    val endReason: String? = null,
    val protocolLabel: String? = null,
    val origin: String = "REAL_OBD",
    val appVersion: String? = null,
)

/** Sequence numbers are per trip; elapsed time is monotonic time since its explicit start. */
data class TripSample(
    val id: String,
    val tripId: String,
    val sequence: Long,
    val observedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val metric: ObdMetric,
    val value: Double?,
    val unit: String,
    val ecuId: String,
    val quality: ObdReadingStatus,
)

/** Implementations commit each mutation atomically. Sample pages are ordered by sequence. */
interface TripRepository {
    suspend fun recoverInterrupted()
    suspend fun create(session: TripSession)
    suspend fun append(tripId: String, samples: List<TripSample>)
    suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String)
    suspend fun list(limit: Int = 100): List<TripSession>
    suspend fun find(tripId: String): TripSession?
    suspend fun samples(tripId: String, afterSequence: Long = -1, limit: Int = 500): List<TripSample>
    suspend fun delete(tripId: String)
    suspend fun storageBytes(): Long
}

data class TripRecordingState(
    val session: TripSession? = null,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val savedSamples: Long = 0,
    val error: String? = null,
    val revision: Long = 0,
)
