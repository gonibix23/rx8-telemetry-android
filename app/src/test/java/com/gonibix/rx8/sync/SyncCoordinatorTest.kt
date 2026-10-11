package com.gonibix.rx8.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    @Test fun disabledGatewayAndNonisolatedStoreCannotClaimSendOrMutate() = runTest {
        val isolated = FixtureSyncStore()
        assertEquals(SyncStep.DISABLED, SyncCoordinator(isolated).runOnce())
        assertEquals(0, isolated.claims)
        val real = FixtureSyncStore(isIsolatedTestStore = false)
        val remote = FixtureRemote { acknowledged(it) }
        assertEquals(SyncStep.DISABLED, SyncCoordinator(real, remote).runOnce())
        assertEquals(0, real.claims)
        assertTrue(remote.requests.isEmpty())
        assertTrue(real.applied.isEmpty())
    }

    @Test fun emptyIsolatedQueueIsIdleWithoutRemoteCalls() = runTest {
        val store = FixtureSyncStore(request = null)
        val remote = FixtureRemote { acknowledged(it) }
        assertEquals(SyncStep.IDLE, SyncCoordinator(store, remote).runOnce())
        assertTrue(remote.requests.isEmpty())
    }

    @Test fun disabledAfterClaimReleasesLeaseWithoutAckOrArtificialFailure() = runTest {
        val store = FixtureSyncStore()
        val remote = FixtureRemote { RemoteReply.Disabled }
        assertEquals(SyncStep.DISABLED, SyncCoordinator(store, remote).runOnce())
        assertSame(SyncResult.Disabled, store.applied.single())
        assertFalse(store.activeLease)
        assertTrue(store.acknowledgedIds.isEmpty())
        assertEquals(1L, store.summary().pending)
    }

    @Test fun lostResponseRetriesFrozenPayloadAndSampleIdsWithoutDuplicateRemoteRows() = runTest {
        var now = 0L
        val store = FixtureSyncStore()
        val serverRows = mutableSetOf<String>()
        val bodies = mutableMapOf<String, String>()
        var loseResponse = true
        val remote = FixtureRemote { request ->
            assertTrue(bodies.putIfAbsent(request.resourceId, request.payloadJson).let { it == null || it == request.payloadJson })
            val results = request.sampleIds.map { SampleAck(it, if (serverRows.add(it)) AckStatus.STORED else AckStatus.ALREADY_STORED) }
            if (loseResponse) { loseResponse = false; throw IOException("Synthetic lost response") }
            RemoteReply.BatchAck(SyncResult.BatchAck(1, request.resourceId, results))
        }
        val firstRunner = SyncCoordinator(store, remote, nowEpochMillis = { now }, randomFraction = { 0.5 })
        assertEquals(SyncStep.APPLIED, firstRunner.runOnce())
        assertTrue(store.applied.single() is SyncResult.Retry)
        assertTrue(store.acknowledgedIds.isEmpty())
        now = 1_000
        // A new runner sees durable fixture state; no regenerated body is supplied.
        assertEquals(SyncStep.APPLIED, SyncCoordinator(store, remote, nowEpochMillis = { now }).runOnce())
        assertEquals(1, serverRows.size)
        assertEquals(serverRows, store.acknowledgedIds)
        assertSame(remote.requests[0], remote.requests[1])
        assertEquals(remote.requests[0].payloadJson, remote.requests[1].payloadJson)
    }

    @Test fun foreignDuplicateWrongSchemaWrongBatchAndEmptyAcksRetryWholeBody() = runTest {
        val request = wireRequest(sampleIds = listOf(wireSample().id, wireSample(1).id))
        val valid = SampleAck(request.sampleIds.first(), AckStatus.STORED)
        val bad = listOf(
            SyncResult.BatchAck(2, request.resourceId, listOf(valid)),
            SyncResult.BatchAck(1, WIRE_VEHICLE, listOf(valid)),
            SyncResult.BatchAck(1, request.resourceId, emptyList()),
            SyncResult.BatchAck(1, request.resourceId, listOf(valid, valid)),
            SyncResult.BatchAck(1, request.resourceId, listOf(valid, SampleAck(WIRE_VEHICLE, AckStatus.STORED))),
            SyncResult.BatchAck(1, request.resourceId, listOf(SampleAck(valid.sampleId, AckStatus.REJECTED))),
            SyncResult.BatchAck(1, request.resourceId, listOf(SampleAck(valid.sampleId, AckStatus.REJECTED, "unsafe\ncode"))),
        )
        for (ack in bad) {
            val store = FixtureSyncStore(request)
            SyncCoordinator(store, FixtureRemote { RemoteReply.BatchAck(ack) }, nowEpochMillis = { 0 }).runOnce()
            assertEquals("invalid_ack", (store.applied.single() as SyncResult.Retry).errorCode)
            assertTrue(store.acknowledgedIds.isEmpty())
        }
    }

    @Test fun metadataAcksRequireExactResourceRevisionSchemaAndKind() = runTest {
        val request = PreparedRequest(SyncRequestKind.VEHICLE, "entry", WIRE_VEHICLE, WIRE_VEHICLE, revision = 2, payloadJson = "{}")
        val bad = listOf(
            SyncResult.MetadataAck(1, WIRE_SESSION, 2), SyncResult.MetadataAck(1, WIRE_VEHICLE, 1),
            SyncResult.MetadataAck(2, WIRE_VEHICLE, 2), SyncResult.MetadataAck(1, WIRE_VEHICLE, 0),
        )
        for (ack in bad) {
            val store = FixtureSyncStore(request)
            SyncCoordinator(store, FixtureRemote { RemoteReply.MetadataAck(ack) }).runOnce()
            assertTrue(store.applied.single() is SyncResult.Retry)
        }
        val store = FixtureSyncStore(request)
        SyncCoordinator(store, FixtureRemote { RemoteReply.MetadataAck(SyncResult.MetadataAck(1, WIRE_VEHICLE, 2)) }).runOnce()
        assertTrue(store.applied.single() is SyncResult.MetadataAck)
        val wrongKind = FixtureSyncStore()
        SyncCoordinator(wrongKind, FixtureRemote { RemoteReply.MetadataAck(SyncResult.MetadataAck(1, WIRE_BATCH, 1)) }).runOnce()
        assertTrue(wrongKind.applied.single() is SyncResult.Retry)
    }

    @Test fun partialAckAndRejectionForwardOnlyValidatedResults() = runTest {
        val request = wireRequest(sampleIds = (0L..2L).map { wireSample(it).id })
        val ack = SyncResult.BatchAck(1, WIRE_BATCH, listOf(
            SampleAck(request.sampleIds[0], AckStatus.ALREADY_STORED),
            SampleAck(request.sampleIds[1], AckStatus.REJECTED, "invalid_unit"),
        ))
        val store = FixtureSyncStore(request)
        SyncCoordinator(store, FixtureRemote { RemoteReply.BatchAck(ack) }).runOnce()
        assertSame(ack, store.applied.single())
        assertEquals(setOf(request.sampleIds[0]), store.acknowledgedIds)
        assertFalse(store.acknowledgedIds.contains(request.sampleIds[2]))
    }

    @Test fun transactionAckValidationFailureBecomesRetryWithoutPartialAck() = runTest {
        val store = FixtureSyncStore().apply { rejectFirstAck = true }
        SyncCoordinator(store, FixtureRemote { acknowledged(it) }).runOnce()
        assertEquals("invalid_ack", (store.applied.single() as SyncResult.Retry).errorCode)
        assertTrue(store.acknowledgedIds.isEmpty())
    }

    @Test fun authenticationConflictsAndPermanentErrorsBlockWithoutAcknowledging() = runTest {
        for ((status, code) in mapOf(401 to "authentication_required", 403 to "authentication_required", 409 to "revision_or_content_conflict", 422 to "permanent_remote_rejection")) {
            val store = FixtureSyncStore()
            SyncCoordinator(store, FixtureRemote { RemoteReply.HttpError(status) }).runOnce()
            assertEquals(code, (store.applied.single() as SyncResult.Block).errorCode)
            assertTrue(store.acknowledgedIds.isEmpty())
        }
    }

    @Test fun twoSample413RetainsByteCeilingSoOrdinarySingletonChildrenCanSucceed() = runTest {
        val samples = listOf(wireSample(), wireSample(1))
        val original = wireRequest(samples.map { it.id }, ApiV1Json.batch(ApiV1Mapper.batch(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, samples)))
        val children = samples.mapIndexed { index, sample ->
            val id = "00000000-0000-4000-8000-${(900 + index).toString().padStart(12, '0')}"
            wireRequest(listOf(sample.id), ApiV1Json.batch(ApiV1Mapper.batch(id, WIRE_VEHICLE, WIRE_SESSION, listOf(sample))), id)
        }
        val threshold = children.maxOf { it.payloadBytes }
        assertTrue(original.payloadBytes > threshold)
        assertTrue(original.payloadBytes / 2 < threshold) // Regression: shared JSON envelope remains in each child.
        val store = FixtureSyncStore(original)
        val remote = FixtureRemote { request -> if (request.payloadBytes > threshold) RemoteReply.HttpError(413) else acknowledged(request) }
        SyncCoordinator(store, remote).runOnce()
        val split = store.applied.single() as SyncResult.Split
        assertEquals(1, split.limits.maxSamples)
        assertEquals(BatchLimits().maxBytes, split.limits.maxBytes)
        for (child in children) {
            assertTrue(child.payloadBytes <= split.limits.maxBytes)
            val childStore = FixtureSyncStore(child)
            SyncCoordinator(childStore, remote, limits = split.limits).runOnce()
            assertEquals(child.sampleIds.toSet(), childStore.acknowledgedIds)
        }
        assertEquals(3, remote.requests.size)
        assertEquals(3, remote.requests.map { it.resourceId }.distinct().size)
    }

    @Test fun singleton413BlocksAndNeverPretendsItWasAcknowledged() = runTest {
        val store = FixtureSyncStore()
        SyncCoordinator(store, FixtureRemote { RemoteReply.HttpError(413) }).runOnce()
        assertEquals("single_request_too_large", (store.applied.single() as SyncResult.Block).errorCode)
        assertTrue(store.acknowledgedIds.isEmpty())
    }

    @Test fun configuredPayloadLimitPreventsSendingOversizedRequest() = runTest {
        val store = FixtureSyncStore(wireRequest(payload = "éé"))
        val remote = FixtureRemote { acknowledged(it) }
        SyncCoordinator(store, remote, limits = BatchLimits(maxBytes = 3)).runOnce()
        assertTrue(store.applied.single() is SyncResult.Block)
        assertTrue(remote.requests.isEmpty())
    }

    @Test fun retryAfterIsHonoredJitterIsBoundedAndAttemptBudgetStopsRetry() = runTest {
        for (status in listOf(408, 429, 500, 503)) {
            val store = FixtureSyncStore()
            SyncCoordinator(store, FixtureRemote { RemoteReply.HttpError(status, 60_000) }, nowEpochMillis = { 10 }, randomFraction = { 0.0 }).runOnce()
            assertEquals(60_010L, (store.applied.single() as SyncResult.Retry).nextAttemptEpochMillis)
        }
        for ((fraction, expected) in listOf(-2.0 to 800L, 2.0 to 1200L, Double.NaN to 1000L)) {
            val store = FixtureSyncStore()
            SyncCoordinator(store, FixtureRemote { RemoteReply.Unavailable }, nowEpochMillis = { 0 }, randomFraction = { fraction }).runOnce()
            assertEquals(expected, (store.applied.single() as SyncResult.Retry).nextAttemptEpochMillis)
        }
        val exhausted = FixtureSyncStore(attempt = 8)
        SyncCoordinator(exhausted, FixtureRemote { RemoteReply.Unavailable }).runOnce()
        assertEquals("retry_limit_reached", (exhausted.applied.single() as SyncResult.Block).errorCode)
    }

    @Test fun largeRetryAfterBlocksRatherThanRetryingEarlyAndTimestampAdditionCannotOverflow() = runTest {
        val unbounded = FixtureSyncStore()
        SyncCoordinator(unbounded, FixtureRemote { RemoteReply.HttpError(429, Long.MAX_VALUE) }).runOnce()
        assertEquals("retry_after_exceeds_policy", (unbounded.applied.single() as SyncResult.Block).errorCode)
        val nearOverflow = FixtureSyncStore()
        SyncCoordinator(nearOverflow, FixtureRemote { RemoteReply.Unavailable }, nowEpochMillis = { Long.MAX_VALUE - 1 }).runOnce()
        assertEquals(Long.MAX_VALUE, (nearOverflow.applied.single() as SyncResult.Retry).nextAttemptEpochMillis)
    }

    @Test fun requestTimeoutSchedulesRetryButParentCancellationLeavesLeaseForRecovery() = runTest {
        val timeoutStore = FixtureSyncStore()
        val never = FixtureRemote { awaitCancellation() }
        SyncCoordinator(timeoutStore, never, nowEpochMillis = { testScheduler.currentTime }, requestTimeoutMillis = 50, leaseMillis = 100).runOnce()
        assertTrue(timeoutStore.applied.single() is SyncResult.Retry)
        val cancelledStore = FixtureSyncStore()
        val cancellation = FixtureRemote { throw CancellationException("Synthetic cancellation") }
        var cancelled = false
        try { SyncCoordinator(cancelledStore, cancellation).runOnce() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(cancelledStore.applied.isEmpty())
        assertTrue(cancelledStore.activeLease)
        val alreadyCancelled = Job().apply { cancel() }
        val neverClaimed = FixtureSyncStore()
        try { withContext(alreadyCancelled) { SyncCoordinator(neverClaimed, cancellation).runOnce() } } catch (_: CancellationException) { /* Expected. */ }
        assertEquals(0, neverClaimed.claims)
    }

    @Test fun lateAckRejectedByLeaseCompareAndSetDoesNotReportSuccess() = runTest {
        val store = FixtureSyncStore().apply { acceptLease = false }
        assertEquals(SyncStep.STALE_LEASE, SyncCoordinator(store, FixtureRemote { acknowledged(it) }).runOnce())
        assertTrue(store.applied.isEmpty())
        assertTrue(store.acknowledgedIds.isEmpty())
    }
}

