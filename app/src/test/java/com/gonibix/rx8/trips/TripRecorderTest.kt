package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdClock
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderTest {
    private val vehicle = VehicleProfile("00000000-0000-4000-8000-000000000001", "Synthetic RX-8", null, null, null, 0)
    private val clock = Clock()
    private fun snapshot(time: Long, status: ObdReadingStatus = ObdReadingStatus.VALID, phase: ObdPhase = ObdPhase.LIVE) =
        ObdSessionSnapshot(phase = phase, elapsedMillis = time, readings = listOf(
            ObdReading(ObdMetric.SPEED, 36.0, status, "7E8", 10_000 + time, time, 1_000 + time),
        ))

    @Test fun startRequiresLiveReadingsAndDoesNotBackfillTheDashboard() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock, appVersion = "synthetic-version")
        recorder.start(vehicle, snapshot(0, phase = ObdPhase.STOPPED))
        runCurrent()
        assertNull(repo.session)
        recorder.start(vehicle, snapshot(0))
        assertTrue(recorder.state.value.isStarting)
        assertNull(recorder.state.value.session)
        runCurrent()
        recorder.onSnapshot(snapshot(0))
        recorder.onSnapshot(snapshot(0, ObdReadingStatus.STALE))
        runCurrent()
        assertTrue(repo.saved.isEmpty())
        assertEquals(vehicle.id, repo.session!!.vehicleId)
        assertEquals("synthetic-version", repo.session!!.appVersion)
        assertEquals(1, repo.recoveries)
    }

    @Test fun allFreshReadingsAreSavedInOrderWithoutUiCollectors() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        runCurrent()
        repeat(100) { recorder.onSnapshot(snapshot((it + 1) * 100L)) }
        clock.now = 11_000
        recorder.stop()
        assertTrue(recorder.state.value.isStopping)
        runCurrent()
        assertEquals((0L..99L).toList(), repo.saved.map { it.sequence })
        assertEquals(100, repo.saved.map { it.id }.distinct().size)
        assertEquals(100L, repo.saved.first().elapsedMillis)
        assertEquals(TripStatus.COMPLETED, repo.session!!.status)
        assertEquals(10_000L, repo.session!!.durationMillis)
        assertNull(recorder.state.value.session)
        assertEquals(2L, recorder.state.value.revision)
    }

    @Test fun duplicateTicksAndStaleTransitionsAreNotNewMeasurementsButErrorsAre() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        recorder.onSnapshot(snapshot(100))
        repeat(4) { recorder.onSnapshot(snapshot(100)) }
        recorder.onSnapshot(snapshot(100, ObdReadingStatus.STALE))
        recorder.onSnapshot(snapshot(200, ObdReadingStatus.NO_DATA))
        recorder.onSnapshot(snapshot(300, ObdReadingStatus.NO_DATA))
        runCurrent()
        assertEquals(3, repo.saved.size)
        assertEquals(listOf(36.0, null, null), repo.saved.map { it.value })
        assertEquals(ObdReadingStatus.NO_DATA, repo.saved.last().quality)
    }

    @Test fun stopDuringStartWaitsForBothCommitsAndFreezesVehicleAssociation() = runTest {
        val repo = Repository().apply { creation = CompletableDeferred() }
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        runCurrent()
        recorder.start(vehicle.copy(id = "different", alias = "Other"), snapshot(0))
        recorder.onSnapshot(snapshot(100))
        clock.now = 1_200
        recorder.stop()
        recorder.onSnapshot(snapshot(300))
        assertTrue(recorder.state.value.isStarting)
        repo.creation!!.complete(Unit)
        runCurrent()
        assertEquals(vehicle.id, repo.session!!.vehicleId)
        assertEquals(1, repo.saved.size)
        assertEquals(TripStatus.COMPLETED, repo.session!!.status)
        assertFalse(recorder.state.value.isStopping)
    }

    @Test fun connectionFailureMarksAnInterruptedTripAndPreventsFurtherSamples() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        recorder.onSnapshot(snapshot(100))
        clock.now = 1_200
        recorder.onSnapshot(snapshot(200, ObdReadingStatus.TIMEOUT, ObdPhase.ERROR))
        recorder.onSnapshot(snapshot(300))
        runCurrent()
        assertEquals(2, repo.saved.size)
        assertNull(repo.saved.last().value)
        assertEquals(TripStatus.INTERRUPTED, repo.session!!.status)
        assertNotNull(recorder.state.value.error)
    }

    @Test fun boundedBacklogStopsVisiblyAndFlushesAcceptedSamples() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock, maxQueuedSamples = 2)
        recorder.start(vehicle, snapshot(0))
        recorder.onSnapshot(snapshot(100))
        recorder.onSnapshot(snapshot(200))
        clock.now = 1_300
        recorder.onSnapshot(snapshot(300))
        runCurrent()
        assertEquals(2, repo.saved.size)
        assertEquals(TripStatus.INTERRUPTED, repo.session!!.status)
        assertTrue(recorder.state.value.error!!.contains("could not keep up"))
    }

    @Test fun lowStorageCannotReportSuccessfulStart() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock, availableBytes = { 0 })
        recorder.start(vehicle, snapshot(0))
        runCurrent()
        assertNull(repo.session)
        assertNull(recorder.state.value.session)
        assertFalse(recorder.state.value.isStarting)
        assertTrue(recorder.state.value.error!!.contains("storage failed"))
    }

    @Test fun failedAppendPreservesEarlierSamplesAndDoesNotReportSuccess() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        recorder.onSnapshot(snapshot(100))
        runCurrent()
        repo.failAppend = true
        clock.now = 1_200
        recorder.onSnapshot(snapshot(200))
        recorder.stop()
        runCurrent()
        assertEquals(1, repo.saved.size)
        assertEquals(TripStatus.INTERRUPTED, repo.session!!.status)
        assertNotNull(recorder.state.value.error)
        assertNull(recorder.state.value.session)
    }

    @Test fun utcClockAdjustmentDoesNotChangeMonotonicElapsed() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.start(vehicle, snapshot(0))
        val reading = snapshot(500).readings.single().copy(observedAtEpochMillis = 100)
        recorder.onSnapshot(snapshot(500).copy(readings = listOf(reading)))
        clock.now = 1_700
        clock.utc = 200
        recorder.stop()
        runCurrent()
        assertEquals(500L, repo.saved.single().elapsedMillis)
        assertEquals(100L, repo.saved.single().observedAtEpochMillis)
        assertEquals(700L, repo.session!!.durationMillis)
    }

    @Test fun connectedJourneyIncludesInitializationAndFirstActualReadingButNoPlaceholders() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        clock.now = 1_400
        assertTrue(recorder.startConnected(vehicle, 10_000, 1_000))
        recorder.onSnapshot(ObdSessionSnapshot(phase = ObdPhase.INITIALIZING))
        recorder.onSnapshot(snapshot(500, ObdReadingStatus.NO_DATA).let {
            it.copy(readings = it.readings.map { reading -> reading.copy(isRequestResult = false) })
        })
        recorder.onSnapshot(snapshot(600).copy(protocolLabel = "ISO 15765-4 CAN (11 bit, 500 kbaud)"))
        clock.now = 1_800
        recorder.stop()
        runCurrent()
        assertEquals(10_000L, repo.session!!.startedAtEpochMillis)
        assertEquals(800L, repo.session!!.durationMillis)
        assertEquals(600L, repo.saved.single().elapsedMillis)
        assertEquals(0L, repo.saved.single().sequence)
        assertEquals("ISO 15765-4 CAN (11 bit, 500 kbaud)", repo.session!!.protocolLabel)
        assertEquals(1, repo.protocolUpdates)
    }

    @Test fun connectedJourneyDoesNotDropFirstActualNoDataOrRequirePlaceholderCallback() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.startConnected(vehicle, 10_000, 1_000)
        recorder.onSnapshot(snapshot(200, ObdReadingStatus.NO_DATA))
        recorder.onSnapshot(snapshot(300))
        runCurrent()
        assertEquals(listOf(null, 36.0), repo.saved.map { it.value })
        assertEquals(listOf(0L, 1L), repo.saved.map { it.sequence })
    }

    @Test fun failureBeforeAnyReadingLeavesAnEmptyInterruptedConnectedJourney() = runTest {
        val repo = Repository()
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.startConnected(vehicle, 10_000, 1_000)
        clock.now = 2_000
        recorder.onSnapshot(ObdSessionSnapshot(phase = ObdPhase.ERROR))
        runCurrent()
        assertTrue(repo.saved.isEmpty())
        assertEquals(TripStatus.INTERRUPTED, repo.session!!.status)
        assertEquals(1_000L, repo.session!!.durationMillis)
    }

    @Test fun connectionDuringPendingStopCannotReplaceTheFrozenVehicle() = runTest {
        val repo = Repository().apply { creation = CompletableDeferred() }
        val recorder = TripRecorder(repo, backgroundScope, clock)
        assertTrue(recorder.startConnected(vehicle, 10_000, 1_000))
        runCurrent()
        recorder.stop()
        assertFalse(recorder.startConnected(vehicle.copy(alias = "Other"), 20_000, 2_000))
        repo.creation!!.complete(Unit)
        runCurrent()
        assertEquals(vehicle.alias, repo.session!!.vehicleAlias)
        assertTrue(recorder.startConnected(vehicle.copy(alias = "Next journey"), 20_000, 2_000))
        runCurrent()
        assertEquals("Next journey", repo.session!!.vehicleAlias)
    }

    @Test fun discoveredProtocolIsDurableOnceAndFailureStopsRecordingVisibly() = runTest {
        val repo = Repository().apply { failProtocol = true }
        val recorder = TripRecorder(repo, backgroundScope, clock)
        recorder.startConnected(vehicle, 10_000, 1_000)
        recorder.onSnapshot(snapshot(100).copy(protocolLabel = "Synthetic protocol"))
        runCurrent()
        assertTrue(repo.saved.isEmpty())
        assertEquals(TripStatus.INTERRUPTED, repo.session!!.status)
        assertNotNull(recorder.state.value.error)
        assertNull(recorder.state.value.session)
    }

    private class Clock : ObdClock {
        var now = 1_000L
        var utc = 10_000L
        override fun epochMillis() = utc
        override fun monotonicMillis() = now
    }
    private class Repository : TripRepository {
        var session: TripSession? = null
        val saved = mutableListOf<TripSample>()
        var recoveries = 0
        var failAppend = false
        var failProtocol = false
        var protocolUpdates = 0
        var creation: CompletableDeferred<Unit>? = null
        override suspend fun recoverInterrupted() { recoveries++ }
        override suspend fun create(session: TripSession) { creation?.await(); this.session = session }
        override suspend fun updateProtocol(tripId: String, protocolLabel: String) {
            if (failProtocol) throw IOException("Synthetic metadata failure")
            protocolUpdates++
            session = requireNotNull(session).copy(protocolLabel = protocolLabel)
        }
        override suspend fun append(tripId: String, samples: List<TripSample>) {
            if (failAppend) throw IOException("Synthetic storage failure")
            saved.addAll(samples)
        }
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) {
            session = session!!.copy(endedAtEpochMillis = endedAtEpochMillis, durationMillis = durationMillis, status = status, endReason = reason)
        }
        override suspend fun list(limit: Int) = listOfNotNull(session)
        override suspend fun historyPage(request: TripHistoryRequest) = TripHistoryPage(list(request.limit))
        override suspend fun find(tripId: String) = session
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = saved.filter { it.sequence > afterSequence }.take(limit)
        override suspend fun delete(tripId: String) = Unit
        override suspend fun storageBytes() = 0L
    }
}
