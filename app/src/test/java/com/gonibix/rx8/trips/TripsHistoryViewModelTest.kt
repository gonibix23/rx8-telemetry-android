package com.gonibix.rx8.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gonibix.rx8.sync.SyncQueueSummary
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripsHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val repository = HistoryRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { repository.releaseGates(); models.clear(); Dispatchers.resetMain() }

    @Test fun navigationUsesBothCursorFieldsAndNeverAccumulatesMoreThanOnePage() = runTest(dispatcher) {
        val first = (151 downTo 102).map { trip(it) }
        val second = (101 downTo 52).map { trip(it) }
        val third = (51 downTo 2).map { trip(it) }
        val last = listOf(trip(1))
        repository.enqueue(TripHistoryPage(first, hasOlder = true))
        val model = model()
        runCurrent()
        for ((index, rows) in listOf(second, third, last).withIndex()) {
            val previous = model.uiState.value.trips
            repository.enqueue(TripHistoryPage(rows, hasNewer = true, hasOlder = index < 2))
            model.olderTrips()
            runCurrent()
            val request = repository.requests.last()
            assertEquals(TripHistoryDirection.OLDER, request.direction)
            assertEquals(TripHistoryCursor(1_000L, previous.last().id), request.cursor)
            assertEquals(50, request.limit)
            assertEquals(rows, model.uiState.value.trips)
            assertTrue(model.uiState.value.trips.size <= 50)
        }
        val calls = repository.requests.size
        model.olderTrips()
        runCurrent()
        assertEquals(calls, repository.requests.size)
        repository.enqueue(TripHistoryPage(third, hasNewer = true, hasOlder = true))
        model.newerTrips()
        runCurrent()
        assertEquals(TripHistoryDirection.NEWER, repository.requests.last().direction)
        assertEquals(TripHistoryCursor(1_000L, last.first().id), repository.requests.last().cursor)
        assertEquals(third, model.uiState.value.trips)
    }

    @Test fun refreshOnAnOlderPagePreservesFilterDetailsAndAnchorWhileNewestIsExplicit() = runTest(dispatcher) {
        val first = listOf(trip(20))
        val older = listOf(trip(10), trip(9))
        repository.enqueue(TripHistoryPage(first, hasOlder = true))
        val model = model()
        runCurrent()
        repository.enqueue(TripHistoryPage(older, hasNewer = true))
        model.olderTrips()
        runCurrent()
        model.selectTrip(older.first().id)
        val detail = model.uiState.first { it.detail != null }.detail
        val revision = model.uiState.value.historyRevision
        repository.enqueue(TripHistoryPage(older, hasNewer = true))
        model.refresh()
        runCurrent()
        assertEquals(TripHistoryRequest(null, TripHistoryCursor(1_000, older.first().id), TripHistoryDirection.AT_OR_OLDER, 50), repository.requests.last())
        assertSame(detail, model.uiState.value.detail)
        assertEquals(older.first().id, model.uiState.value.selectedTripId)
        assertEquals(revision, model.uiState.value.historyRevision)
        model.closeDetails()
        assertEquals(older, model.uiState.value.trips)
        repository.enqueue(TripHistoryPage(first, hasOlder = true))
        model.newestTrips()
        runCurrent()
        assertEquals(TripHistoryDirection.NEWEST, repository.requests.last().direction)
        assertNull(repository.requests.last().cursor)
        assertEquals(revision + 1, model.uiState.value.historyRevision)
    }

    @Test fun filteringClearsWrongRowsImmediatelyAndNeverMutatesARecordingVehicle() = runTest(dispatcher) {
        val active = trip(20).copy(status = TripStatus.RECORDING)
        repository.enqueue(TripHistoryPage(listOf(active)))
        val model = model()
        runCurrent()
        val gate = repository.gate()
        model.selectHistoryVehicle(VEHICLE_B)
        assertEquals(VEHICLE_B, model.uiState.value.historyVehicleId)
        assertTrue(model.uiState.value.trips.isEmpty())
        assertEquals(1L, model.uiState.value.historyRevision)
        assertFalse(model.uiState.value.hasNewerTrips)
        assertFalse(model.uiState.value.hasOlderTrips)
        runCurrent()
        gate.success(TripHistoryPage(listOf(trip(10, VEHICLE_B))))
        runCurrent()
        assertEquals(VEHICLE_B, repository.requests.last().vehicleId)
        assertEquals(0, repository.recordingMutations)
        assertEquals(active, repository.sessions[active.id])
    }

    @Test fun sameFilterFailureKeepsTheVisiblePageAndRetryRepeatsTheFailedNavigation() = runTest(dispatcher) {
        val first = listOf(trip(20))
        repository.enqueue(TripHistoryPage(first, hasOlder = true))
        val model = model()
        runCurrent()
        repository.fail()
        model.olderTrips()
        runCurrent()
        val failedRequest = repository.requests.last()
        assertEquals(first, model.uiState.value.trips)
        assertTrue(model.uiState.value.hasOlderTrips)
        assertNotNull(model.uiState.value.historyError)
        assertNull(model.uiState.value.error)
        assertEquals(0L, model.uiState.value.historyRevision)
        repository.enqueue(TripHistoryPage(listOf(trip(10)), hasNewer = true))
        model.retryHistory()
        runCurrent()
        assertEquals(failedRequest, repository.requests.last())
        assertNull(model.uiState.value.historyError)
        assertEquals(listOf(trip(10)), model.uiState.value.trips)
        assertEquals(1L, model.uiState.value.historyRevision)
    }

    @Test fun failedNewFilterNeverPresentsPreviousVehicleRowsAndCanRetryAnEmptyResult() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(listOf(trip(20))))
        val model = model()
        runCurrent()
        repository.fail()
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        assertTrue(model.uiState.value.trips.isEmpty())
        assertEquals(VEHICLE_B, model.uiState.value.historyVehicleId)
        assertNotNull(model.uiState.value.historyError)
        repository.enqueue(TripHistoryPage(emptyList()))
        model.retryHistory()
        runCurrent()
        assertTrue(model.uiState.value.trips.isEmpty())
        assertNull(model.uiState.value.historyError)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(VEHICLE_B, repository.requests.last().vehicleId)
    }

    @Test fun aToBToARejectsTheFirstSlowSuccessEvenWhenItsVehicleMatchesAgain() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(emptyList()))
        val model = model()
        runCurrent()
        val obsolete = repository.gate()
        model.selectHistoryVehicle(VEHICLE_A)
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(8, VEHICLE_B))))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        val current = listOf(trip(9))
        repository.enqueue(TripHistoryPage(current))
        model.selectHistoryVehicle(VEHICLE_A)
        runCurrent()
        obsolete.success(TripHistoryPage(listOf(trip(1))))
        runCurrent()
        assertEquals(current, model.uiState.value.trips)
        assertEquals(VEHICLE_A, model.uiState.value.historyVehicleId)
        assertNull(model.uiState.value.historyError)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun obsoleteFailureDoesNotReplaceACompletedFilterOrItsLoadingState() = runTest(dispatcher) {
        val obsolete = repository.gate()
        val model = model()
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(3, VEHICLE_B))))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        obsolete.failure()
        runCurrent()
        assertEquals(listOf(trip(3, VEHICLE_B)), model.uiState.value.trips)
        assertNull(model.uiState.value.historyError)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun lifecycleRefreshDuringNavigationKeepsItsRequestedDestination() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(listOf(trip(20)), hasOlder = true))
        val model = model()
        runCurrent()
        val old = repository.gate()
        model.olderTrips()
        runCurrent()
        val requested = repository.requests.last()
        repository.enqueue(TripHistoryPage(listOf(trip(10)), hasNewer = true))
        model.refresh()
        runCurrent()
        assertEquals(requested, repository.requests.last())
        old.success(TripHistoryPage(listOf(trip(11)), hasNewer = true))
        runCurrent()
        assertEquals(listOf(trip(10)), model.uiState.value.trips)
        assertEquals(1L, model.uiState.value.historyRevision)
    }

    @Test fun cancelledEmptyPageDoesNotTriggerAnObsoleteFallbackQuery() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(listOf(trip(20)), hasOlder = true))
        val model = model()
        runCurrent()
        val obsolete = repository.gate()
        model.olderTrips()
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(5, VEHICLE_B))))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        val calls = repository.requests.size
        obsolete.success(TripHistoryPage(emptyList()))
        runCurrent()
        assertEquals(calls, repository.requests.size)
        assertEquals(listOf(trip(5, VEHICLE_B)), model.uiState.value.trips)
        assertNull(model.uiState.value.historyError)
    }

    @Test fun duplicatePageClicksDoNotScheduleAdditionalLoads() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(listOf(trip(20)), hasOlder = true))
        val model = model()
        runCurrent()
        val gate = repository.gate()
        model.olderTrips()
        model.olderTrips()
        model.newerTrips()
        model.newestTrips()
        runCurrent()
        assertEquals(2, repository.requests.size)
        gate.success(TripHistoryPage(listOf(trip(10)), hasNewer = true))
        runCurrent()
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun deletingTheAnchorRefillsTheSameFilteredRangeUsingItsOriginalCursor() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(emptyList()))
        val model = model()
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(20, VEHICLE_B)), hasOlder = true))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        val anchor = trip(10, VEHICLE_B)
        repository.enqueue(TripHistoryPage(listOf(anchor, trip(9, VEHICLE_B)), hasNewer = true, hasOlder = true))
        model.olderTrips()
        runCurrent()
        val revision = model.uiState.value.historyRevision
        repository.enqueue(TripHistoryPage(listOf(trip(9, VEHICLE_B), trip(8, VEHICLE_B)), hasNewer = true))
        model.deleteTrip(anchor.id)
        runCurrent()
        assertEquals(TripHistoryRequest(VEHICLE_B, TripHistoryCursor(1_000, anchor.id), TripHistoryDirection.AT_OR_OLDER, 50), repository.requests.last())
        assertEquals(listOf(trip(9, VEHICLE_B), trip(8, VEHICLE_B)), model.uiState.value.trips)
        assertEquals(VEHICLE_B, model.uiState.value.historyVehicleId)
        assertEquals(revision, model.uiState.value.historyRevision)
        assertNull(repository.sessions[anchor.id])
    }

    @Test fun deletingTheFinalRowFallsBackOnceToNewestWithinTheSameFilter() = runTest(dispatcher) {
        repository.enqueue(TripHistoryPage(emptyList()))
        val model = model()
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(20, VEHICLE_B)), hasOlder = true))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        val last = trip(10, VEHICLE_B)
        repository.enqueue(TripHistoryPage(listOf(last), hasNewer = true))
        model.olderTrips()
        runCurrent()
        repository.enqueue(TripHistoryPage(emptyList()))
        repository.enqueue(TripHistoryPage(emptyList()))
        val calls = repository.requests.size
        model.deleteTrip(last.id)
        runCurrent()
        assertEquals(calls + 2, repository.requests.size)
        assertEquals(TripHistoryRequest(vehicleId = VEHICLE_B, limit = 50), repository.requests.last())
        assertTrue(model.uiState.value.trips.isEmpty())
        assertNull(model.uiState.value.historyError)
        assertFalse(model.uiState.value.hasOlderTrips)
        assertFalse(model.uiState.value.hasNewerTrips)
    }

    @Test fun deletionInvalidatesAnOlderPageRequestThatCompletesLate() = runTest(dispatcher) {
        val first = trip(20)
        repository.enqueue(TripHistoryPage(listOf(first), hasOlder = true))
        val model = model()
        runCurrent()
        val obsolete = repository.gate()
        model.olderTrips()
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(19))))
        model.deleteTrip(first.id)
        runCurrent()
        obsolete.success(TripHistoryPage(listOf(first), hasNewer = true))
        runCurrent()
        assertEquals(listOf(trip(19)), model.uiState.value.trips)
        assertNull(repository.sessions[first.id])
        assertFalse(model.uiState.value.isDeleting)
    }

    @Test fun committedDeletionDoesNotResurrectItsRowIfRefillFails() = runTest(dispatcher) {
        val current = trip(20)
        repository.enqueue(TripHistoryPage(listOf(current)))
        val model = model()
        runCurrent()
        repository.fail()
        model.deleteTrip(current.id)
        runCurrent()
        assertTrue(model.uiState.value.trips.isEmpty())
        assertNotNull(model.uiState.value.historyError)
        assertTrue(model.uiState.value.message!!.contains("deleted"))
        assertNull(repository.sessions[current.id])
    }

    @Test fun storageFailureCannotHideHistoryOrQueueAndRefreshRecoversWithoutFabricatedZero() = runTest(dispatcher) {
        repository.failStorage = true
        repository.enqueue(TripHistoryPage(listOf(trip(20))))
        val model = model { SyncQueueSummary(pending = 7) }
        runCurrent()
        assertEquals(listOf(trip(20)), model.uiState.value.trips)
        assertNull(model.uiState.value.historyError)
        assertNull(model.uiState.value.storageBytes)
        assertNotNull(model.uiState.value.storageError)
        assertFalse(model.uiState.value.isLoadingStorage)
        assertEquals(7L, model.uiState.value.syncStatus!!.summary!!.pending)
        repository.failStorage = false
        repository.enqueue(TripHistoryPage(listOf(trip(20))))
        model.refresh()
        runCurrent()
        assertEquals(4_096L, model.uiState.value.storageBytes)
        assertNull(model.uiState.value.storageError)
    }

    @Test fun aSlowStorageReadDoesNotDelayOrOverwriteHistory() = runTest(dispatcher) {
        val bytes = CompletableDeferred<Long>()
        repository.readStorage = { bytes.await() }
        repository.enqueue(TripHistoryPage(listOf(trip(20))))
        val model = model()
        runCurrent()
        assertFalse(model.uiState.value.isLoading)
        assertTrue(model.uiState.value.isLoadingStorage)
        assertEquals(listOf(trip(20)), model.uiState.value.trips)
        bytes.complete(9_000L)
        runCurrent()
        assertEquals(9_000L, model.uiState.value.storageBytes)
        assertFalse(model.uiState.value.isLoadingStorage)
    }

    @Test fun pageNavigationDoesNotRefreshGlobalQueueCountsOrDatabaseSize() = runTest(dispatcher) {
        var queueReads = 0
        repository.enqueue(TripHistoryPage(listOf(trip(20)), hasOlder = true))
        val model = model { queueReads++; SyncQueueSummary(pending = 7) }
        runCurrent()
        repository.enqueue(TripHistoryPage(listOf(trip(10)), hasNewer = true))
        model.olderTrips()
        runCurrent()
        assertEquals(1, queueReads)
        assertEquals(1, repository.storageReads)
        assertEquals(7L, model.uiState.value.syncStatus!!.summary!!.pending)
    }

    @Test fun oldTripDetailsAndExportUseTheirIdentityAfterHistoryFilterChanges() = runTest(dispatcher) {
        val old = trip(10)
        repository.enqueue(TripHistoryPage(listOf(old), hasNewer = true))
        val model = model()
        runCurrent()
        model.selectTrip(old.id)
        val detail = model.uiState.first { it.detail != null }.detail
        repository.enqueue(TripHistoryPage(listOf(trip(30, VEHICLE_B))))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        assertSame(detail, model.uiState.value.detail)
        val output = ByteArrayOutputStream()
        model.exportTrip(old.id, TripExportFormat.JSON) { output }
        model.uiState.first { !it.isExporting }
        assertTrue(output.toString(Charsets.UTF_8.name()).contains(old.id))
        assertFalse(output.toString(Charsets.UTF_8.name()).contains(VEHICLE_B))
        assertEquals(VEHICLE_B, model.uiState.value.historyVehicleId)
    }

    @Test fun repositoryContractViolationCannotExpandOrMislabelTheVisiblePage() = runTest(dispatcher) {
        val initial = listOf(trip(20))
        repository.enqueue(TripHistoryPage(initial))
        val model = model()
        runCurrent()
        repository.enqueue(TripHistoryPage((1..51).map { trip(it) }))
        model.refresh()
        runCurrent()
        assertEquals(initial, model.uiState.value.trips)
        assertNotNull(model.uiState.value.historyError)
        repository.enqueue(TripHistoryPage(initial))
        model.selectHistoryVehicle(VEHICLE_B)
        runCurrent()
        assertTrue(model.uiState.value.trips.isEmpty())
        assertEquals(VEHICLE_B, model.uiState.value.historyVehicleId)
        assertNotNull(model.uiState.value.historyError)
    }

    private fun model(queue: (suspend () -> SyncQueueSummary)? = null): TripsViewModel =
        ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TripsViewModel(repository, dispatcher, queue) as T
        })[TripsViewModel::class.java]

    private class HistoryRepository : TripRepository {
        val requests = mutableListOf<TripHistoryRequest>()
        val sessions = mutableMapOf<String, TripSession>()
        private val loads = ArrayDeque<suspend () -> TripHistoryPage>()
        private val gates = mutableListOf<Gate>()
        var recordingMutations = 0
        var storageReads = 0
        var failStorage = false
        var readStorage: suspend () -> Long = { 4_096L }

        fun enqueue(page: TripHistoryPage) {
            page.trips.forEach { sessions[it.id] = it }
            loads.add { page }
        }
        fun fail() { loads.add { throw IOException("Synthetic history failure") } }
        fun gate() = Gate().also { gate -> gates += gate; loads.add { gate.await() } }
        fun releaseGates() { gates.forEach { it.success(TripHistoryPage(emptyList())) } }
        override suspend fun historyPage(request: TripHistoryRequest): TripHistoryPage {
            requests += request
            return loads.removeFirst().invoke()
        }
        override suspend fun list(limit: Int): List<TripSession> = error("History must use bounded cursor pages")
        override suspend fun recoverInterrupted() { recordingMutations++ }
        override suspend fun create(session: TripSession) { recordingMutations++ }
        override suspend fun append(tripId: String, samples: List<TripSample>) { recordingMutations++ }
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) { recordingMutations++ }
        override suspend fun find(tripId: String) = sessions[tripId]
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = emptyList<TripSample>()
        override suspend fun delete(tripId: String) { sessions.remove(tripId) }
        override suspend fun storageBytes(): Long {
            storageReads++
            if (failStorage) throw IOException("Synthetic size failure")
            return readStorage()
        }
    }

    private class Gate {
        private val result = CompletableDeferred<Result<TripHistoryPage>>()
        suspend fun await(): TripHistoryPage = withContext(NonCancellable) { result.await().getOrThrow() }
        fun success(page: TripHistoryPage) { result.complete(Result.success(page)) }
        fun failure() { result.complete(Result.failure(IOException("Synthetic obsolete query failure"))) }
    }

    private companion object {
        const val VEHICLE_A = "00000000-0000-4000-8000-000000000001"
        const val VEHICLE_B = "00000000-0000-4000-8000-000000000002"
        fun trip(number: Int, vehicle: String = VEHICLE_A) = TripSession(
            id = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}",
            vehicleId = vehicle,
            vehicleAlias = "Recorded fixture name",
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 6_000,
            durationMillis = 5_000,
            status = TripStatus.COMPLETED,
            endReason = "User stopped recording",
        )
    }
}
