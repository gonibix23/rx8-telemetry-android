package com.gonibix.rx8.sync

import java.io.IOException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

enum class SyncStep { DISABLED, IDLE, APPLIED, STALE_LEASE }

/** Preparation-only runner. Neither a worker nor an enabled transport is wired into the app. */
class SyncCoordinator(
    private val store: SyncStore,
    private val transport: SyncTransport = DisabledSyncTransport,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val randomFraction: () -> Double = { Random.nextDouble() },
    private val limits: BatchLimits = BatchLimits(),
    private val leaseMillis: Long = 30_000L,
    private val requestTimeoutMillis: Long = 10_000L,
) {
    init { require(requestTimeoutMillis > 0 && leaseMillis > requestTimeoutMillis) }

    suspend fun runOnce(): SyncStep {
        // These guards precede leasing, queue mutation, and the remote call.
        if (!transport.enabled || !store.isIsolatedTestStore) return SyncStep.DISABLED
        currentCoroutineContext().ensureActive()
        val lease = store.claimNext(nowEpochMillis(), leaseMillis, limits) ?: return SyncStep.IDLE
        val request = lease.request
        val result = if (request.payloadBytes > limits.maxBytes || request.sampleIds.size > limits.maxSamples) {
            splitOrBlock(request)
        } else {
            val reply = try {
                withTimeout(requestTimeoutMillis) { transport.send(request) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                RemoteReply.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                RemoteReply.Unavailable
            }
            currentCoroutineContext().ensureActive()
            when (reply) {
                RemoteReply.Disabled -> SyncResult.Disabled
                RemoteReply.Unavailable -> retry(lease, "remote_unavailable")
                is RemoteReply.MetadataAck -> verifiedOrRetry(lease, reply.acknowledgment)
                is RemoteReply.BatchAck -> verifiedOrRetry(lease, reply.acknowledgment)
                is RemoteReply.HttpError -> when (reply.statusCode) {
                    401, 403 -> SyncResult.Block("authentication_required")
                    409 -> SyncResult.Block("revision_or_content_conflict")
                    413 -> splitOrBlock(request)
                    408, 429 -> retry(lease, "http_${reply.statusCode}", reply.retryAfterMillis)
                    in 500..599 -> retry(lease, "server_error", reply.retryAfterMillis)
                    else -> SyncResult.Block("permanent_remote_rejection")
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val applied = try {
            store.applyResult(lease, result, nowEpochMillis())
        } catch (_: InvalidSyncAckException) {
            // A store validates again inside its transaction; rejection must have no partial writes.
            store.applyResult(lease, retry(lease, "invalid_ack"), nowEpochMillis())
        }
        return when {
            !applied -> SyncStep.STALE_LEASE
            result == SyncResult.Disabled -> SyncStep.DISABLED
            else -> SyncStep.APPLIED
        }
    }

    private fun verifiedOrRetry(lease: SyncLease, result: SyncResult): SyncResult = try {
        validateAcknowledgment(lease.request, result)
        result
    } catch (_: InvalidSyncAckException) {
        retry(lease, "invalid_ack")
    }

    private fun splitOrBlock(request: PreparedRequest): SyncResult {
        if (request.kind != SyncRequestKind.TELEMETRY || request.sampleIds.size <= 1 || request.payloadBytes <= 1) {
            return SyncResult.Block("single_request_too_large")
        }
        return SyncResult.Split(BatchLimits(
            maxSamples = min(limits.maxSamples, request.sampleIds.size / 2).coerceAtLeast(1),
            // Each child retains the envelope. Halving bytes could reject valid singletons.
            maxBytes = limits.maxBytes,
        ))
    }

    private fun retry(lease: SyncLease, error: String, retryAfterMillis: Long? = null): SyncResult {
        if (lease.attempt >= MAX_ATTEMPTS) return SyncResult.Block("retry_limit_reached")
        if (retryAfterMillis != null && retryAfterMillis > MAX_RETRY_AFTER_MILLIS) {
            // Do not retry earlier than an unbounded server instruction; require later review instead.
            return SyncResult.Block("retry_after_exceeds_policy")
        }
        val exponent = (lease.attempt - 1).coerceIn(0, 20)
        val baseDelay = (1_000L shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
        val random = randomFraction().takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5
        val jittered = (baseDelay * (0.8 + random * 0.4)).toLong().coerceIn(1L, MAX_BACKOFF_MILLIS)
        val delay = maxOf(jittered, retryAfterMillis?.coerceAtLeast(0L) ?: 0L)
        val now = nowEpochMillis().coerceAtLeast(0L)
        val nextAttempt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        return SyncResult.Retry(nextAttempt, error)
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
        const val MAX_BACKOFF_MILLIS = 3_600_000L
        const val MAX_RETRY_AFTER_MILLIS = 86_400_000L
    }
}
