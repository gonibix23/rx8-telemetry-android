package com.gonibix.rx8.telemetry.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {
    private val can = ObdProtocol.CAN_11_500

    @Test
    fun `echo and CR LF variations do not hide command acknowledgement`() {
        val reply = ElmResponseParser.parse("ATE0", "\r\n at e0 \r\nOK\r\n>")
        assertTrue(reply.isOk)
        assertFalse(reply.searching)
        assertTrue(reply.dataLines.isEmpty())
        assertTrue(reply.errors.isEmpty())
        assertFalse(ElmResponseParser.parse("ATE0", "").isOk)
        assertFalse(ElmResponseParser.parse("ATE0", "OK\runexpected").isOk)
        assertFalse(ElmResponseParser.parse("ATE0", "OK\r?").isOk)
    }

    @Test
    fun `searching and successful bus initialization can precede a frame on the same line`() {
        val body = "0105\rSEARCHING...BUS INIT: ...OK\r48 6B 10 41 05 7B 84\r>"
        val reply = ElmResponseParser.parse("0105", body)
        assertTrue(reply.searching)
        assertEquals(listOf("48 6B 10 41 05 7B 84"), reply.dataLines)
        assertEquals(
            listOf(ObdResponse("10", 0x05, listOf(0x7B))),
            ElmResponseParser.parseMode01(body, ObdProtocol.ISO_9141_2, 0x05),
        )
        assertEquals(
            listOf(ObdResponse("7E8", 0x0D, listOf(0))),
            ElmResponseParser.parseMode01("SEARCHING...7E8 03 41 0D 00", can, 0x0D),
        )
    }

    @Test
    fun `all standard ATDPN protocol values and automatic prefixes map to explicit protocols`() {
        val expected = listOf(
            ObdProtocol.J1850_PWM, ObdProtocol.J1850_VPW, ObdProtocol.ISO_9141_2,
            ObdProtocol.KWP_SLOW, ObdProtocol.KWP_FAST, ObdProtocol.CAN_11_500,
            ObdProtocol.CAN_29_500, ObdProtocol.CAN_11_250, ObdProtocol.CAN_29_250,
        )
        expected.forEachIndexed { index, protocol ->
            assertEquals(protocol, ElmResponseParser.parseProtocol((index + 1).toString()))
            assertEquals(protocol, ElmResponseParser.parseProtocol("ATDPN\ra${index + 1}\r>"))
            assertTrue(protocol.description.isNotBlank())
        }
    }

    @Test
    fun `unresolved automatic and user defined protocols are explicit failures`() {
        for (value in listOf("0", "A0", "A", "AA", "B", "AB", "C", "AC")) {
            assertFailure(ObdFailure.UNSUPPORTED_PROTOCOL) { ElmResponseParser.parseProtocol(value) }
        }
    }

    @Test
    fun `ambiguous or malformed protocol replies are rejected`() {
        for (value in listOf("", "OK", "6\r7", "A66", "ISO 15765-4", "D", "06")) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseProtocol(value) }
        }
        assertFailure(ObdFailure.ADAPTER_ERROR) { ElmResponseParser.parseProtocol("?") }
    }

    @Test
    fun `eleven bit CAN payload excludes PCI and arbitrary padding`() {
        val result = ElmResponseParser.parseMode01("010C\r7e8 04 41 0c 1a f8 aa 55 ff\r>", can, 0x0C)
        assertEquals(listOf(ObdResponse("7E8", 0x0C, listOf(0x1A, 0xF8))), result)
        assertEquals(1726.0, ObdPid.ENGINE_RPM.decode(result.single().data), 0.0)
    }

    @Test
    fun `unpadded complete CAN single frames are valid`() {
        assertEquals(
            listOf(ObdResponse("7E8", 0x0D, listOf(0))),
            ElmResponseParser.parseMode01("7E8 03 41 0D 00", can, 0x0D),
        )
    }

    @Test
    fun `twenty nine bit CAN split and compact identifiers preserve ECU source`() {
        val protocol = ObdProtocol.CAN_29_500
        val body = "18 DA F1 10 03 41 05 7B 00 00 00 00\r18DAF118 03 41 05 50"
        assertEquals(
            listOf(ObdResponse("18DAF110", 0x05, listOf(0x7B)), ObdResponse("18DAF118", 0x05, listOf(0x50))),
            ElmResponseParser.parseMode01(body, protocol, 0x05),
        )
    }

    @Test
    fun `multiple CAN responders remain distinct and ordered`() {
        assertEquals(
            listOf(ObdResponse("7E8", 0x0C, listOf(0x1A, 0xF8)), ObdResponse("7E9", 0x0C, listOf(0x0F, 0xA0))),
            ElmResponseParser.parseMode01("7E8 04 41 0C 1A F8\r7E9 04 41 0C 0F A0", can, 0x0C),
        )
    }

    @Test
    fun `identical duplicates collapse but conflicting values from one ECU fail`() {
        assertEquals(
            listOf(ObdResponse("7E8", 0x0D, listOf(0))),
            ElmResponseParser.parseMode01("7E8 03 41 0D 00\r7E8 03 41 0D 00", can, 0x0D),
        )
        val failure = assertFailure(ObdFailure.MALFORMED) {
            ElmResponseParser.parseMode01("7E8 03 41 0D 00\r7E8 03 41 0D 01", can, 0x0D)
        }
        assertEquals("7E8", failure.ecu)
    }

    @Test
    fun `missing headers omitted PCI extra DLC and invalid identifiers cannot be inferred`() {
        for (body in listOf(
            "41 0D 00", "7E8 41 0D 00", "7E8 8 03 41 0D 00 00 00 00 00",
            "7E8 08 03 41 0D 00 00 00 00 00", "FFF 03 41 0D 00", "7E8 03 410D00",
            "7E8 03 41 0D GG", "07E8 03 41 0D 00",
        )) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x0D) }
        }
        assertFailure(ObdFailure.MALFORMED) {
            ElmResponseParser.parseMode01("20DAF110 03 41 0D 00", ObdProtocol.CAN_29_500, 0x0D)
        }
    }

    @Test
    fun `truncated overlong and invalid single frame lengths are rejected`() {
        for (body in listOf(
            "7E8 04 41 0C 1A", "7E8 04 41 0C 1A F8 00 00 00 00",
            "7E8 00", "7E8 01 41", "7E8 08 41 0C 1A F8 00 00 00",
            "7E8", "7E8 03 41 0C 1A",
        )) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x0C) }
        }
    }

    @Test
    fun `ISO TP first consecutive and flow control frames require later implementation`() {
        for (body in listOf(
            "7E8 10 0A 41 00 BE 3F B8 13",
            "7E8 21 41 00 BE 3F B8 13 00",
            "7E8 30 00 00 00 00 00 00 00",
        )) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x00) }
        }
    }

    @Test
    fun `J1850 ELM manual examples preserve separate source addresses`() {
        val body = "48 6B 10 41 00 BE 3E B8 11 FA\r48 6B 18 41 00 80 10 80 00 C0"
        for (protocol in listOf(ObdProtocol.J1850_PWM, ObdProtocol.J1850_VPW)) {
            assertEquals(
                listOf(
                    ObdResponse("10", 0x00, listOf(0xBE, 0x3E, 0xB8, 0x11)),
                    ObdResponse("18", 0x00, listOf(0x80, 0x10, 0x80, 0x00)),
                ),
                ElmResponseParser.parseMode01(body, protocol, 0x00),
            )
        }
    }

    @Test
    fun `KWP source is stable when format byte length changes across capability and RPM requests`() {
        for (protocol in listOf(ObdProtocol.KWP_SLOW, ObdProtocol.KWP_FAST)) {
            val capability = ElmResponseParser.parseMode01("86 F1 10 41 00 BE 3E B8 11 8D", protocol, 0x00).single()
            val rpm = ElmResponseParser.parseMode01("84 F1 10 41 0C 1A F8 E4", protocol, 0x0C).single()
            assertEquals("10", capability.ecu)
            assertEquals(capability.ecu, rpm.ecu)
            assertEquals(listOf(0x1A, 0xF8), rpm.data)
        }
    }

    @Test
    fun `legacy missing checksum or invalid KWP length cannot be mistaken for valid data`() {
        assertFailure(ObdFailure.MALFORMED) {
            ElmResponseParser.parseMode01("48 6B 10 41 0C 1A F8", ObdProtocol.ISO_9141_2, 0x0C)
        }
        for (body in listOf(
            "83 F1 10 41 0C 1A F8 E4",
            "04 F1 10 41 0C 1A F8 E4",
            "80 F1 10 04 41 0C 1A F8 E4",
        )) {
            assertFailure(ObdFailure.MALFORMED) {
                ElmResponseParser.parseMode01(body, ObdProtocol.KWP_FAST, 0x0C)
            }
        }
    }

    @Test
    fun `adapter DATA ERROR rejects a printed legacy frame instead of guessing its checksum scheme`() {
        // ELM validates the protocol-specific checksum/CRC and marks corrupt printed frames.
        val failure = assertFailure(ObdFailure.ADAPTER_ERROR) {
            ElmResponseParser.parseMode01("48 6B 10 41 05 7B 00 <DATA ERROR", ObdProtocol.ISO_9141_2, 0x05)
        }
        assertTrue(failure.message!!.contains("DATA ERROR"))
    }

    @Test
    fun `wrong service or PID is never decoded under the requested metric`() {
        for (body in listOf("7E8 03 41 05 40", "7E8 03 42 0D 40", "7E8 04 41 0D 40 41")) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x0D) }
        }
        // Fuel trim is now implemented; enumerated fuel type remains outside the scalar catalog.
        assertEquals(listOf(0x40), ElmResponseParser.parseMode01("7E8 03 41 06 40", can, 0x06).single().data)
        assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01("7E8 03 41 51 01", can, 0x51) }
    }

    @Test
    fun `negative response retains ECU identity and has no numeric value`() {
        val failure = assertFailure(ObdFailure.NEGATIVE_RESPONSE) {
            ElmResponseParser.parseMode01("7E9 03 7F 01 12", can, 0x0C)
        }
        assertEquals("7E9", failure.ecu)
        assertTrue(failure.message!!.contains("12"))
    }

    @Test
    fun `standard adapter errors are explicitly classified`() {
        val errors = mapOf(
            "NO DATA" to ElmError.NO_DATA, "UNABLE TO CONNECT" to ElmError.UNABLE_TO_CONNECT,
            "BUS INIT: ...ERROR" to ElmError.BUS_INIT_ERROR, "BUS ERROR" to ElmError.BUS_ERROR,
            "BUS BUSY" to ElmError.BUS_ERROR, "CAN ERROR" to ElmError.CAN_ERROR,
            "DATA ERROR" to ElmError.DATA_ERROR, "BUFFER FULL" to ElmError.BUFFER_FULL,
            "STOPPED" to ElmError.STOPPED, "?" to ElmError.UNKNOWN_COMMAND,
            "LV RESET" to ElmError.LOW_VOLTAGE, "ERR94" to ElmError.OTHER_ERROR,
        )
        errors.forEach { (body, expected) ->
            val reply = ElmResponseParser.parse("010D", "010D\r$body\r>")
            assertEquals(listOf(expected), reply.errors)
            assertFalse(reply.isOk)
            val reason = if (expected == ElmError.NO_DATA) ObdFailure.NO_DATA else ObdFailure.ADAPTER_ERROR
            assertFailure(reason) { ElmResponseParser.parseMode01(body, can, 0x0D) }
        }
    }

    @Test
    fun `mixed error and valid frame fails the command conservatively`() {
        assertFailure(ObdFailure.NO_DATA) {
            ElmResponseParser.parseMode01("7E8 03 41 0D 00\rNO DATA", can, 0x0D)
        }
        assertFailure(ObdFailure.ADAPTER_ERROR) {
            ElmResponseParser.parseMode01("NO DATA\rCAN ERROR\r7E8 03 41 0D 00", can, 0x0D)
        }
    }

    @Test
    fun `empty status only and unknown responses are explicit malformed failures`() {
        for (body in listOf("", "SEARCHING...", "BUS INIT: ...", "BUS INIT: ...OK", "OK", "ELM327 v2.1")) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x0D) }
        }
    }

    @Test
    fun `control bytes extra prompts and unbounded bodies are rejected`() {
        for (body in listOf("\u0000", "\u001B", "\u00FF", "7E8 03 41 0D 00>NO DATA", ">>", " ".repeat(65_537))) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.parseMode01(body, can, 0x0D) }
        }
    }

    @Test
    fun `ELM capability example maps high bits to low PID numbers`() {
        val response = ElmResponseParser.parseMode01("7E8 06 41 00 BE 1F B8 10 00", can, 0x00).single()
        assertEquals(
            setOf(1, 3, 4, 5, 6, 7, 12, 13, 14, 15, 16, 17, 19, 20, 21, 28),
            ElmResponseParser.supportedPids(response),
        )
    }

    @Test
    fun `capability blocks carry their base and continuation bit`() {
        assertEquals(
            setOf(0x01, 0x20),
            ElmResponseParser.supportedPids(ObdResponse("7E8", 0x00, listOf(0x80, 0, 0, 1))),
        )
        assertEquals(
            setOf(0x21, 0x40),
            ElmResponseParser.supportedPids(ObdResponse("7E8", 0x20, listOf(0x80, 0, 0, 1))),
        )
        assertEquals(
            (1..32).toSet(),
            ElmResponseParser.supportedPids(ObdResponse("7E8", 0x00, listOf(255, 255, 255, 255))),
        )
        assertTrue(ElmResponseParser.supportedPids(ObdResponse("7E8", 0x00, listOf(0, 0, 0, 0))).isEmpty())
    }

    @Test
    fun `each ECU capability set remains separate`() {
        val responses = ElmResponseParser.parseMode01(
            "7E8 06 41 00 00 10 00 00\r7E9 06 41 00 00 08 00 00",
            can, 0x00,
        )
        assertEquals(
            mapOf("7E8" to setOf(0x0C), "7E9" to setOf(0x0D)),
            responses.associate { it.ecu to ElmResponseParser.supportedPids(it) },
        )
    }

    @Test
    fun `invalid support bitmap structures are rejected`() {
        for (response in listOf(
            ObdResponse("7E8", 0x01, listOf(0, 0, 0, 0)),
            ObdResponse("7E8", 0x00, listOf(0, 0, 0)),
            ObdResponse("7E8", 0x00, listOf(0, 0, 0, 256)),
            ObdResponse("7E8", 0x100, listOf(0, 0, 0, 0)),
        )) {
            assertFailure(ObdFailure.MALFORMED) { ElmResponseParser.supportedPids(response) }
        }
    }

    private fun assertFailure(reason: ObdFailure, action: () -> Unit): ObdParseException =
        assertThrows(ObdParseException::class.java, action).also { assertEquals(reason, it.reason) }
}
