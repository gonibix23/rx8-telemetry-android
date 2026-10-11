package com.gonibix.rx8.connection

import com.gonibix.rx8.obd.ObdPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface SessionWakeLock {
    fun acquire(timeoutMillis: Long)
    fun release()
}

/** Bounded renewable CPU ownership, enabled only while OBD acquisition is active. */
internal class AcquisitionWakeLock(
    private val scope: CoroutineScope,
    private val wakeLock: SessionWakeLock,
    private val onFailure: () -> Unit,
) {
    private var renewal: Job? = null
    private var active = false
    private var generation = 0L
    private var closed = false

    fun update(state: BluetoothConnectionUiState) {
        if (closed) return
        val required = state.phase == BluetoothConnectionPhase.CONNECTED &&
            state.obd.phase in setOf(ObdPhase.INITIALIZING, ObdPhase.DISCOVERING, ObdPhase.LIVE)
        if (required == active) return
        active = required
        val expected = ++generation
        renewal?.cancel()
        renewal = null
        if (!required) {
            wakeLock.release()
            return
        }
        renewal = scope.launch {
            try {
                while (isActive && expected == generation) {
                    wakeLock.acquire(TIMEOUT_MILLIS)
                    delay(RENEWAL_MILLIS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) {
                    active = false
                    wakeLock.release()
                    onFailure()
                }
            }
        }
    }

    fun stop() {
        generation++
        active = false
        renewal?.cancel()
        renewal = null
        wakeLock.release()
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 120_000L
        const val RENEWAL_MILLIS = 60_000L
    }
}
