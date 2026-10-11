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

/** Immutable ordering key; navigation does not require the referenced row to still exist. */
data class TripHistoryCursor(val startedAtEpochMillis: Long, val tripId: String)

enum class TripHistoryDirection { NEWEST, OLDER, NEWER, AT_OR_OLDER }

data class TripHistoryRequest(
    val vehicleId: String? = null,
    val cursor: TripHistoryCursor? = null,
    val direction: TripHistoryDirection = TripHistoryDirection.NEWEST,
    val limit: Int = 50,
)

/** Every page uses descending start-time/ID order. Empty pages have no navigation flags. */
data class TripHistoryPage(
    val trips: List<TripSession>,
    val hasNewer: Boolean = false,
    val hasOlder: Boolean = false,
)

/** Implementations commit each mutation atomically. Sample pages are ordered by sequence. */
interface TripRepository {
    suspend fun recoverInterrupted()
    suspend fun create(session: TripSession)
    suspend fun updateProtocol(tripId: String, protocolLabel: String)
    suspend fun append(tripId: String, samples: List<TripSample>)
    suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String)
    suspend fun list(limit: Int = 100): List<TripSession>
    suspend fun historyPage(request: TripHistoryRequest): TripHistoryPage
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
