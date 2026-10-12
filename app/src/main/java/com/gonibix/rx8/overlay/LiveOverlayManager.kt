package com.gonibix.rx8.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.annotation.MainThread
import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.obd.ObdSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owned by the existing connected-device service. Never starts acquisition or a second service. */
@MainThread
class LiveOverlayManager(context: Context, private val scope: CoroutineScope) {
    private val application = context.applicationContext
    private val host = AndroidOverlayHost(application) { setEnabled(false) }
    private val controller = OverlayController(host, SystemClock::elapsedRealtime)
    val state = controller.state
    private var ticker: Job? = null
    private var closed = false
    private val visibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshPermission() }
    }
    init {
        ContextCompat.registerReceiver(application, visibilityReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshScreen()
    }
    private fun refreshScreen() {
        val unlocked = application.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
        val interactive = application.getSystemService(PowerManager::class.java)?.isInteractive == true
        controller.setScreenAvailable(unlocked && interactive)
    }

    fun update(snapshot: ObdSessionSnapshot, connected: Boolean, cards: List<DashboardCardConfig>) {
        if (closed) return
        refreshScreen()
        controller.update(snapshot, connected, cards)
        updateTicker()
    }
    fun setEnabled(enabled: Boolean): Boolean {
        if (closed) return false
        refreshScreen()
        return controller.setEnabled(enabled).also { updateTicker() }
    }
    fun setAppVisible(visible: Boolean) { controller.setAppVisible(visible) }
    fun refreshPermission() { if (closed) return; refreshScreen(); controller.refresh(); updateTicker() }
    fun close() {
        if (closed) return
        closed = true
        ticker?.cancel(); ticker = null
        controller.close()
        runCatching { application.unregisterReceiver(visibilityReceiver) }
    }
    private fun updateTicker() {
        if (!state.value.enabled) { ticker?.cancel(); ticker = null }
        else if (ticker?.isActive != true) ticker = scope.launch(Dispatchers.Main.immediate) {
            while (state.value.enabled) { delay(1_000); refreshScreen(); controller.refresh() }
        }
    }
}
