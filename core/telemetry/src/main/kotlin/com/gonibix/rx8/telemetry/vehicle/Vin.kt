package com.gonibix.rx8.telemetry.vehicle

import java.util.Locale

/** Structural validation only; it does not prove vehicle identity or impose a regional check digit. */
object Vin {
    private val allowed = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** Canonicalizes manual input without silently deleting internal spaces or non-ASCII characters. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length != 17 || trimmed.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) {
            return null
        }
        val normalized = trimmed.uppercase(Locale.ROOT)
        return normalized.takeIf(allowed::matches)
    }
}
