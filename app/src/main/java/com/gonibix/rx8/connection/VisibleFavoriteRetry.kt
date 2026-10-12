package com.gonibix.rx8.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One visible-app retry timer; transport owns its own bounded connection attempt. */
internal class VisibleFavoriteRetry(
    private val scope: CoroutineScope,
    private val onReady: (Boolean) -> Unit,
    private val retryMillis: Long = 30_000L,
) {
    private var timer: Job? = null
    private var favoriteKey: String? = null
    private var eligible = false
    private var attempted = false
    private var ready = false

    init { require(retryMillis > 0) }

    fun update(key: String?, canAttempt: Boolean) {
        if (key != favoriteKey) {
            stopTimer()
            favoriteKey = key
            attempted = false
            eligible = false
        }
        val next = key != null && canAttempt
        if (eligible == next) return
        eligible = next
        stopTimer()
        if (!next) return
        if (!attempted) publish(true) else {
            timer = scope.launch {
                delay(retryMillis)
                if (eligible) publish(true)
            }
        }
    }

    fun consume(): Boolean {
        if (!ready || !eligible) return false
        attempted = true
        eligible = false
        stopTimer()
        return true
    }

    /** Manual attempts also delay the next favorite retry after their terminal result. */
    fun manualAttempt() {
        attempted = true
        eligible = false
        stopTimer()
    }

    fun close() { eligible = false; stopTimer() }

    private fun stopTimer() { timer?.cancel(); timer = null; publish(false) }
    private fun publish(value: Boolean) {
        if (ready != value) { ready = value; onReady(value) }
    }
}
