package com.gonibix.rx8.sync

import java.util.Collections

data class BatchLimits(val maxSamples: Int = 100, val maxBytes: Int = 256 * 1024) {
    init {
        require(maxSamples in 1..500)
        require(maxBytes in 1..1024 * 1024)
    }
}

enum class SyncRequestKind { VEHICLE, SESSION, TELEMETRY }

/** A durable snapshot. Retries send this exact JSON and identity, never a regenerated body. */
class PreparedRequest(
    val kind: SyncRequestKind,
    val entryId: String,
    val resourceId: String,
    val vehicleId: String,
    val sessionId: String? = null,
    val revision: Long? = null,
    val payloadJson: String,
    sampleIds: List<String> = emptyList(),
) {
    val sampleIds: List<String> = Collections.unmodifiableList(sampleIds.toList())
    val payloadBytes: Int get() = payloadJson.toByteArray(Charsets.UTF_8).size
    val idempotencyKey: String? get() = resourceId.takeIf { kind == SyncRequestKind.TELEMETRY }
}

data class SyncLease(
    val token: String,
    val request: PreparedRequest,
    val expiresAtEpochMillis: Long,
    val attempt: Int = 1,
)

data class SyncQueueSummary(
    val pending: Long = 0,
    val sending: Long = 0,
    val acknowledged: Long = 0,
    val blocked: Long = 0,
)

enum class AckStatus { STORED, ALREADY_STORED, REJECTED }
data class SampleAck(val sampleId: String, val status: AckStatus, val code: String? = null)

sealed interface SyncResult {
    data class MetadataAck(val schemaVersion: Int, val resourceId: String, val revision: Long) : SyncResult
    class BatchAck(val schemaVersion: Int, val batchId: String, results: List<SampleAck>) : SyncResult {
        val results: List<SampleAck> = Collections.unmodifiableList(results.toList())
    }
    data class Retry(val nextAttemptEpochMillis: Long, val errorCode: String) : SyncResult
    data class Block(val errorCode: String) : SyncResult
    data class Split(val limits: BatchLimits) : SyncResult
    data object Disabled : SyncResult
}

class InvalidSyncAckException(message: String = "The acknowledgment did not match the prepared request.") : IllegalArgumentException(message)

/** Validate the entire response before marking even one sample as acknowledged. */
fun validateAcknowledgment(request: PreparedRequest, result: SyncResult) {
    val valid = when (result) {
        is SyncResult.MetadataAck -> request.kind != SyncRequestKind.TELEMETRY &&
            result.schemaVersion == 1 && result.resourceId == request.resourceId &&
            result.revision > 0 && result.revision == request.revision
        is SyncResult.BatchAck -> request.kind == SyncRequestKind.TELEMETRY &&
            result.schemaVersion == 1 && result.batchId == request.resourceId && result.results.isNotEmpty() &&
            result.results.size <= request.sampleIds.size &&
            result.results.map { it.sampleId }.distinct().size == result.results.size &&
            result.results.all { acknowledgment ->
                acknowledgment.sampleId in request.sampleIds &&
                    (acknowledgment.status != AckStatus.REJECTED ||
                        acknowledgment.code?.matches(Regex("[A-Za-z0-9_.-]{1,64}")) == true)
            }
        else -> false
    }
    if (!valid) throw InvalidSyncAckException()
}

interface SyncStore {
    /** Fake transports are permitted only for isolated stores; production requires HTTPS. */
    val isIsolatedTestStore: Boolean get() = false
    suspend fun claimNext(nowEpochMillis: Long, leaseMillis: Long, limits: BatchLimits): SyncLease?
    /** Validates the whole ACK and commits atomically. Stale/expired/deleted leases return false. */
    suspend fun applyResult(lease: SyncLease, result: SyncResult, nowEpochMillis: Long): Boolean
    suspend fun summary(): SyncQueueSummary
}

sealed interface RemoteReply {
    data class MetadataAck(val acknowledgment: SyncResult.MetadataAck) : RemoteReply
    data class BatchAck(val acknowledgment: SyncResult.BatchAck) : RemoteReply
    data class HttpError(val statusCode: Int, val retryAfterMillis: Long? = null) : RemoteReply
    data object Unavailable : RemoteReply
    data object InvalidAcknowledgment : RemoteReply
    data object Disabled : RemoteReply
}

/** Prepared bodies are immutable across network attempts. */
interface SyncTransport {
    val enabled: Boolean
    suspend fun send(request: PreparedRequest): RemoteReply
}

object DisabledSyncTransport : SyncTransport {
    override val enabled: Boolean = false
    override suspend fun send(request: PreparedRequest): RemoteReply = RemoteReply.Disabled
}
