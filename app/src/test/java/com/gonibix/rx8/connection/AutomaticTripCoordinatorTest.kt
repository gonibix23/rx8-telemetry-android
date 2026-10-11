package com.gonibix.rx8.connection

import com.gonibix.rx8.obd.ObdClock
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.trips.TripHistoryPage
import com.gonibix.rx8.trips.TripHistoryRequest
import com.gonibix.rx8.trips.TripRecorder
import com.gonibix.rx8.trips.TripRepository
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticTripCoordinatorTest {
    private val first = VehicleProfile("00000000-0000-4000-8000-000000000001", "First vehicle", null, null, null, 0)
    private val second = first.copy(id = "00000000-0000-4000-8000-000000000002", alias = "Second vehicle")

    @Test fun delayedProfileLookupKeepsSocketTimeAndFirstFreshResponseAndFreezesVehicle() = runTest {
        val fixture = Fixture(this)
        val selected = CompletableDeferred<VehicleProfile?>()
        fixture.loader = { selected.await() }
        val preparing = launch { fixture.coordinator.connected() }
        runCurrent()
        assertNull(fixture.repository.current)
        fixture.clock.now = 4_000
        selected.complete(first)
        runCurrent()
        assertTrue(preparing.isCompleted)
        assertEquals(11_000L, fixture.repository.current!!.startedAtEpochMillis)
        fixture.loader = { second }
        fixture.coordinator.refreshSelectedVehicle()
        fixture.recorder.onSnapshot(fixture.reading(0.0))
        runCurrent()
        assertEquals(first.id, fixture.repository.current!!.vehicleId)
        assertEquals(first.alias, fixture.repository.current!!.vehicleAlias)
        assertEquals(3_000L, fixture.repository.saved.single().elapsedMillis)
        assertEquals(0.0, fixture.repository.saved.single().value!!, 0.0)
    }

    @Test fun noProfileRecordsOnlyFreshValuesAfterLaterPersistedSelection() = runTest {
        val fixture = Fixture(this)
        fixture.loader = { null }
        fixture.coordinator.connected()
        runCurrent()
        assertTrue(fixture.awaiting)
        assertNull(fixture.repository.current)
        fixture.clock.now = 8_000
        fixture.current = fixture.reading(40.0)
        fixture.loader = { first }
        fixture.coordinator.refreshSelectedVehicle()
        runCurrent()
        assertFalse(fixture.awaiting)
        assertEquals(18_000L, fixture.repository.current!!.startedAtEpochMillis)
        fixture.recorder.onSnapshot(fixture.current)
        fixture.clock.now = 8_100
        fixture.recorder.onSnapshot(fixture.reading(41.0))
        runCurrent()
        assertEquals(listOf(41.0), fixture.repository.saved.map { it.value })
        assertEquals(100L, fixture.repository.saved.single().elapsedMillis)
    }

    @Test fun stopDuringProfileLookupPreventsAutomaticStartButAllowsManualStart() = runTest {
        val fixture = Fixture(this)
        val selected = CompletableDeferred<VehicleProfile?>()
        fixture.loader = { selected.await() }
        launch { fixture.coordinator.connected() }
        runCurrent()
        fixture.coordinator.stopRecording()
        selected.complete(first)
        runCurrent()
        fixture.coordinator.refreshSelectedVehicle()
        assertNull(fixture.repository.current)
        assertFalse(fixture.awaiting)
        fixture.current = fixture.reading(10.0)
        fixture.coordinator.startManually(second, fixture.current)
        runCurrent()
        assertEquals(second.id, fixture.repository.current!!.vehicleId)
    }

    @Test fun disconnectRejectsACancellationResistantSelectionResult() = runTest {
        val fixture = Fixture(this)
        val selected = CompletableDeferred<VehicleProfile?>()
        fixture.loader = { withContext(NonCancellable) { selected.await() } }
        val preparing = launch { fixture.coordinator.connected() }
        runCurrent()
        fixture.coordinator.ended(true)
        preparing.cancel()
        selected.complete(first)
        runCurrent()
        assertNull(fixture.repository.current)
        assertFalse(fixture.awaiting)
    }

    @Test fun olderLookupCannotAssignItsProfileToANewConnection() = runTest {
        val fixture = Fixture(this)
        val old = CompletableDeferred<VehicleProfile?>()
        fixture.loader = { withContext(NonCancellable) { old.await() } }
        val previous = launch { fixture.coordinator.connected() }
        runCurrent()
        fixture.coordinator.ended(false)
        previous.cancel()
        fixture.loader = { second }
        fixture.coordinator.connected()
        runCurrent()
        old.complete(first)
        runCurrent()
        assertEquals(1, fixture.repository.created.size)
        assertEquals(second.id, fixture.repository.current!!.vehicleId)
    }

    @Test fun explicitStopSurvivesSelectionRefreshAndANewConnectionCanRecordAgain() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.connected()
        runCurrent()
        fixture.coordinator.stopRecording()
        runCurrent()
        fixture.loader = { second }
        repeat(3) { fixture.coordinator.refreshSelectedVehicle() }
        runCurrent()
        assertEquals(1, fixture.repository.created.size)
        assertEquals(TripStatus.COMPLETED, fixture.repository.current!!.status)
        fixture.coordinator.ended(true)
        fixture.coordinator.connected()
        runCurrent()
        assertEquals(2, fixture.repository.created.size)
        assertEquals(second.id, fixture.repository.current!!.vehicleId)
    }

    @Test fun failedRecordingStartNeverLoopsOrBlocksLiveDataPreparation() = runTest {
        val fixture = Fixture(this)
        fixture.repository.failCreate = true
        fixture.coordinator.connected()
        runCurrent()
        assertNotNull(fixture.recorder.state.value.error)
        fixture.repository.failCreate = false
        fixture.coordinator.refreshSelectedVehicle()
        runCurrent()
        assertEquals(1, fixture.repository.createAttempts)
        assertNull(fixture.repository.current)
    }

    @Test fun nextConnectionWaitsForPreviousDurableFinishBeforeStartingItsTrip() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.connected()
        runCurrent()
        val finish = CompletableDeferred<Unit>()
        fixture.repository.finishGate = finish
        fixture.coordinator.ended(true)
        runCurrent()
        fixture.clock.now = 2_000
        fixture.loader = { second }
        val preparing = launch { fixture.coordinator.connected() }
        runCurrent()
        assertFalse(preparing.isCompleted)
        assertEquals(1, fixture.repository.created.size)
        fixture.clock.now = 4_000
        finish.complete(Unit)
        runCurrent()
        assertTrue(preparing.isCompleted)
        assertEquals(2, fixture.repository.created.size)
        assertEquals(12_000L, fixture.repository.current!!.startedAtEpochMillis)
        assertEquals(second.id, fixture.repository.current!!.vehicleId)
    }

    @Test fun disconnectWhilePreviousTripFinishesPreventsTheWaitingStart() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.connected()
        runCurrent()
        val finish = CompletableDeferred<Unit>()
        fixture.repository.finishGate = finish
        fixture.coordinator.ended(true)
        runCurrent()
        val preparing = launch { fixture.coordinator.connected() }
        runCurrent()
        fixture.coordinator.ended(true)
        finish.complete(Unit)
        preparing.join()
        runCurrent()
        assertEquals(1, fixture.repository.created.size)
    }

    @Test fun selectionLookupFailureAndTimeoutExplainRecoveryWithoutInventingAProfile() = runTest {
        val fixture = Fixture(this)
        fixture.loader = { throw IOException("synthetic private detail") }
        fixture.coordinator.connected()
        assertTrue(fixture.awaiting)
        assertTrue(fixture.message!!.contains("could not be loaded"))
        assertFalse(fixture.message!!.contains("private"))
        fixture.coordinator.ended(true)
        fixture.loader = { awaitCancellation() }
        val preparing = launch { fixture.coordinator.connected() }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(preparing.isCompleted)
        assertTrue(fixture.awaiting)
        assertNull(fixture.repository.current)
    }

    @Test fun selectionSavedWhileInitialLookupReturnsNoneIsRecheckedOnce() = runTest {
        val fixture = Fixture(this)
        val initial = CompletableDeferred<VehicleProfile?>()
        var calls = 0
        fixture.loader = { if (calls++ == 0) initial.await() else first }
        launch { fixture.coordinator.connected() }
        runCurrent()
        fixture.coordinator.refreshSelectedVehicle()
        initial.complete(null)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(first.id, fixture.repository.current!!.vehicleId)
        assertFalse(fixture.awaiting)
    }

    @Test fun selectionMadeBeforeLiveStartsAtSelectionTimeWithoutWaitingForLive() = runTest {
        val fixture = Fixture(this)
        fixture.loader = { null }
        fixture.coordinator.connected()
        fixture.clock.now = 6_000
        fixture.current = ObdSessionSnapshot(phase = ObdPhase.INITIALIZING)
        fixture.loader = { second }
        fixture.coordinator.refreshSelectedVehicle()
        runCurrent()
        assertEquals(16_000L, fixture.repository.current!!.startedAtEpochMillis)
        fixture.clock.now = 6_100
        fixture.recorder.onSnapshot(fixture.reading(0.0))
        runCurrent()
        assertEquals(100L, fixture.repository.saved.single().elapsedMillis)
    }

    private inner class Fixture(scope: TestScope) {
        val clock = Clock()
        val repository = Repository()
        val recorder = TripRecorder(repository, scope.backgroundScope, clock)
        var current = ObdSessionSnapshot()
        var loader: suspend () -> VehicleProfile? = { first }
        var awaiting = false
        var message: String? = null
        val coordinator = AutomaticTripCoordinator(recorder, scope.backgroundScope, { loader() }, { current }, { wait, text ->
            awaiting = wait
            message = text
        }, clock)
        fun reading(value: Double) = ObdSessionSnapshot(phase = ObdPhase.LIVE, readings = listOf(
            ObdReading(ObdMetric.SPEED, value, ObdReadingStatus.VALID, "7E8", clock.epochMillis(), clock.now, clock.now),
        ))
    }

    private class Clock : ObdClock {
        var now = 1_000L
        override fun monotonicMillis() = now
        override fun epochMillis() = 10_000L + now
    }

    private class Repository : TripRepository {
        var current: TripSession? = null
        val created = mutableListOf<TripSession>()
        val saved = mutableListOf<TripSample>()
        var failCreate = false
        var createAttempts = 0
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun recoverInterrupted() = Unit
        override suspend fun create(session: TripSession) {
            createAttempts++
            if (failCreate) throw IOException("synthetic write failure")
            current = session
            created += session
        }
        override suspend fun updateProtocol(tripId: String, protocolLabel: String) { current = current!!.copy(protocolLabel = protocolLabel) }
        override suspend fun append(tripId: String, samples: List<TripSample>) { saved += samples }
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) {
            finishGate?.await()
            current = current!!.copy(endedAtEpochMillis = endedAtEpochMillis, durationMillis = durationMillis, status = status, endReason = reason)
        }
        override suspend fun list(limit: Int) = listOfNotNull(current)
        override suspend fun historyPage(request: TripHistoryRequest) = TripHistoryPage(list(request.limit))
        override suspend fun find(tripId: String) = current
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = saved.filter { it.tripId == tripId && it.sequence > afterSequence }.take(limit)
        override suspend fun delete(tripId: String) = Unit
        override suspend fun storageBytes() = 0L
    }
}
