package com.gonibix.rx8.sync

import androidx.room.withTransaction
import com.gonibix.rx8.trips.TripSampleEntity
import com.gonibix.rx8.trips.TripSessionEntity
import com.gonibix.rx8.vehicle.VehicleDatabase
import java.util.UUID

/** Durable queue mechanics only. Constructing or observing this store never sends or claims work. */
class RoomSyncStore(
    private val database: VehicleDatabase,
    override val isIsolatedTestStore: Boolean = false,
) : SyncStore {
    private val dao get() = database.sync()

    override suspend fun summary(): SyncQueueSummary {
        val counts = dao.counts().associate { it.state to it.count }
        return SyncQueueSummary(counts[PENDING] ?: 0, counts[SENDING] ?: 0, counts[ACKNOWLEDGED] ?: 0, counts[BLOCKED] ?: 0)
    }

    override suspend fun claimNext(nowEpochMillis: Long, leaseMillis: Long, limits: BatchLimits): SyncLease? = database.withTransaction {
        require(nowEpochMillis >= 0 && leaseMillis in 1L..MAX_LEASE_MILLIS)
        val expiry = Math.addExact(nowEpochMillis, leaseMillis)
        // A new token invalidates callbacks from the old owner; its body and id remain unchanged.
        dao.expired(nowEpochMillis).forEach { expired -> release(expired, PENDING, nowEpochMillis, "lease_expired") }
        var work = SyncRequestKind.entries.firstNotNullOfOrNull { dao.nextWork(it.name, nowEpochMillis) }
        if (work == null) {
            val session = dao.nextUnbatchedSession() ?: return@withTransaction null
            val samples = dao.unbatchedSamples(session.id, limits.maxSamples)
            prepareBatches(session, samples, limits)
            work = dao.nextWork(SyncRequestKind.TELEMETRY.name, nowEpochMillis) ?: return@withTransaction null
        }
        val claimed = work.copy(
            state = SENDING, attempt = Math.addExact(work.attempt, 1),
            leaseToken = UUID.randomUUID().toString(), leaseExpiresAtEpochMillis = expiry, errorCode = null,
        )
        dao.updateWork(claimed)
        dao.batchReferences(claimed.id).forEach { dao.updateSample(it.copy(state = SENDING, errorCode = null)) }
        SyncLease(requireNotNull(claimed.leaseToken), request(claimed), expiry, claimed.attempt)
    }

    override suspend fun applyResult(lease: SyncLease, result: SyncResult, nowEpochMillis: Long): Boolean = database.withTransaction {
        require(nowEpochMillis >= 0)
        val work = dao.work(lease.request.entryId) ?: return@withTransaction false
        if (work.state != SENDING || work.leaseToken != lease.token || work.leaseExpiresAtEpochMillis != lease.expiresAtEpochMillis ||
            nowEpochMillis >= requireNotNull(work.leaseExpiresAtEpochMillis)) return@withTransaction false
        // The caller cannot replace the body or identifiers associated with a valid lease token.
        val actual = request(work)
        if (actual.kind != lease.request.kind || actual.resourceId != lease.request.resourceId || actual.revision != lease.request.revision ||
            actual.vehicleId != lease.request.vehicleId || actual.sessionId != lease.request.sessionId ||
            actual.payloadJson != lease.request.payloadJson || actual.sampleIds != lease.request.sampleIds) throw InvalidSyncAckException()
        if (result is SyncResult.MetadataAck || result is SyncResult.BatchAck) validateAcknowledgment(actual, result)
        when (result) {
            is SyncResult.MetadataAck -> {
                if (actual.kind == SyncRequestKind.TELEMETRY || result.schemaVersion != 1 ||
                    result.resourceId != actual.resourceId || result.revision != actual.revision) throw InvalidSyncAckException()
                release(work, ACKNOWLEDGED, 0, null)
            }
            is SyncResult.BatchAck -> applyBatchAck(work, actual, result)
            is SyncResult.Retry -> release(work, PENDING, maxOf(nowEpochMillis, result.nextAttemptEpochMillis), safeCode(result.errorCode))
            is SyncResult.Block -> release(work, BLOCKED, 0, safeCode(result.errorCode))
            is SyncResult.Split -> split(work, actual, result.limits)
            SyncResult.Disabled -> release(work, PENDING, nowEpochMillis, null)
        }
        true
    }

    private suspend fun applyBatchAck(work: SyncWorkEntity, request: PreparedRequest, acknowledgment: SyncResult.BatchAck) {
        val known = request.sampleIds.toSet()
        if (request.kind != SyncRequestKind.TELEMETRY || acknowledgment.schemaVersion != 1 || acknowledgment.batchId != request.resourceId ||
            acknowledgment.results.map { it.sampleId }.distinct().size != acknowledgment.results.size ||
            acknowledgment.results.any { it.sampleId !in known }) throw InvalidSyncAckException()
        // Validate the entire response before changing even one sample; an omitted sample stays pending.
        val outcomes = acknowledgment.results.associateBy { it.sampleId }
        dao.batchReferences(work.id).forEach { reference ->
            val result = outcomes[reference.sampleId]
            val state = when (result?.status) {
                AckStatus.STORED, AckStatus.ALREADY_STORED -> ACKNOWLEDGED
                AckStatus.REJECTED -> BLOCKED
                null -> PENDING
            }
            dao.updateSample(reference.copy(state = state, batchId = null,
                errorCode = if (state == BLOCKED) safeCode(result?.code ?: "rejected") else null))
        }
        // Resolved snapshots are retired, never rewritten. Any remainder receives a fresh batch ID.
        retire(work)
    }

    private suspend fun split(work: SyncWorkEntity, request: PreparedRequest, limits: BatchLimits) {
        if (request.kind != SyncRequestKind.TELEMETRY) {
            release(work, BLOCKED, 0, "metadata_too_large")
            return
        }
        val samples = dao.batchSamples(work.id)
        if (samples.size <= 1) {
            release(work, BLOCKED, 0, "sample_too_large")
            return
        }
        val session = requireNotNull(database.trips().find(requireNotNull(work.sessionId)))
        // Force a smaller sample count even if a caller supplied unchanged limits after HTTP 413.
        val smaller = limits.copy(maxSamples = minOf(limits.maxSamples, (samples.size + 1) / 2))
        dao.batchReferences(work.id).forEach { dao.updateSample(it.copy(state = PENDING, batchId = null, errorCode = null)) }
        prepareBatches(session, samples, smaller)
        retire(work)
    }

    private suspend fun retire(work: SyncWorkEntity) {
        // Retain the attempted body for inspection until explicit trip deletion; never reuse its ID.
        dao.updateWork(work.copy(state = RETIRED, leaseToken = null, leaseExpiresAtEpochMillis = null, errorCode = null))
    }

    private suspend fun release(work: SyncWorkEntity, state: String, nextAttempt: Long, error: String?) {
        dao.updateWork(work.copy(state = state, nextAttemptEpochMillis = nextAttempt,
            leaseToken = null, leaseExpiresAtEpochMillis = null, errorCode = error))
        dao.batchReferences(work.id).forEach { dao.updateSample(it.copy(state = state, errorCode = error)) }
    }

    private suspend fun request(work: SyncWorkEntity) = PreparedRequest(
        kind = SyncRequestKind.valueOf(work.kind), entryId = work.id, resourceId = work.resourceId,
        vehicleId = work.vehicleId, sessionId = work.sessionId, revision = work.revision,
        payloadJson = work.payloadJson, sampleIds = dao.batchSamples(work.id).map { it.id },
    )

    /** At most 500 rows are prepared at once. UTF-8 bytes, including envelope overhead, are bounded. */
    private suspend fun prepareBatches(session: TripSessionEntity, samples: List<TripSampleEntity>, limits: BatchLimits) {
        require(samples.size <= 500)
        var batchId = UUID.randomUUID().toString()
        var batch = mutableListOf<TripSampleEntity>()
        var body: String? = null
        suspend fun commitBatch() {
            if (batch.isEmpty()) return
            dao.insertWork(SyncWorkEntity(batchId, SyncRequestKind.TELEMETRY.name, batchId,
                session.vehicleId, session.id, null, requireNotNull(body)))
            batch.forEach { dao.updateSample(SyncSampleEntity(it.id, PENDING, batchId)) }
            batchId = UUID.randomUUID().toString()
            batch = mutableListOf()
            body = null
        }
        for (sample in samples) {
            val singleton = try { encode(batchId, session, listOf(sample)) } catch (_: IllegalArgumentException) {
                dao.updateSample(SyncSampleEntity(sample.id, BLOCKED, errorCode = "unsupported_sample"))
                continue
            }
            if (singleton.toByteArray(Charsets.UTF_8).size > limits.maxBytes) {
                dao.updateSample(SyncSampleEntity(sample.id, BLOCKED, errorCode = "sample_too_large"))
                continue
            }
            var candidate = encode(batchId, session, batch + sample)
            if (batch.size >= limits.maxSamples || candidate.toByteArray(Charsets.UTF_8).size > limits.maxBytes) {
                commitBatch()
                candidate = encode(batchId, session, listOf(sample))
            }
            batch += sample
            body = candidate
        }
        commitBatch()
    }

    private fun encode(id: String, session: TripSessionEntity, samples: List<TripSampleEntity>) =
        ApiV1Json.batch(ApiV1Mapper.batch(id, session.vehicleId, session.id, samples.map { it.syncModel() }))

    private fun safeCode(code: String): String = code.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "remote_error"

    private companion object { const val MAX_LEASE_MILLIS = 10L * 60 * 1000 }
}