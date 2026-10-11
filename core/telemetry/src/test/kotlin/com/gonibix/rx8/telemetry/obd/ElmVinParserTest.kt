package com.gonibix.rx8.telemetry.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ElmVinParserTest {
    private val syntheticVin = "ABCDEFGHJKLMNPR12"
    private val otherSyntheticVin = "ZYXWVUTSRPNMLK987"
    private val can = ObdProtocol.CAN_11_500
    private val literalCan = listOf(
        "7E8 10 14 49 02 01 41 42 43",
        "7E8 21 44 45 46 47 48 4A 4B",
        "7E8 22 4C 4D 4E 50 52 31 32",
    )

    @Test
    fun `complete CAN first and consecutive frames produce exactly one VIN`() {
        assertEquals(
            listOf(VinResponse("7E8", syntheticVin)),
            ElmVinParser.parse(literalCan.joinToString("\r"), can),
        )
    }

    @Test
    fun `CAN 250 kbaud uses the same VIN framing`() {
        assertEquals(
            listOf(VinResponse("7E8", syntheticVin)),
            ElmVinParser.parse(literalCan.joinToString("\r"), ObdProtocol.CAN_11_250),
        )
    }

    @Test
    fun `command echo searching and prompt are handled by the common ELM envelope`() {
        val body = "09 02\r\nSEARCHING...\r\n" + literalCan.joinToString("\r\n").lowercase() + "\r\n>"
        assertEquals(listOf(VinResponse("7E8", syntheticVin)), ElmVinParser.parse(body, can))
    }

    @Test
    fun `twenty nine bit split and compact CAN identifiers are canonicalized`() {
        for (protocol in listOf(ObdProtocol.CAN_29_500, ObdProtocol.CAN_29_250)) {
            val body = literalCan.mapIndexed { index, line ->
                line.replace("7E8", if (index == 1) "18DAF110" else "18 DA F1 10")
            }.joinToString("\r")
            assertEquals(listOf(VinResponse("18DAF110", syntheticVin)), ElmVinParser.parse(body, protocol))
        }
    }

    @Test
    fun `interleaved ECUs never borrow each other's CAN continuation bytes`() {
        val first = canRows(syntheticVin, "7E8")
        val second = canRows(otherSyntheticVin, "7E9")
        val interleaved = first.zip(second).flatMap { listOf(it.first, it.second) }
        assertEquals(
            listOf(VinResponse("7E8", syntheticVin), VinResponse("7E9", otherSyntheticVin)),
            ElmVinParser.parse(interleaved.joinToString("\r"), can),
        )
    }

    @Test
    fun `matching VINs from separate ECUs retain both sources`() {
        assertEquals(
            listOf(VinResponse("7E8", syntheticVin), VinResponse("7E9", syntheticVin)),
            ElmVinParser.parse((literalCan + canRows(syntheticVin, "7E9")).joinToString("\r"), can),
        )
    }

    @Test
    fun `missing CAN first middle or last frames fail instead of returning partial data`() {
        for (rows in listOf(literalCan.drop(1), listOf(literalCan[0], literalCan[2]), literalCan.dropLast(1))) {
            malformed { ElmVinParser.parse(rows.joinToString("\r"), can) }
        }
    }

    @Test
    fun `CAN continuation order and duplicates are validated`() {
        for (rows in listOf(
            listOf(literalCan[0], literalCan[2], literalCan[1]),
            listOf(literalCan[0], literalCan[0], literalCan[1], literalCan[2]),
            listOf(literalCan[0], literalCan[1], literalCan[1], literalCan[2]),
            literalCan + literalCan.last(),
        )) {
            malformed { ElmVinParser.parse(rows.joinToString("\r"), can) }
        }
    }

    @Test
    fun `one incomplete ECU invalidates the complete reply even if another VIN was reconstructed`() {
        val body = (literalCan + canRows(otherSyntheticVin, "7E9").take(2)).joinToString("\r")
        val error = malformed { ElmVinParser.parse(body, can) }
        assertEquals("7E9", error.ecu)
    }

    @Test
    fun `incorrect CAN lengths truncated frames and surplus bytes fail`() {
        for (rows in listOf(
            literalCan.toMutableList().also { it[0] = it[0].replace("10 14", "10 13") },
            literalCan.toMutableList().also { it[0] = it[0].replace("10 14", "10 15") },
            literalCan.toMutableList().also { it[0] = it[0].dropLast(3) },
            literalCan.toMutableList().also { it[1] = it[1].dropLast(3) },
            literalCan.toMutableList().also { it[2] = it[2].dropLast(3) },
            literalCan.toMutableList().also { it[2] = it[2] + " 00" },
        )) {
            malformed { ElmVinParser.parse(rows.joinToString("\r"), can) }
        }
    }

    @Test
    fun `unexpected service PID and multiple VIN item counts are rejected`() {
        for (prefix in listOf("49 04 01", "41 02 01", "49 02 00", "49 02 02")) {
            malformed {
                ElmVinParser.parse(literalCan.joinToString("\r").replace("49 02 01", prefix), can)
            }
        }
    }

    @Test
    fun `headerless DLC extended addressing and flow control layouts are not guessed`() {
        for (body in listOf(
            "014\r0: 49 02 01 41 42 43\r1: 44 45 46 47 48 4A 4B\r2: 4C 4D 4E 50 52 31 32",
            literalCan.joinToString("\r").replace("7E8 ", "7E8 8 "),
            literalCan.joinToString("\r").replace("7E8 ", "7E8 08 "),
            "7E8 F1 10 14 49 02 01 41 42",
            "7E8 30 00 00 00 00 00 00 00",
            "7E8 03 49 02 01",
        )) {
            malformed { ElmVinParser.parse(body, can) }
        }
    }

    @Test
    fun `malformed CAN identifiers hex and byte boundaries fail`() {
        for (body in listOf(
            literalCan.joinToString("\r").replace("7E8", "FFF"),
            literalCan.joinToString("\r").replace("7E8", "07E8"),
            literalCan.joinToString("\r").replace("31 32", "31 ZZ"),
            literalCan.joinToString("\r").replace("31 32", "3132"),
            "7E8",
        )) {
            malformed { ElmVinParser.parse(body, can) }
        }
        malformed {
            ElmVinParser.parse(literalCan.joinToString("\r").replace("7E8", "20DAF110"), ObdProtocol.CAN_29_500)
        }
    }

    @Test
    fun `five legacy numbered packets remove exactly three filler bytes`() {
        for (protocol in listOf(ObdProtocol.ISO_9141_2, ObdProtocol.J1850_PWM, ObdProtocol.J1850_VPW)) {
            assertEquals(
                listOf(VinResponse("10", syntheticVin)),
                ElmVinParser.parse(legacyRows(syntheticVin, 0x10).joinToString("\r"), protocol),
            )
        }
    }

    @Test
    fun `short KWP packets validate the encoded length and retain source address`() {
        for (protocol in listOf(ObdProtocol.KWP_FAST, ObdProtocol.KWP_SLOW)) {
            assertEquals(
                listOf(VinResponse("10", syntheticVin)),
                ElmVinParser.parse(legacyRows(syntheticVin, 0x10, kwp = true).joinToString("\r"), protocol),
            )
        }
    }

    @Test
    fun `legacy ECU packets may interleave without merging different VINs`() {
        val first = legacyRows(syntheticVin, 0x10)
        val second = legacyRows(otherSyntheticVin, 0x18)
        val body = first.zip(second).flatMap { listOf(it.first, it.second) }.joinToString("\r")
        assertEquals(
            listOf(VinResponse("10", syntheticVin), VinResponse("18", otherSyntheticVin)),
            ElmVinParser.parse(body, ObdProtocol.ISO_9141_2),
        )
    }

    @Test
    fun `missing repeated and out of order legacy packets fail`() {
        val rows = legacyRows(syntheticVin, 0x10)
        for (invalid in listOf(
            rows.drop(1), rows.dropLast(1), rows.filterIndexed { index, _ -> index != 2 },
            listOf(rows[1], rows[0]) + rows.drop(2),
            rows.take(2) + rows[1] + rows.drop(2), rows + rows.last(),
        )) {
            malformed { ElmVinParser.parse(invalid.joinToString("\r"), ObdProtocol.ISO_9141_2) }
        }
    }

    @Test
    fun `legacy filler packet size checksum presence and KWP headers are validated`() {
        val rows = legacyRows(syntheticVin, 0x10)
        for (body in listOf(
            rows.joinToString("\r").replace("01 00 00 00", "01 01 00 00"),
            rows.joinToString("\r").replace("49 02 01", "49 04 01"),
            rows.toMutableList().also { it[2] = it[2].dropLast(3) }.joinToString("\r"),
            rows.toMutableList().also { it[2] = it[2] + " 00" }.joinToString("\r"),
        )) {
            malformed { ElmVinParser.parse(body, ObdProtocol.ISO_9141_2) }
        }
        for (header in listOf("86 F1 10", "07 F1 10", "80 F1 10 07")) {
            malformed {
                ElmVinParser.parse(
                    legacyRows(syntheticVin, 0x10, kwp = true).joinToString("\r").replace("87 F1 10", header),
                    ObdProtocol.KWP_FAST,
                )
            }
        }
    }

    @Test
    fun `invalid VIN characters cannot be accepted or normalized from ECU wire data`() {
        for (invalid in listOf(
            syntheticVin.replaceRange(5, 6, "I"), syntheticVin.replaceRange(5, 6, "O"),
            syntheticVin.replaceRange(5, 6, "Q"), syntheticVin.lowercase(),
            syntheticVin.replaceRange(5, 6, " "), syntheticVin.replaceRange(5, 6, "\u0000"),
            syntheticVin.replaceRange(5, 6, "\u00FF"), syntheticVin.replaceRange(5, 6, "-"),
        )) {
            malformed { ElmVinParser.parse(canRows(invalid, "7E8").joinToString("\r"), can) }
            malformed {
                ElmVinParser.parse(legacyRows(invalid, 0x10).joinToString("\r"), ObdProtocol.ISO_9141_2)
            }
        }
    }

    @Test
    fun `negative VIN responses retain ECU source without returning a candidate`() {
        val canFailure = failure(ObdFailure.NEGATIVE_RESPONSE) {
            ElmVinParser.parse("7E9 03 7F 09 12 00 00 00 00", can)
        }
        assertEquals("7E9", canFailure.ecu)
        val legacyFailure = failure(ObdFailure.NEGATIVE_RESPONSE) {
            ElmVinParser.parse("48 6B 18 7F 09 12 65", ObdProtocol.ISO_9141_2)
        }
        assertEquals("18", legacyFailure.ecu)
        malformed { ElmVinParser.parse("7E8 03 7F 01 12", can) }
    }

    @Test
    fun `no data and adapter errors keep their established failure classifications`() {
        failure(ObdFailure.NO_DATA) { ElmVinParser.parse("0902\rNO DATA\r>", can) }
        for (body in listOf("?", "CAN ERROR", "UNABLE TO CONNECT", "BUFFER FULL", "STOPPED")) {
            failure(ObdFailure.ADAPTER_ERROR) { ElmVinParser.parse(body, can) }
        }
        failure(ObdFailure.ADAPTER_ERROR) {
            ElmVinParser.parse(legacyRows(syntheticVin, 0x10).joinToString("\r") + " <DATA ERROR", ObdProtocol.ISO_9141_2)
        }
    }

    @Test
    fun `mixed successful VIN and adapter failure is rejected conservatively`() {
        failure(ObdFailure.NO_DATA) { ElmVinParser.parse(literalCan.joinToString("\r") + "\rNO DATA", can) }
        failure(ObdFailure.NEGATIVE_RESPONSE) {
            ElmVinParser.parse(literalCan.joinToString("\r") + "\r7E9 03 7F 09 12", can)
        }
    }

    @Test
    fun `empty unknown and invalid ELM envelopes produce no candidate`() {
        for (body in listOf("", "OK", "SEARCHING...", "ELM327", "\u0000", ">>", " ".repeat(65_537))) {
            malformed { ElmVinParser.parse(body, can) }
        }
    }

    @Test
    fun `exceptions never include the raw candidate VIN or complete response text`() {
        val invalidVin = syntheticVin.replaceRange(5, 6, "I")
        val body = canRows(invalidVin, "7E8").joinToString("\r")
        val error = malformed { ElmVinParser.parse(body, can) }
        assertFalse(error.toString().contains(invalidVin))
        assertFalse(error.toString().contains(body))
        assertFalse(error.message!!.contains("41 42 43"))
    }

    private fun canRows(vin: String, ecu: String): List<String> {
        val payload = listOf(0x49, 0x02, 0x01) + vin.map(Char::code)
        return listOf(
            "$ecu " + hex(listOf(0x10, 0x14) + payload.take(6)),
            "$ecu " + hex(listOf(0x21) + payload.drop(6).take(7)),
            "$ecu " + hex(listOf(0x22) + payload.drop(13)),
        )
    }

    private fun legacyRows(vin: String, source: Int, kwp: Boolean = false): List<String> =
        (listOf(0, 0, 0) + vin.map(Char::code)).chunked(4).mapIndexed { index, characters ->
            val header = if (kwp) listOf(0x87, 0xF1, source) else listOf(0x48, 0x6B, source)
            val frame = header + listOf(0x49, 0x02, index + 1) + characters
            // Synthetic adapter traces. ELM, not this parser, validates the protocol's checksum/CRC.
            hex(frame + (frame.sum() and 0xFF))
        }

    private fun hex(data: List<Int>): String = data.joinToString(" ") { it.toString(16).padStart(2, '0').uppercase() }
    private fun malformed(action: () -> Unit): ObdParseException = failure(ObdFailure.MALFORMED, action)
    private fun failure(reason: ObdFailure, action: () -> Unit): ObdParseException =
        assertThrows(ObdParseException::class.java, action).also { assertEquals(reason, it.reason) }
}
