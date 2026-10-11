package com.gonibix.rx8.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.sync.SyncQueueSummary
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val isLoading: Boolean = true,
    val selectedTripId: String? = null,
    val detail: TripAnalysis? = null,
    val isLoadingDetail: Boolean = false,
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
    private var detailJob: Job? = null
    private var queueJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshQueueSummary()
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true)
            try {
                val (trips, bytes) = withContext(ioDispatcher) {
                    repository.list(100) to repository.storageBytes()
                }
                mutableState.value = mutableState.value.copy(trips = trips, storageBytes = bytes, isLoading = false, error = mutableState.value.error.takeUnless { it == LOAD_ERROR })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = LOAD_ERROR)
            }
        }
    }

    private fun refreshQueueSummary() {
        val loader = queueSummaryLoader ?: return
        queueJob?.cancel()
        mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus())
        queueJob = viewModelScope.launch {
            try {
                val summary = withContext(ioDispatcher) { loader() }
                mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus(summary = summary, isLoading = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(syncStatus = TripSyncStatus(isLoading = false, error = "Upload status could not be read. Refresh Trips to try again."))
            }
        }
    }

    fun selectTrip(id: String) {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedTripId = id, detail = null, isLoadingDetail = true, error = null, message = null)
        detailJob = viewModelScope.launch {
            try {
                val analysis = withContext(ioDispatcher) {
                    val session = repository.find(id) ?: throw TripUiException("This trip is no longer available. Refresh the history.")
                    requireFinished(session)
                    analyzeTrip(repository, session)
                }
                if (mutableState.value.selectedTripId == id) {
                    mutableState.value = mutableState.value.copy(detail = analysis, isLoadingDetail = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (mutableState.value.selectedTripId == id) {
                    mutableState.value = mutableState.value.copy(isLoadingDetail = false, error = failure.friendly("This trip summary could not be loaded. Return to history and try again."))
                }
            }
        }
    }

    fun closeDetails() {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedTripId = null, detail = null, isLoadingDetail = false, error = null, message = null)
    }

    fun deleteTrip(id: String) {
        if (mutableState.value.isDeleting || mutableState.value.isExporting) return
        mutableState.value = mutableState.value.copy(isDeleting = true, error = null, message = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val session = repository.find(id) ?: throw TripUiException("This trip is no longer available. Refresh the history.")
                    requireFinished(session)
                    repository.delete(id)
                }
                if (mutableState.value.selectedTripId == id) closeDetails()
                mutableState.value = mutableState.value.copy(isDeleting = false, message = "Trip and its saved samples deleted.")
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
        const val LOAD_ERROR = "Saved trips could not be loaded. Try Refresh. Your stored data has not been replaced."
    }
}
