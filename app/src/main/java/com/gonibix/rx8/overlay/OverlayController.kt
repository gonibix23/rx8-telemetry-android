package com.gonibix.rx8.overlay

import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.dashboard.DashboardLayoutCodec
import com.gonibix.rx8.dashboard.DashboardReading
import com.gonibix.rx8.dashboard.modularDashboardModel
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlayUiState(
    val enabled: Boolean = false,
    val visible: Boolean = false,
    val permissionGranted: Boolean = false,
    val error: String? = null,
)

internal data class OverlayRow(val card: DashboardCardConfig, val reading: DashboardReading)
internal interface OverlayHost {
    fun permissionGranted(): Boolean
    fun show(rows: List<OverlayRow>)
    fun hide()
}

/** Pure session-bound presentation policy. No Bluetooth commands, service starts or persisted enable. */
internal class OverlayController(private val host: OverlayHost, private val nowMillis: () -> Long) {
    private val mutableState = MutableStateFlow(OverlayUiState())
    val state = mutableState.asStateFlow()
    private var connected = false
    private var appVisible = true
    private var screenAvailable = true
    private var closed = false
    private var snapshot = ObdSessionSnapshot()
    private var receivedAt = nowMillis()
    private var cards = emptyList<DashboardCardConfig>()

    fun update(snapshot: ObdSessionSnapshot, connected: Boolean, cards: List<DashboardCardConfig>) {
        if (closed) return
        // Preference/status emissions are not new clock observations. Do not rejuvenate stalled readings.
        if (snapshot.elapsedMillis != this.snapshot.elapsedMillis || snapshot.phase != this.snapshot.phase) receivedAt = nowMillis()
        this.snapshot = snapshot
        this.connected = connected
        this.cards = cards.filter { it.showInOverlay }.distinctBy { it.metric }.take(DashboardLayoutCodec.MAX_OVERLAY_METRICS)
        if (!connected) mutableState.value = mutableState.value.copy(enabled = false)
        refresh()
    }

    fun setEnabled(enabled: Boolean): Boolean {
        if (closed) return false
        val permission = host.permissionGranted()
        val error = when {
            !enabled -> null
            !permission -> "Allow display over other apps before enabling the floating panel."
            !connected -> "Connect the adapter before enabling the floating panel."
            cards.isEmpty() -> "Edit a sensor card and select Show in floating panel first."
            else -> null
        }
        mutableState.value = OverlayUiState(enabled && error == null, permissionGranted = permission, error = error)
        refresh()
        return mutableState.value.enabled
    }

    fun setScreenAvailable(available: Boolean) { if (!closed) { screenAvailable = available; refresh() } }
    fun setAppVisible(visible: Boolean) { if (!closed) { appVisible = visible; refresh() } }
    fun refresh() {
        if (closed) return
        val permission = host.permissionGranted()
        val previous = mutableState.value
        val lost = previous.enabled && !permission
        val enabled = previous.enabled && connected && permission && cards.isNotEmpty()
        val shouldShow = enabled && !appVisible && screenAvailable
        try {
            if (shouldShow) {
                val delta = (nowMillis() - receivedAt).coerceAtLeast(0L)
                val ageSnapshot = if (snapshot.phase == ObdPhase.LIVE && snapshot.elapsedMillis <= Long.MAX_VALUE - delta)
                    snapshot.copy(elapsedMillis = snapshot.elapsedMillis + delta) else snapshot
                val model = modularDashboardModel(ageSnapshot, cards)
                val byMetric = cards.associateBy { it.metric }
                val rows = if (model.ecus.isEmpty()) cards.map { card ->
                    OverlayRow(card, DashboardReading(card.metric, "Unknown", null, null, null, true))
                } else model.ecus.take(32).flatMap { ecu -> ecu.readings.map { OverlayRow(byMetric.getValue(it.metric), it) } }
                host.show(rows)
            } else host.hide()
            mutableState.value = previous.copy(enabled = enabled, visible = shouldShow, permissionGranted = permission,
                error = if (lost) "Floating panel closed because its permission was removed." else previous.error)
        } catch (_: Exception) {
            runCatching { host.hide() }
            mutableState.value = OverlayUiState(permissionGranted = permission,
                error = "The floating panel could not be shown. Check its permission and try again.")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { host.hide() }
        mutableState.value = mutableState.value.copy(enabled = false, visible = false)
    }
}
