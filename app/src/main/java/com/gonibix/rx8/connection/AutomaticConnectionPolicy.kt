package com.gonibix.rx8.connection

/** One opening opportunity per process, shared across service/Activity recreation. */
class AutomaticConnectionPolicy {
    private var consumed = false

    @Synchronized fun isAvailable(): Boolean = !consumed

    @Synchronized fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}
