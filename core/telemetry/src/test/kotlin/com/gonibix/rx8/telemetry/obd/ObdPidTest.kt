package com.gonibix.rx8.telemetry.obd

import com.gonibix.rx8.telemetry.TelemetryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObdPidTest {
    @Test
    fun `six decoder candidates retain existing metric metadata`() {
        assertEquals(TelemetryMetric.entries.toSet(), ObdPid.entries.map { it.metric }.toSet())
        assertEquals(
            setOf("010C", "010D", "0105", "010F", "0111", "0104"),
            ObdPid.entries.map { it.command }.toSet(),
        )
        ObdPid.entries.forEach {
            assertEquals(it.metric.label, it.label)
            assertEquals(it.metric.unit, it.unit)
        }
    }

    @Test
    fun `ELM manual RPM example is decoded big endian`() {
        assertEquals(1726.0, ObdPid.ENGINE_RPM.decode(listOf(0x1A, 0xF8)), 0.0)
        assertEquals(64.25, ObdPid.ENGINE_RPM.decode(listOf(0x01, 0x01)), 0.0)
        assertEquals(16383.75, ObdPid.ENGINE_RPM.decode(listOf(0xFF, 0xFF)), 0.0)
    }

    @Test
    fun `ELM manual coolant example and negative temperatures are preserved`() {
        assertEquals(83.0, ObdPid.COOLANT_TEMPERATURE.decode(listOf(0x7B)), 0.0)
        for (pid in listOf(ObdPid.COOLANT_TEMPERATURE, ObdPid.INTAKE_TEMPERATURE)) {
            assertEquals(-40.0, pid.decode(listOf(0x00)), 0.0)
            assertEquals(0.0, pid.decode(listOf(0x28)), 0.0)
            assertEquals(215.0, pid.decode(listOf(0xFF)), 0.0)
        }
    }

    @Test
    fun `speed is an unsigned byte in kilometres per hour`() {
        assertEquals(100.0, ObdPid.SPEED.decode(listOf(0x64)), 0.0)
        assertEquals(255.0, ObdPid.SPEED.decode(listOf(0xFF)), 0.0)
        assertEquals("km/h", ObdPid.SPEED.unit)
    }

    @Test
    fun `load and throttle use the full unsigned percentage range`() {
        for (pid in listOf(ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION)) {
            assertEquals(0.0, pid.decode(listOf(0)), 0.0)
            assertEquals(50.19607843137255, pid.decode(listOf(128)), 0.000000001)
            assertEquals(100.0, pid.decode(listOf(255)), 0.0)
        }
    }

    @Test
    fun `valid zero readings are not treated as missing`() {
        for (pid in listOf(ObdPid.ENGINE_RPM, ObdPid.SPEED, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE_POSITION)) {
            assertEquals(0.0, pid.decode(List(pid.dataBytes) { 0 }), 0.0)
        }
    }

    @Test
    fun `truncated and surplus decoder data cannot produce readings`() {
        ObdPid.entries.forEach { pid ->
            for (data in listOf(emptyList(), List(pid.dataBytes + 1) { 0 })) {
                assertEquals(
                    ObdFailure.MALFORMED,
                    assertThrows(ObdParseException::class.java) { pid.decode(data) }.reason,
                )
            }
        }
        assertThrows(ObdParseException::class.java) { ObdPid.ENGINE_RPM.decode(listOf(0x1A)) }
    }

    @Test
    fun `signed or out of range integers are not accepted as wire bytes`() {
        for (value in listOf(-1, 256, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(ObdParseException::class.java) { ObdPid.SPEED.decode(listOf(value)) }
            assertThrows(ObdParseException::class.java) { ObdPid.ENGINE_RPM.decode(listOf(0, value)) }
        }
    }
}
