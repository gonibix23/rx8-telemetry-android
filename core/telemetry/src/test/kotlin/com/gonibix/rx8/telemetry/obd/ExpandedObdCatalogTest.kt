package com.gonibix.rx8.telemetry.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedObdCatalogTest {
    @Test fun catalogCoversEveryVerifiedBaseScalarRequestWithoutDuplicateCommands() {
        val excluded = setOf(0x01, 0x02, 0x03, 0x12, 0x13, 0x1C, 0x1D, 0x1E, 0x20, 0x40, 0x41, 0x51, 0x5F, 0x60)
        assertEquals((1..0x64).filterNot { it in excluded }.toSet(), ObdPid.entries.map { it.pid }.toSet())
        assertEquals(86, ObdPid.entries.size)
        assertEquals(84, ObdPid.entries.count { it.fields.isNotEmpty() })
        assertEquals(120, ObdField.entries.size)
        assertEquals(86, ObdPid.entries.map { it.command }.distinct().size)
        assertEquals(ObdField.entries.toSet(), ObdPid.entries.flatMap { it.fields }.toSet())
        assertTrue(ObdPid.entries.all { it.dataBytes in 1..5 })
    }

    @Test fun scalarExamplesUseVerifiedSignedUnsignedOffsetsAndUnits() {
        val examples = listOf(
            Example(ObdField.SHORT_TERM_FUEL_TRIM_BANK_1, listOf(0), -100.0, "%"),
            Example(ObdField.LONG_TERM_FUEL_TRIM_BANK_2, listOf(255), 99.21875, "%"),
            Example(ObdField.FUEL_PRESSURE, listOf(100), 300.0, "kPa"),
            Example(ObdField.MANIFOLD_PRESSURE, listOf(100), 100.0, "kPa"),
            Example(ObdField.IGNITION_TIMING, listOf(100), -14.0, "deg"),
            Example(ObdField.MASS_AIR_FLOW, listOf(1, 144), 4.0, "g/s"),
            Example(ObdField.ENGINE_RUN_TIME, listOf(1, 44), 300.0, "s"),
            Example(ObdField.DISTANCE_WITH_MIL, listOf(1, 44), 300.0, "km"),
            Example(ObdField.FUEL_RAIL_PRESSURE_RELATIVE, listOf(3, 232), 79.0, "kPa"),
            Example(ObdField.FUEL_RAIL_PRESSURE_GAUGE, listOf(3, 232), 10_000.0, "kPa"),
            Example(ObdField.EGR_ERROR, listOf(128), 0.0, "%"),
            Example(ObdField.EVAP_VAPOR_PRESSURE, listOf(0xFF, 0x80), -32.0, "Pa"),
            Example(ObdField.EVAP_VAPOR_PRESSURE_WIDE, listOf(0xFF, 0xFF), -1.0, "Pa"),
            Example(ObdField.EVAP_VAPOR_PRESSURE_ABSOLUTE, listOf(0x4E, 0x20), 100.0, "kPa"),
            Example(ObdField.CATALYST_TEMPERATURE_B1_S1, listOf(0x0F, 0xA0), 360.0, "°C"),
            Example(ObdField.CONTROL_MODULE_VOLTAGE, listOf(0x36, 0xB0), 14.0, "V"),
            Example(ObdField.ABSOLUTE_ENGINE_LOAD, listOf(0xFF, 0xFF), 25_700.0, "%"),
            Example(ObdField.TIME_WITH_MIL, listOf(0, 60), 60.0, "min"),
            Example(ObdField.WARM_UPS_SINCE_CLEAR, listOf(7), 7.0, "count"),
            Example(ObdField.FUEL_INJECTION_TIMING, listOf(0x69, 0), 0.0, "deg"),
            Example(ObdField.ENGINE_FUEL_RATE, listOf(1, 44), 15.0, "L/h"),
            Example(ObdField.DRIVER_DEMAND_TORQUE, listOf(0), -125.0, "%"),
            Example(ObdField.ACTUAL_ENGINE_TORQUE, listOf(255), 130.0, "%"),
            Example(ObdField.ENGINE_REFERENCE_TORQUE, listOf(1, 144), 400.0, "N·m"),
        )
        examples.forEach {
            assertEquals(it.field.name, it.expected, decoded(it.field, it.bytes).value!!, 0.000000001)
            assertEquals(it.unit, it.field.unit)
        }
    }

    @Test fun everyScalarRetainsFiniteDomainAtByteBoundariesAndRejectsInvalidBytes() {
        ObdPid.entries.filter { it.fields.isNotEmpty() }.forEach { pid ->
            for (byte in listOf(0, 127, 128, 255)) {
                val values = pid.decodeValues(List(pid.dataBytes) { byte })
                values.forEach { (field, decoded) ->
                    assertTrue(decoded.available)
                    decoded.value?.let { assertTrue(field.name + ": " + it, field.isValueInRange(it)) }
                }
            }
            assertThrows(ObdParseException::class.java) { pid.decodeValues(emptyList()) }
            assertThrows(ObdParseException::class.java) { pid.decodeValues(List(pid.dataBytes + 1) { 0 }) }
            assertThrows(ObdParseException::class.java) { pid.decodeValues(List(pid.dataBytes) { 256 }) }
            assertThrows(ObdParseException::class.java) { pid.decodeValues(List(pid.dataBytes) { -1 }) }
        }
    }

    @Test fun optionalFuelTrimBanksAreSeparateAndAbsentBankIsNotZero() {
        val one = ObdPid.SHORT_TERM_FUEL_TRIM_BANK_1.decodeValues(listOf(128))
        assertEquals(0.0, one.getValue(ObdField.SHORT_TERM_FUEL_TRIM_BANK_1).value)
        val missing = one.getValue(ObdField.SHORT_TERM_FUEL_TRIM_BANK_3)
        assertFalse(missing.available)
        assertNull(missing.value)
        val two = ObdPid.SHORT_TERM_FUEL_TRIM_BANK_1.decodeValues(listOf(128, 64))
        assertEquals(-50.0, two.getValue(ObdField.SHORT_TERM_FUEL_TRIM_BANK_3).value)
        val secondary = ObdPid.SECONDARY_LONG_TERM_TRIM_BANK_2.decodeValues(listOf(128, 192))
        assertEquals(50.0, secondary.getValue(ObdField.SECONDARY_LONG_TERM_TRIM_BANK_4).value)
    }

    @Test fun narrowOxygenVoltageAndTrimShareOneRequestAndUnusedTrimIsNull() {
        val values = ObdPid.OXYGEN_SENSOR_1.decodeValues(listOf(100, 128))
        assertEquals(0.5, values.getValue(ObdField.OXYGEN_SENSOR_1_VOLTAGE).value)
        assertEquals(0.0, values.getValue(ObdField.OXYGEN_SENSOR_1_FUEL_TRIM).value)
        val unused = ObdPid.OXYGEN_SENSOR_1.decodeValues(listOf(100, 255))
        assertEquals(0.5, unused.getValue(ObdField.OXYGEN_SENSOR_1_VOLTAGE).value)
        assertNull(unused.getValue(ObdField.OXYGEN_SENSOR_1_FUEL_TRIM).value)
    }

    @Test fun wideOxygenRatioVoltageAndCurrentHonorStandardAndCustomScaling() {
        val wide = ObdPid.WIDE_OXYGEN_SENSOR_1.decodeValues(listOf(0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(2.0, wide.getValue(ObdField.WIDE_OXYGEN_SENSOR_1_RATIO).value)
        assertEquals(8.0, wide.getValue(ObdField.WIDE_OXYGEN_SENSOR_1_VOLTAGE).value)
        val context = ObdDecodeContext(4, 16, 64, 77, 100)
        val scaled = ObdPid.WIDE_OXYGEN_SENSOR_1.decodeValues(listOf(0x7D, 0x00, 0x9C, 0x40), context)
        assertEquals(32000.0 * 4 / 65535, scaled.getValue(ObdField.WIDE_OXYGEN_SENSOR_1_RATIO).value!!, 0.000000001)
        assertEquals(40000.0 * 16 / 65535, scaled.getValue(ObdField.WIDE_OXYGEN_SENSOR_1_VOLTAGE).value!!, 0.000000001)
        val current = ObdPid.CURRENT_OXYGEN_SENSOR_1.decodeValues(listOf(0, 0, 0x5C, 0x40), context)
        assertEquals(-17.875, current.getValue(ObdField.CURRENT_OXYGEN_SENSOR_1_CURRENT).value!!, 0.0)
        val zero = ObdPid.CURRENT_OXYGEN_SENSOR_1.decodeValues(listOf(0, 0, 0x80, 0))
        assertEquals(0.0, zero.getValue(ObdField.CURRENT_OXYGEN_SENSOR_1_CURRENT).value)
        assertEquals(127.0 * 770 / 255, decoded(ObdField.MANIFOLD_PRESSURE, listOf(127), context).value!!, 0.000000001)
        assertEquals(1000.0, decoded(ObdField.MASS_AIR_FLOW, listOf(255, 255), context).value!!, 0.0)
    }

    @Test fun unreadAdvertisedScalingDoesNotManufactureDefaultValues() {
        val unknown = ObdDecodeContext(null, null, null, null, null)
        for (field in listOf(ObdField.MANIFOLD_PRESSURE, ObdField.MASS_AIR_FLOW, ObdField.COMMANDED_EQUIVALENCE_RATIO)) {
            assertNull(decoded(field, List(field.parameter.dataBytes) { 100 }, unknown).value)
        }
        assertTrue(ObdPid.WIDE_OXYGEN_SENSOR_2.decodeValues(listOf(1, 2, 3, 4), unknown).values.all { it.value == null })
        assertEquals(0.0, decoded(ObdField.ENGINE_OIL_TEMPERATURE, listOf(40), unknown).value)
        assertThrows(IllegalArgumentException::class.java) { ObdDecodeContext(maximumRatio = 256) }
    }

    @Test fun signedVaporPressureUsesTheWholeTwosComplementWord() {
        for ((bytes, signed) in listOf(
            listOf(0x00, 0x80) to 128.0, listOf(0x7F, 0xFF) to 32767.0,
            listOf(0x80, 0x00) to -32768.0, listOf(0xFF, 0x80) to -128.0,
        )) {
            assertEquals(signed, decoded(ObdField.EVAP_VAPOR_PRESSURE_WIDE, bytes).value)
            assertEquals(signed / 4, decoded(ObdField.EVAP_VAPOR_PRESSURE, bytes).value)
        }
    }

    @Test fun torqueMapYieldsFiveSeparateScalarChannels() {
        val values = ObdPid.ENGINE_TORQUE_MAP.decodeValues(listOf(0, 125, 130, 200, 255))
        assertEquals(listOf(-125.0, 0.0, 5.0, 75.0, 130.0), values.values.map { it.value })
        assertEquals(5, values.keys.distinct().size)
        assertTrue(values.keys.all { it.pid == 0x64 })
    }

    @Test fun genericParserAcceptsEveryCatalogShapeWithoutRelaxingFraming() {
        for (pid in ObdPid.entries) for (length in pid.minimumDataBytes..pid.dataBytes) {
            val data = List(length) { 0x80 }
            val payload = listOf(0x41, pid.pid) + data
            val can = "7E8 " + (listOf(payload.size) + payload).joinToString(" ") { "%02X".format(it) }
            assertEquals(data, ElmResponseParser.parseMode01(can, ObdProtocol.CAN_11_500, pid.pid).single().data)
            val kwp = (listOf(0x80 + payload.size, 0xF1, 0x10) + payload + 0x00).joinToString(" ") { "%02X".format(it) }
            assertEquals("10", ElmResponseParser.parseMode01(kwp, ObdProtocol.KWP_FAST, pid.pid).single().ecu)
        }
        assertThrows(ObdParseException::class.java) {
            ElmResponseParser.parseMode01("7E8 10 08 41 64 01 02 03", ObdProtocol.CAN_11_500, 0x64)
        }
        assertThrows(ObdParseException::class.java) {
            ElmResponseParser.parseMode01("7E8 03 41 51 01", ObdProtocol.CAN_11_500, 0x51)
        }
    }

    @Test fun parserKeepsMultiComponentSourcesSeparateAndBoundsTheirCount() {
        val body = "7E8 06 41 24 80 00 20 00\r7E9 06 41 24 90 00 30 00"
        val responses = ElmResponseParser.parseMode01(body, ObdProtocol.CAN_11_500, 0x24)
        assertEquals(listOf("7E8", "7E9"), responses.map { it.ecu })
        assertFalse(responses[0].data == responses[1].data)
        val tooMany = (0..32).joinToString("\r") { "%03X 03 41 0D 00".format(0x700 + it) }
        assertThrows(ObdParseException::class.java) { ElmResponseParser.parseMode01(tooMany, ObdProtocol.CAN_11_500, 0x0D) }
    }

    private fun decoded(field: ObdField, bytes: List<Int>, context: ObdDecodeContext = ObdDecodeContext()) =
        field.parameter.decodeValues(bytes, context).getValue(field)

    private data class Example(val field: ObdField, val bytes: List<Int>, val expected: Double, val unit: String)
}
