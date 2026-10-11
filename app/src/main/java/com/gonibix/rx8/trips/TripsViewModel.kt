package com.gonibix.rx8.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.sync.SyncQueueSummary
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TripExportFormat(val extension: String) { JSON("json"), CSV("csv") }

/** A local count snapshot, never a delivery attempt or a substitute for a server ACK. */
data class TripSyncStatus(
    val summary: SyncQueueSummary? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class TripsUiState(
    val trips: List<TripSession> = emptyList(),
    val storageBytes: Long? = null,
    val isLoadingStorage: Boolean = true,
    val storageError: String? = null,
    val isLoading: Boolean = true,
    val historyVehicleId: String? = null,
    val hasNewerTrips: Boolean = false,
    val hasOlderTrips: Boolean = false,
    val historyError: String? = null,
    val historyRevision: Long = 0,
    val selectedTripId: String? = null,
    val detail: TripAnalysis? = null,
    val isLoadingDetail: Boolean = false,
    val selectedSeries: TripSeriesKey? = null,
    val series: TripSeriesAnalysis? = null,
    val isLoadingSeries: Boolean = false,
    val seriesError: String? = null,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val syncStatus: TripSyncStatus? = null,
)

/** History is independent of recording ownership; this observer never recovers or stops a session. */
class TripsViewModel(
    private val repository: TripRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val queueSummaryLoader: (suspend () -> SyncQueueSummary)? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TripsUiState(syncStatus = queueSummaryLoader?.let { TripSyncStatus() }))
    val uiState = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var historyGeneration = 0L
    private var historyAnchor: TripHistoryCursor? = null
    private var historyAtNewest = true
    private var lastHistoryRequest = TripHistoryRequest()
    private var lastHistoryRepositions = false
    private var storageJob: Job? = null
    private var storageGeneration = 0L
    private var detailJob: Job? = null
    private var seriesJob: Job? = null
    private var detailGeneration = 0L
    private var seriesGeneration = 0L
    private var queueJob: Job? = null
    private var queueGeneration = 0L

    init { refresh() }

    /** Refresh the visible browsing context, never reset an older page on lifecycle entry. */
    fun refresh() {
        refreshQueueSummary()
        refreshStorage()
        if (mutableState.value.isDeleting) return
        if (mutableState.value.isLoading) {
            loadHistory(lastHistoryRequest, lastHistoryRepositions)
        } else {
            loadHistory(currentHistoryRequest())
        }
    }

    fun selectHistoryVehicle(id: String?) {
        val state = mutableState.value
        if (state.isDeleting || state.historyVehicleId == id) return
        historyAnchor = null
        historyAtNewest = true
        mutableState.value = state.copy(
            historyVehicleId = id, trips = emptyList(), hasNewerTrips = false, hasOlderTrips = false,
            historyError = null, historyRevision = state.historyRevision + 1,
        )
        loadHistory(TripHistoryRequest(vehicleId = id, limit = HISTORY_PAGE_SIZE))
    }

    fun olderTrips() {
        val state = mutableState.value
        if (state.isLoading || state.isDeleting || !state.hasOlderTrips) return
        val last = state.trips.lastOrNull() ?: return
        loadHistory(TripHistoryRequest(state.historyVehicleId, last.historyCursor(), TripHistoryDirection.OLDER, HISTORY_PAGE_SIZE), reposition = true)
    }

    fun newerTrips() {
        val state = mutableState.value
        if (state.isLoading || state.isDeleting || !state.hasNewerTrips) return
        val first = state.trips.firstOrNull() ?: return
        loadHistory(TripHistoryRequest(state.historyVehicleId, first.historyCursor(), TripHistoryDirection.NEWER, HISTORY_PAGE_SIZE), reposition = true)
    }

    fun newestTrips() {
        val state = mutableState.value
        if (state.isLoading || state.isDeleting) return
        loadHistory(TripHistoryRequest(vehicleId = state.historyVehicleId, limit = HISTORY_PAGE_SIZE), reposition = true)
    }

    fun retryHistory() {
        val state = mutableState.value
        if (state.isLoading || state.isDeleting || state.historyError == null) return
        loadHistory(lastHistoryRequest, lastHistoryRepositions)
    }

    private fun currentHistoryRequest(): TripHistoryRequest {
        val vehicleId = mutableState.value.historyVehicleId
        val anchor = historyAnchor
        return if (historyAtNewest || anchor == null) {
            TripHistoryRequest(vehicleId = vehicleId, limit = HISTORY_PAGE_SIZE)
        } else {
            TripHistoryRequest(vehicleId, anchor, TripHistoryDirection.AT_OR_OLDER, HISTORY_PAGE_SIZE)
        }
    }

    private fun loadHistory(request: TripHistoryRequest, reposition: Boolean = false) {
        val generation = ++historyGeneration
        historyJob?.cancel()
        lastHistoryRequest = request
        lastHistoryRepositions = reposition
        mutableState.value = mutableState.value.copy(isLoading = true, historyError = null)
        historyJob = viewModelScope.launch {
            try {
                val (page, returnedToNewest) = withContext(ioDispatcher) {
                    val requested = repository.historyPage(request)
                    coroutineContext.ensureActive()
                    // A deletion can empty a cursor range. Recover once, preserving its vehicle filter.
                    if (requested.trips.isEmpty() && request.direction != TripHistoryDirection.NEWEST) {
                        repository.historyPage(TripHistoryRequest(vehicleId = request.vehicleId, limit = HISTORY_PAGE_SIZE)) to true
                    } else requested to false
                }
                if (generation != historyGeneration || request.vehicleId != mutableState.value.historyVehicleId) return@launch
                check(page.trips.size <= HISTORY_PAGE_SIZE) { "History page exceeds its bound" }
                check(request.vehicleId == null || page.trips.all { it.vehicleId == request.vehicleId }) { "History page belongs to another vehicle" }
                historyAnchor = page.trips.firstOrNull()?.historyCursor()
                historyAtNewest = returnedToNewest || request.direction == TripHistoryDirection.NEWEST || !page.hasNewer
                mutableState.value = mutableState.value.copy(
                    trips = page.trips.toList(), hasNewerTrips = page.hasNewer, hasOlderTrips = page.hasOlder,
                    isLoading = false, historyError = null,
                    historyRevision = mutableState.value.historyRevision + if (reposition || returnedToNewest) 1 else 0,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == historyGeneration && request.vehicleId == mutableState.value.historyVehicleId) {
                    mutableState.value = mutableState.value.copy(isLoading = false, historyError = LOAD_ERROR)
                }
            }
        }
    }

    private fun refreshStorage() {
        val generation = ++storageGeneration
        storageJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoadingStorage = true, storageError = null)
        storageJob = viewModelScope.launch {
            try {
                val bytes = withContext(ioDispatcher) { repository.storageBytes() }
                if (generation == storageGeneration) {
                    mutableState.value = mutableState.value.copy(storageBytes = bytes, isLoadingStorage = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == storageGeneration) {
                    mutableState.value = mutableState.value.copy(
                        storageBytes = null, isLoadingStorage = false,
                        storageError = "Database size could not be read. Refresh Trips to try again.",
                    )
                }
            }
        }
    }

    private fun refreshQueueSummary() {
        val loader = queueSummaryLoader ?: return
        val generation = ++queueGeneration
        queueJob?.cancel()
        mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus())
        queueJob = viewModelScope.launch {
            try {
                val summary = withContext(ioDispatcher) { loader() }
                if (generation == queueGeneration) {
                    mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus(summary = summary, isLoading = false))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == queueGeneration) {
                    mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus(isLoading = false, error = "Upload status could not be read. Refresh Trips to try again."))
                }
            }
        }
    }

    private fun TripSession.historyCursor() = TripHistoryCursor(startedAtEpochMillis, id)

    fun selectTrip(id: String) {
        val generation = ++detailGeneration
        detailJob?.cancel()
        cancelSeries()
        mutableState.value = mutableState.value.copy(
            selectedTripId = id, detail = null, isLoadingDetail = true, error = null, message = null,
            selectedSeries = null, series = null, isLoadingSeries = false, seriesError = null,
        )
        detailJob = viewModelScope.launch {
            try {
                val analysis = withContext(ioDispatcher) {
                    val session = repository.find(id) ?: throw TripUiException("This trip is no longer available. Refresh the history.")
                    requireFinished(session)
                    analyzeTrip(repository, session)
                }
                if (detailGeneration == generation && mutableState.value.selectedTripId == id) {
                    mutableState.value = mutableState.value.copy(detail = analysis, isLoadingDetail = false)
                    analysis.defaultSeries()?.let(::selectSeries)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (detailGeneration == generation && mutableState.value.selectedTripId == id) {
                    mutableState.value = mutableState.value.copy(isLoadingDetail = false, error = failure.friendly("This trip summary could not be loaded. Return to history and try again."))
                }
            }
        }
    }

    fun selectSeries(key: TripSeriesKey) {
        val state = mutableState.value
        val analysis = state.detail ?: return
        if (state.selectedTripId != analysis.session.id || analysis.session.status == TripStatus.RECORDING ||
            key !in analysis.seriesOptions) return
        if (state.selectedSeries == key && (state.isLoadingSeries || state.series != null)) return
        loadSeries(analysis, key)
    }

    fun retrySeries() {
        val state = mutableState.value
        if (state.isLoadingSeries || state.seriesError == null) return
        state.selectedSeries?.let(::selectSeries)
    }

    private fun loadSeries(analysis: TripAnalysis, key: TripSeriesKey) {
        cancelSeries()
        val generation = seriesGeneration
        val detailVersion = detailGeneration
        val tripId = analysis.session.id
        mutableState.value = mutableState.value.copy(
            selectedSeries = key, series = null, isLoadingSeries = true, seriesError = null,
        )
        seriesJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { analyzeTripSeries(repository, analysis.session, key) }
                if (isCurrentSeries(detailVersion, generation, tripId, key)) {
                    mutableState.value = mutableState.value.copy(series = result, isLoadingSeries = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentSeries(detailVersion, generation, tripId, key)) {
                    mutableState.value = mutableState.value.copy(isLoadingSeries = false, seriesError = SERIES_ERROR)
                }
            }
        }
    }

    private fun isCurrentSeries(detailVersion: Long, generation: Long, tripId: String, key: TripSeriesKey): Boolean =
        detailGeneration == detailVersion && seriesGeneration == generation &&
            mutableState.value.selectedTripId == tripId && mutableState.value.selectedSeries == key

    private fun cancelSeries() {
        seriesGeneration++
        seriesJob?.cancel()
        seriesJob = null
    }

    fun closeDetails() {
        detailGeneration++
        detailJob?.cancel()
        cancelSeries()
        mutableState.value = mutableState.value.copy(
            selectedTripId = null, detail = null, isLoadingDetail = false, error = null, message = null,
            selectedSeries = null, series = null, isLoadingSeries = false, seriesError = null,
        )
    }

    fun deleteTrip(id: String) {
        if (mutableState.value.isDeleting || mutableState.value.isExporting) return
        historyGeneration++
        historyJob?.cancel()
        mutableState.value = mutableState.value.copy(isDeleting = true, isLoading = false, error = null, message = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val session = repository.find(id) ?: throw TripUiException("This trip is no longer available. Refresh the history.")
                    requireFinished(session)
                    repository.delete(id)
                }
                if (mutableState.value.selectedTripId == id) closeDetails()
                mutableState.value = mutableState.value.copy(
                    trips = mutableState.value.trips.filterNot { it.id == id },
                    isDeleting = false, message = "Trip and its saved samples deleted.",
                )
                refresh()
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(isDeleting = false)
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(isDeleting = false, error = failure.friendly("This trip could not be deleted. Refresh and try again."))
            }
        }
    }

    /** The caller supplies only the user-chosen document. Success includes flushing and closing it. */
    fun exportTrip(id: String, format: TripExportFormat, openOutput: () -> OutputStream) {
        if (mutableState.value.isExporting || mutableState.value.isDeleting) return
        mutableState.value = mutableState.value.copy(isExporting = true, error = null, message = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val session = repository.find(id) ?: throw TripUiException("This trip is no longer available. Refresh the history.")
                    requireFinished(session)
                    openOutput().bufferedWriter(Charsets.UTF_8).use { writer ->
                        when (format) {
                            TripExportFormat.JSON -> TripExporter.writeJson(repository, session, writer)
                            TripExportFormat.CSV -> TripExporter.writeCsv(repository, session, writer)
                        }
                        writer.flush()
                    }
                }
                mutableState.value = mutableState.value.copy(isExporting = false, message = "${format.name} export saved to your chosen location.")
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(isExporting = false)
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(isExporting = false, error = failure.friendly("Export could not be completed. The chosen document may be incomplete; choose a location and try again."))
            }
        }
    }

    private fun requireFinished(session: TripSession) {
        if (session.status == TripStatus.RECORDING) throw TripUiException("Stop recording before viewing the full summary, exporting, or deleting this trip.")
    }

    private fun Exception.friendly(fallback: String) = if (this is TripUiException) requireNotNull(message) else fallback
    private class TripUiException(message: String) : IllegalStateException(message)

    private companion object {
        const val HISTORY_PAGE_SIZE = 50
        const val SERIES_ERROR = "This sensor chart could not be loaded. Retry the chart or choose another sensor."
        const val LOAD_ERROR = "Saved trips could not be loaded. Retry or refresh the history. Your stored data has not been replaced."
    }
}
