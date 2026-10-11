package com.gonibix.rx8.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.sync.SyncQueueSummary
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
class TripsSeriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val repository = SeriesRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { repository.releaseGates(); models.clear(); Dispatchers.resetMain() }

    @Test fun defaultUsesDistanceSourceAndSwitchingKeepsSummaryHistoryAndQueueAvailable() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        val initial = model.awaitSeries(speed)
        val summary = initial.detail!!
        assertEquals("7E8", summary.speedEcuId)
        assertEquals(0.0, initial.series!!.minValue!!, 0.0)
        assertEquals(20.0, initial.series.maxValue!!, 0.0)
        assertEquals(setOf(speed, alternateSpeed, rpm), summary.seriesOptions.toSet())
        val gate = repository.gateNextPage()
        model.selectSeries(alternateSpeed)
        gate.entered.await()
        assertSame(summary, model.uiState.value.detail)
        assertEquals(alternateSpeed, model.uiState.value.selectedSeries)
        assertTrue(model.uiState.value.isLoadingSeries)
        assertNull(model.uiState.value.series)
        assertEquals(listOf(trip), model.uiState.value.trips)
        assertEquals(SyncQueueSummary(pending = 7), model.uiState.value.syncStatus!!.summary)
        gate.release.complete(Unit)
        val selected = model.awaitSeries(alternateSpeed)
        assertSame(summary, selected.detail)
        assertEquals(80.0, selected.series!!.minValue!!, 0.0)
        assertEquals(1L, selected.series.sampleCount)
        assertEquals(0, repository.mutations.get())
    }

    @Test fun tripWithoutSpeedUsesStableFallbackAndEmptyTripHasNoChartRequest() = runTest(dispatcher) {
        repository.records[trip.id] = listOf(sample(0, ObdMetric.INTAKE_TEMPERATURE, "7E9", -5.0))
        val model = model()
        model.selectTrip(trip.id)
        val intake = TripSeriesKey(ObdMetric.INTAKE_TEMPERATURE, "7E9")
        val selected = model.awaitSeries(intake)
        assertNull(selected.detail!!.speedEcuId)
        assertEquals(-5.0, selected.series!!.minValue!!, 0.0)
        repository.records[trip.id] = emptyList()
        model.selectTrip(trip.id)
        val empty = model.uiState.first { it.detail != null && !it.isLoadingDetail }
        assertTrue(empty.detail!!.seriesOptions.isEmpty())
        assertNull(empty.selectedSeries)
        assertNull(empty.series)
        assertFalse(empty.isLoadingSeries)
        assertNull(empty.seriesError)
    }

    @Test fun rapidAToBToASelectionRejectsLateResultsAndFailureFromTheFirstA() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        model.awaitSeries(speed)
        val firstA = repository.gateNextPage()
        model.selectSeries(rpm)
        firstA.entered.await()
        val firstJob = model.activeLoad()
        val middleB = repository.gateNextPage()
        model.selectSeries(alternateSpeed)
        middleB.entered.await()
        val middleJob = model.activeLoad()
        val finalA = repository.gateNextPage()
        model.selectSeries(rpm)
        finalA.entered.await()
        finalA.release.complete(Unit)
        val expected = model.awaitSeries(rpm)
        assertEquals(2_000.0, expected.series!!.maxValue!!, 0.0)
        // These repositories deliberately finish after cancellation, including a same-key failure.
        firstA.release.completeExceptionally(IOException("Obsolete private storage failure"))
        middleB.release.complete(Unit)
        firstJob.join()
        middleJob.join()
        assertSame(expected.series, model.uiState.value.series)
        assertEquals(rpm, model.uiState.value.selectedSeries)
        assertNull(model.uiState.value.seriesError)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isLoadingSeries)
    }

    @Test fun closingDetailsClearsChartAndRejectsALateReadFailure() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        model.awaitSeries(speed)
        val gate = repository.gateNextPage()
        model.selectSeries(rpm)
        gate.entered.await()
        val pending = model.activeLoad()
        model.closeDetails()
        assertChartCleared(model.uiState.value)
        gate.release.completeExceptionally(IOException("Obsolete closed chart"))
        pending.join()
        assertChartCleared(model.uiState.value)
        assertNull(model.uiState.value.error)
        assertEquals(listOf(trip), model.uiState.value.trips)
    }

    @Test fun changingTripsDoesNotPublishThePreviousTripsChart() = runTest(dispatcher) {
        val otherTrip = trip.copy(id = "other-trip", vehicleAlias = "Other recorded trip")
        repository.sessions[otherTrip.id] = otherTrip
        repository.records[otherTrip.id] = listOf(sample(0, ObdMetric.SPEED, "7E8", 33.0, otherTrip.id))
        val model = model()
        model.selectTrip(trip.id)
        model.awaitSeries(speed)
        val gate = repository.gateNextPage()
        model.selectSeries(rpm)
        gate.entered.await()
        val pending = model.activeLoad()
        model.selectTrip(otherTrip.id)
        assertNull(model.uiState.value.detail)
        assertNull(model.uiState.value.series)
        assertNull(model.uiState.value.selectedSeries)
        val other = model.awaitSeries(speed)
        assertEquals(otherTrip.id, other.detail!!.session.id)
        assertEquals(33.0, other.series!!.maxValue!!, 0.0)
        gate.release.completeExceptionally(IOException("Previous trip storage failure"))
        pending.join()
        assertSame(other.detail, model.uiState.value.detail)
        assertSame(other.series, model.uiState.value.series)
        assertNull(model.uiState.value.seriesError)
    }

    @Test fun reopeningTheSameTripRejectsTheOldDetailFailureAndResetsChartChoice() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        model.awaitSeries(speed)
        model.selectSeries(rpm)
        model.awaitSeries(rpm)
        val gate = repository.gateNextFind()
        model.selectTrip(trip.id)
        gate.entered.await()
        val obsolete = model.activeLoad()
        model.closeDetails()
        model.selectTrip(trip.id)
        val reopened = model.awaitSeries(speed)
        gate.release.completeExceptionally(IOException("Old same-trip summary failure"))
        obsolete.join()
        assertSame(reopened.detail, model.uiState.value.detail)
        assertSame(reopened.series, model.uiState.value.series)
        assertNull(model.uiState.value.error)
        assertNull(model.uiState.value.seriesError)
        assertFalse(model.uiState.value.isLoadingDetail)
    }

    @Test fun successfulDeletionClearsAndInvalidatesAnInFlightChart() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        model.awaitSeries(speed)
        val gate = repository.gateNextPage()
        model.selectSeries(rpm)
        gate.entered.await()
        val pending = model.activeLoad()
        model.deleteTrip(trip.id)
        model.uiState.first { !it.isDeleting && it.message?.contains("deleted") == true }
        assertChartCleared(model.uiState.value)
        gate.release.completeExceptionally(IOException("Deleted sample page"))
        pending.join()
        model.uiState.first { !it.isLoading }
        assertTrue(model.uiState.value.trips.isEmpty())
        assertChartCleared(model.uiState.value)
        assertNull(model.uiState.value.error)
        assertEquals(1, repository.mutations.get())
    }

    @Test fun chartFailureIsLocalAndRetryKeepsTheSelectedSourceWithoutRepeatingSummary() = runTest(dispatcher) {
        val model = model()
        model.selectTrip(trip.id)
        val summary = model.awaitSeries(speed).detail
        val gate = repository.gateNextPage()
        model.selectSeries(rpm)
        gate.entered.await()
        gate.release.completeExceptionally(IOException("Private database location"))
        val failed = model.uiState.first { !it.isLoadingSeries && it.seriesError != null }
        assertSame(summary, failed.detail)
        assertEquals(rpm, failed.selectedSeries)
        assertNull(failed.series)
        assertNull(failed.error)
        assertNull(failed.message)
        assertFalse(failed.seriesError!!.contains("Private"))
        assertEquals(listOf(trip), failed.trips)
        assertNotNull(failed.syncStatus!!.summary)
        val findCalls = repository.findReads.get()
        val retry = repository.gateNextPage()
        model.retrySeries()
        retry.entered.await()
        val readCalls = repository.pageReads.get()
        model.retrySeries()
        runCurrent()
        assertEquals(readCalls, repository.pageReads.get())
        assertTrue(model.uiState.value.isLoadingSeries)
        assertNull(model.uiState.value.seriesError)
        retry.release.complete(Unit)
        val recovered = model.awaitSeries(rpm)
        assertSame(summary, recovered.detail)
        assertEquals(findCalls, repository.findReads.get())
        assertNull(recovered.seriesError)
    }

    @Test fun absentAndActiveTripSelectionsDoNotReadChartsOrReplaceAnAvailableSelection() = runTest(dispatcher) {
        val model = model()
        model.selectSeries(rpm)
        model.retrySeries()
        runCurrent()
        assertEquals(0, repository.pageReads.get())
        model.selectTrip(trip.id)
        val valid = model.awaitSeries(speed)
        val pages = repository.pageReads.get()
        model.selectSeries(TripSeriesKey(ObdMetric.SPEED, "7EF"))
        model.retrySeries()
        runCurrent()
        assertEquals(pages, repository.pageReads.get())
        assertSame(valid.series, model.uiState.value.series)
        assertEquals(speed, model.uiState.value.selectedSeries)
        repository.sessions[trip.id] = trip.copy(status = TripStatus.RECORDING)
        model.selectTrip(trip.id)
        model.uiState.first { !it.isLoadingDetail }
        model.selectSeries(rpm)
        runCurrent()
        assertEquals(pages, repository.pageReads.get())
        assertNull(model.uiState.value.selectedSeries)
        assertNull(model.uiState.value.series)
        assertFalse(model.uiState.value.isLoadingSeries)
        assertTrue(model.uiState.value.error!!.contains("Stop recording"))
    }

    @Test fun aNewActivityObserverKeepsTheSameSelectedChartWithoutAnotherRead() = runTest(dispatcher) {
        val original = model()
        original.selectTrip(trip.id)
        original.awaitSeries(speed)
        original.selectSeries(alternateSpeed)
        val selected = original.awaitSeries(alternateSpeed)
        val reads = repository.pageReads.get()
        val observer = model()
        runCurrent()
        assertSame(original, observer)
        assertSame(selected.series, observer.uiState.value.series)
        assertEquals(alternateSpeed, observer.uiState.value.selectedSeries)
        assertEquals(reads, repository.pageReads.get())
    }

    private suspend fun TripsViewModel.awaitSeries(key: TripSeriesKey): TripsUiState =
        uiState.first { !it.isLoadingDetail && !it.isLoadingSeries && it.series?.key == key }

    /** Join the actual cancelled VM job so assertions cannot race a late exception callback. */
    private fun TripsViewModel.activeLoad(): Job = viewModelScope.coroutineContext[Job]!!.children.single { it.isActive }

    private fun assertChartCleared(state: TripsUiState) {
        assertNull(state.selectedTripId)
        assertNull(state.detail)
        assertNull(state.selectedSeries)
        assertNull(state.series)
        assertNull(state.seriesError)
        assertFalse(state.isLoadingDetail)
        assertFalse(state.isLoadingSeries)
    }

    private fun model(): TripsViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
            TripsViewModel(repository, dispatcher) { SyncQueueSummary(pending = 7) },
        ))
    })[TripsViewModel::class.java]

    private class ReadGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() {
            entered.complete(Unit)
            // Simulate an external storage callback that completes even after caller cancellation.
            withContext(NonCancellable) { release.await() }
        }
    }

    private class SeriesRepository : TripRepository {
        val sessions = ConcurrentHashMap<String, TripSession>().apply { put(trip.id, trip) }
        val records = ConcurrentHashMap<String, List<TripSample>>().apply { put(trip.id, fixtureSamples) }
        val pageReads = AtomicInteger()
        val findReads = AtomicInteger()
        val mutations = AtomicInteger()
        private val pageGates = ConcurrentLinkedQueue<ReadGate>()
        private val findGates = ConcurrentLinkedQueue<ReadGate>()
        private val allGates = ConcurrentLinkedQueue<ReadGate>()
        fun gateNextPage() = ReadGate().also { pageGates.add(it); allGates.add(it) }
        fun gateNextFind() = ReadGate().also { findGates.add(it); allGates.add(it) }
        fun releaseGates() { allGates.forEach { it.release.complete(Unit) } }
        override suspend fun recoverInterrupted() = error("History must not recover recordings")
        override suspend fun create(session: TripSession) = error("History must not create recordings")
        override suspend fun append(tripId: String, samples: List<TripSample>) = error("History must not append samples")
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) = error("History must not finish recordings")
        override suspend fun list(limit: Int) = sessions.values.sortedBy { it.id }.take(limit)
        override suspend fun find(tripId: String): TripSession? {
            findReads.incrementAndGet()
            findGates.poll()?.await()
            return sessions[tripId]
        }
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int): List<TripSample> {
            pageReads.incrementAndGet()
            if (afterSequence == -1L) pageGates.poll()?.await()
            return records[tripId].orEmpty().filter { it.sequence > afterSequence }.take(limit)
        }
        override suspend fun delete(tripId: String) {
            require(sessions[tripId]?.status != TripStatus.RECORDING)
            sessions.remove(tripId)
            records.remove(tripId)
            mutations.incrementAndGet()
        }
        override suspend fun storageBytes() = 4_096L
    }

    private companion object {
        val trip = TripSession("series-trip", "fixture-vehicle", "Fixture RX-8", 1_000, 6_000, 5_000, TripStatus.COMPLETED)
        val speed = TripSeriesKey(ObdMetric.SPEED, "7E8")
        val alternateSpeed = TripSeriesKey(ObdMetric.SPEED, "7E9")
        val rpm = TripSeriesKey(ObdMetric.ENGINE_RPM, "7E8")
        fun sample(sequence: Long, metric: ObdMetric, ecu: String, value: Double, tripId: String = trip.id) = TripSample(
            "sample-$tripId-$sequence", tripId, sequence, 1_000 + sequence * 1_000, sequence * 1_000,
            metric, value, metric.unit, ecu, ObdReadingStatus.VALID,
        )
        val fixtureSamples = listOf(
            sample(0, ObdMetric.SPEED, "7E8", 0.0),
            sample(1, ObdMetric.ENGINE_RPM, "7E8", 2_000.0),
            sample(2, ObdMetric.SPEED, "7E8", 20.0),
            sample(3, ObdMetric.SPEED, "7E9", 80.0),
        )
    }
}
