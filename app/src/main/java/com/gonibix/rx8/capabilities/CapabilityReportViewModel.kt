package com.gonibix.rx8.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.obd.ObdSessionSnapshot
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CapabilityReportUiState(
    val report: CapabilityReport? = null,
    val isAwaitingDestination: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** Activity-scoped, temporary report ownership. No polling, persistence or connection dependencies. */
class CapabilityReportViewModel(
    private val appVersion: String,
    private val androidApiLevel: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CapabilityReportUiState())
    val uiState = mutableState.asStateFlow()
    private var pendingBody: String? = null
    private var cleared = false

    /** Only an explicit UI action supplies a snapshot; later acquisition updates cannot alter it. */
    fun capture(snapshot: ObdSessionSnapshot): Boolean {
        if (cleared || isBusy()) return false
        return try {
            check(CapabilityReports.canCapture(snapshot))
            val report = CapabilityReports.capture(snapshot, appVersion, androidApiLevel, clock())
            mutableState.value = CapabilityReportUiState(report = report, message = "Report captured. Review it before exporting.")
            true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = CAPTURE_ERROR, message = null)
            false
        }
    }

    /** Freeze the exact JSON before launching a document picker; this state survives Activity recreation. */
    fun beginExport(): String? {
        if (cleared || isBusy()) return null
        val report = mutableState.value.report
        if (report == null) {
            mutableState.value = mutableState.value.copy(error = "Capture a report before exporting.", message = null)
            return null
        }
        return try {
            val body = CapabilityReportJson.encode(report)
            check(body.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES)
            pendingBody = body
            mutableState.value = mutableState.value.copy(isAwaitingDestination = true, error = null, message = null)
            "RX8-capabilities-${report.reportId}.json"
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "The report could not be prepared. Capture a new report and try again.", message = null)
            null
        }
    }

    /** null means the user cancelled the picker; a provider failing to open must throw instead. */
    fun completeExport(openOutput: (() -> OutputStream)?) {
        if (cleared || mutableState.value.isExporting) return
        val body = pendingBody
        pendingBody = null
        if (body == null) {
            mutableState.value = mutableState.value.copy(isAwaitingDestination = false, error = EXPIRED_ERROR, message = null)
            return
        }
        if (openOutput == null) {
            mutableState.value = mutableState.value.copy(isAwaitingDestination = false, error = null, message = null)
            return
        }
        mutableState.value = mutableState.value.copy(isAwaitingDestination = false, isExporting = true, error = null, message = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    openOutput().use { output ->
                        currentCoroutineContext().ensureActive()
                        output.write(bytes)
                        currentCoroutineContext().ensureActive()
                        output.flush()
                        currentCoroutineContext().ensureActive()
                    }
                    currentCoroutineContext().ensureActive()
                }
                mutableState.value = mutableState.value.copy(isExporting = false, error = null, message = "JSON report saved to your chosen location.")
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(isExporting = false, error = CANCELLED_ERROR, message = null)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(isExporting = false, error = EXPORT_ERROR, message = null)
            }
        }
    }

    /** Launcher failures are different from a user choosing Cancel. */
    fun destinationUnavailable() {
        if (cleared || mutableState.value.isExporting || pendingBody == null) return
        pendingBody = null
        mutableState.value = mutableState.value.copy(isAwaitingDestination = false,
            error = "The document picker could not be opened. Try again or choose another document provider.", message = null)
    }

    override fun onCleared() {
        cleared = true
        pendingBody = null
        mutableState.value = mutableState.value.copy(isAwaitingDestination = false, isExporting = false, message = null)
        super.onCleared()
    }

    private fun isBusy() = mutableState.value.isAwaitingDestination || mutableState.value.isExporting

    private companion object {
        const val MAX_REPORT_BYTES = 512 * 1024
        const val CAPTURE_ERROR = "A report could not be captured. Wait for vehicle capability discovery and try again."
        const val EXPIRED_ERROR = "The pending report is no longer available. Capture and export a new report."
        const val EXPORT_ERROR = "Export could not be completed. The chosen document may be incomplete; choose a location and try again."
        const val CANCELLED_ERROR = "Export was cancelled. The chosen document may be incomplete."
    }
}