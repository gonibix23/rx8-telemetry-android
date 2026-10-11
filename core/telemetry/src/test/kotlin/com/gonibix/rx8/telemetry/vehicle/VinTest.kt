package com.gonibix.rx8.telemetry.vehicle

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinTest {
    // Deliberately synthetic; no real vehicle identity is used in tests.
    private val syntheticVin = "ABCDEFGHJKLMNPR12"

    @Test
    fun `an allowed 17 character VIN is preserved`() {
        assertEquals(syntheticVin, Vin.normalize(syntheticVin))
        assertEquals("12345678901234567", Vin.normalize("12345678901234567"))
    }

    @Test
    fun `manual lowercase and surrounding whitespace normalize without changing the identity`() {
        assertEquals(syntheticVin, Vin.normalize(" \t\r\nabcdefghjklmnpr12\n "))
    }

    @Test
    fun `normalization is independent of the default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(syntheticVin, Vin.normalize("abcdefghjklmnpr12"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a regional ninth position check digit is not imposed`() {
        // The ninth character is J, deliberately outside the North American check-digit alphabet.
        assertEquals(syntheticVin, Vin.normalize(syntheticVin))
    }

    @Test
    fun `empty shortened and oversized inputs are invalid`() {
        for (raw in listOf("", " \t", syntheticVin.dropLast(1), syntheticVin + "3", "A".repeat(1_000))) {
            assertNull(Vin.normalize(raw))
        }
    }

    @Test
    fun `ambiguous I O Q letters are rejected in both cases`() {
        for (letter in "IOQioq") {
            assertNull(Vin.normalize(syntheticVin.replaceRange(5, 6, letter.toString())))
        }
    }

    @Test
    fun `internal whitespace punctuation and controls are not silently deleted`() {
        for (character in listOf(" ", "-", ".", "\t", "\n", "\u0000")) {
            assertNull(Vin.normalize(syntheticVin.replaceRange(5, 6, character)))
        }
    }

    @Test
    fun `non ASCII letters digits and uppercase expansions are rejected`() {
        for (character in listOf("ſ", "ß", "Ａ", "١", "А", "é")) {
            assertNull(Vin.normalize(syntheticVin.replaceRange(5, 6, character)))
        }
    }
}
