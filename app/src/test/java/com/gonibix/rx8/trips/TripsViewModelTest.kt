package com.gonibix.rx8.trips

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun historyLoadsWithoutRecoveringOrMutatingAnActiveRecording() = runTest(dispatcher) {
        repository.current = trip.copy(status = TripStatus.RECORDING)
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        assertEquals(100, repository.lastLimit)
        assertEquals(listOf(repository.current), model.uiState.value.trips)
        assertEquals(4_096L, model.uiState.value.storageBytes)
        assertEquals(0, repository.recoveries)
        assertEquals(0, repository.deletions)
    }

    @Test fun failedReloadRetainsPreviouslyLoadedHistory() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        repository.failList = true
        model.refresh()
        runCurrent()
        assertEquals(listOf(trip), model.uiState.value.trips)
        assertTrue(model.uiState.value.error!!.contains("could not be loaded"))
        assertFalse(model.uiState.value.isLoading)
        repository.failList = false
        model.refresh()
        runCurrent()
        assertNull(model.uiState.value.error)
    }

    @Test fun activeTripCannotBeDeletedOrExportedEvenThroughDirectCallbacks() = runTest(dispatcher) {
        repository.current = trip.copy(status = TripStatus.RECORDING)
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        model.deleteTrip(trip.id)
        runCurrent()
        assertEquals(0, repository.deletions)
        var opened = false
        model.exportTrip(trip.id, TripExportFormat.JSON) { opened = true; ByteArrayOutputStream() }
        runCurrent()
        assertFalse(opened)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("Stop recording"))
    }

    @Test fun activeTripSummaryDoesNotReadAnUnboundedGrowingHistory() = runTest(dispatcher) {
        repository.current = trip.copy(status = TripStatus.RECORDING)
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        model.selectTrip(trip.id)
        runCurrent()
        assertFalse(model.uiState.value.isLoadingDetail)
        assertNull(model.uiState.value.detail)
        assertEquals(0, repository.sampleReads)
        assertTrue(model.uiState.value.error!!.contains("Stop recording"))
    }

    @Test fun exportSuccessIsPublishedOnlyAfterTheOutputIsClosed() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                assertTrue(model.uiState.value.isExporting)
                assertNull(model.uiState.value.message)
                closed = true
                super.close()
            }
        }
        model.exportTrip(trip.id, TripExportFormat.JSON) { output }
        model.uiState.first { !it.isExporting }
        assertTrue(closed)
        assertTrue(model.uiState.value.message!!.contains("JSON export saved"))
        assertTrue(output.toString(Charsets.UTF_8.name()).contains(trip.id))
        assertNull(model.uiState.value.error)
    }

    @Test fun destinationCloseFailureNeverReportsAnExportAsSaved() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("Synthetic document-provider close failure") }
        }
        model.exportTrip(trip.id, TripExportFormat.CSV) { output }
        model.uiState.first { !it.isExporting }
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("may be incomplete"))
    }

    @Test fun deniedDestinationLeavesHistoryAvailableAndReportsFailure() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        model.exportTrip(trip.id, TripExportFormat.JSON) { throw SecurityException("Synthetic denied destination") }
        model.uiState.first { !it.isExporting }
        assertEquals(listOf(trip), model.uiState.value.trips)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("Export could not be completed"))
    }

    @Test fun successfulDeletionUpdatesHistoryOnlyAfterRepositorySuccess() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        model.deleteTrip(trip.id)
        assertEquals(listOf(trip), model.uiState.value.trips)
        runCurrent()
        assertEquals(1, repository.deletions)
        assertTrue(model.uiState.value.trips.isEmpty())
        assertTrue(model.uiState.value.message!!.contains("deleted"))
    }

    @Test fun failedDeletionRetainsHistoryAndDoesNotClaimSuccess() = runTest(dispatcher) {
        val model = TripsViewModel(repository, dispatcher)
        runCurrent()
        repository.failDelete = true
        model.deleteTrip(trip.id)
        runCurrent()
        assertEquals(listOf(trip), model.uiState.value.trips)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("could not be deleted"))
    }

    private class FakeRepository : TripRepository {
        var current: TripSession? = trip
        var recoveries = 0
        var deletions = 0
        var sampleReads = 0
        var lastLimit = 0
        var failList = false
        var failDelete = false
        override suspend fun recoverInterrupted() { recoveries++ }
        override suspend fun create(session: TripSession) = error("History must not create a recording")
        override suspend fun append(tripId: String, samples: List<TripSample>) = error("History must not append samples")
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) = error("History must not finish a recording")
        override suspend fun list(limit: Int): List<TripSession> {
            lastLimit = limit
            if (failList) throw IOException("Synthetic history failure")
            return listOfNotNull(current)
        }
        override suspend fun find(tripId: String) = current?.takeIf { it.id == tripId }
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int): List<TripSample> { sampleReads++; return emptyList() }
        override suspend fun delete(tripId: String) {
            if (failDelete) throw IOException("Synthetic deletion failure")
            deletions++
            current = null
        }
        override suspend fun storageBytes() = 4_096L
    }

    private companion object {
        val trip = TripSession("test-trip", "test-vehicle", "Fixture RX-8", 1_000, 6_000, 5_000, TripStatus.COMPLETED, "User stopped recording")
    }
}