/** Test-only remote. It has no URL, Android context, or access to the production database. */
private class FixtureRemote(private val reply: suspend (PreparedRequest) -> RemoteReply) : SyncTransport {
    override val enabled = true
    val requests = mutableListOf<PreparedRequest>()
    override suspend fun send(request: PreparedRequest): RemoteReply { requests += request; return reply(request) }
}

/** A deliberately isolated fixture; Room transaction and restart behavior has separate tests. */
private class FixtureSyncStore(
    private var request: PreparedRequest? = wireRequest(),
    override val isIsolatedTestStore: Boolean = true,
    private var attempt: Int = 1,
) : SyncStore {
    var claims = 0
    var activeLease = false
    var acceptLease = true
    var rejectFirstAck = false
    private var readyAt = 0L
    val applied = mutableListOf<SyncResult>()
    val acknowledgedIds = mutableSetOf<String>()
    override suspend fun claimNext(nowEpochMillis: Long, leaseMillis: Long, limits: BatchLimits): SyncLease? {
        claims++
        if (nowEpochMillis < readyAt) return null
        return request?.let { activeLease = true; SyncLease("token-$claims", it, Long.MAX_VALUE, attempt) }
    }
    override suspend fun applyResult(lease: SyncLease, result: SyncResult, nowEpochMillis: Long): Boolean {
        if (!acceptLease) return false
        if (result is SyncResult.BatchAck || result is SyncResult.MetadataAck) {
            validateAcknowledgment(lease.request, result)
            if (rejectFirstAck) { rejectFirstAck = false; throw InvalidSyncAckException() }
        }
        applied += result
        activeLease = false
        when (result) {
            is SyncResult.Retry -> { readyAt = result.nextAttemptEpochMillis; attempt++ }
            is SyncResult.BatchAck -> {
                acknowledgedIds += result.results.filter { it.status != AckStatus.REJECTED }.map { it.sampleId }
                request = null
            }
            is SyncResult.MetadataAck, is SyncResult.Block, is SyncResult.Split -> request = null
            SyncResult.Disabled -> Unit
        }
        return true
    }
    override suspend fun summary() = SyncQueueSummary(
        pending = if (activeLease) 0 else request?.sampleIds?.size?.toLong() ?: 0,
        sending = if (activeLease) request?.sampleIds?.size?.toLong() ?: 0 else 0,
        acknowledged = acknowledgedIds.size.toLong(),
    )
}

private fun acknowledged(request: PreparedRequest) = RemoteReply.BatchAck(
    SyncResult.BatchAck(1, request.resourceId, request.sampleIds.map { SampleAck(it, AckStatus.STORED) }),
)
