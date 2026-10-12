package com.gonibix.rx8.sync

/** Bounds one worker run without treating successful requests as transport retry attempts. */
internal class UploadRunBudget(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
    maxDurationMillis: Long = DEFAULT_DURATION_MILLIS,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val durationNanos: Long
    private val startedAtNanos = nowNanos()
    private var startedRequests = 0

    init {
        require(maxRequests > 0)
        require(maxDurationMillis in 1..Long.MAX_VALUE / 1_000_000L)
        durationNanos = maxDurationMillis * 1_000_000L
    }

    /** Check between requests; an already-started request retains its own ten-second timeout. */
    fun tryStartRequest(): Boolean {
        val elapsed = nowNanos() - startedAtNanos
        if (startedRequests >= maxRequests || elapsed < 0L || elapsed >= durationNanos) return false
        startedRequests++
        return true
    }

    companion object {
        const val DEFAULT_MAX_REQUESTS = 250
        const val DEFAULT_DURATION_MILLIS = 240_000L
    }
}
