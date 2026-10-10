package com.gonibix.rx8.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTelemetrySourceTest {
    @Test
    fun `starts disconnected without values`() {
        val snapshot = DemoTelemetrySource().snapshot

        assertEquals(ConnectionState.DISCONNECTED, snapshot.connection)
        assertEquals(SimulationScenario.NORMAL, snapshot.scenario)
        assertEquals(DataOrigin.SIMULATED, snapshot.origin)
        assertEquals(0L, snapshot.elapsedMs)
        assertTrue(snapshot.readings.isEmpty())
    }

    @Test
    fun `connecting immediately supplies all six synthetic metrics`() {
        val source = DemoTelemetrySource()

        val snapshot = source.connect()

        assertEquals(snapshot, source.snapshot)
        assertEquals(ConnectionState.CONNECTED, snapshot.connection)
        assertEquals(TelemetryMetric.entries.toSet(), snapshot.readings.map { it.metric }.toSet())
        assertEquals(6, snapshot.readings.size)
        assertTrue(snapshot.readings.all { it.status == ReadingStatus.VALID && it.value != null })
        assertTrue(snapshot.readings.all { it.elapsedMs == 0L })
        val speed = snapshot.readings.single { it.metric == TelemetryMetric.SPEED }
        assertEquals(0.0, speed.value!!, 0.0)
        assertEquals(ReadingStatus.VALID, speed.status)
    }

    @Test
    fun `replaying the same commands produces identical snapshots`() {
        fun replay(): List<TelemetrySnapshot> {
            val source = DemoTelemetrySource()
            return listOf(
                source.connect(),
                source.advanceBy(25_750L),
                source.setScenario(SimulationScenario.TIMEOUT),
                source.advanceBy(),
                source.setScenario(SimulationScenario.MISSING_DATA),
                source.disconnect(),
                source.advanceBy(50_000L),
                source.connect(),
                source.setScenario(SimulationScenario.NORMAL),
                source.advanceBy(300_000L),
            )
        }

        val first = replay()
        val second = replay()

        assertEquals(first, second)
        assertTrue(first.all { it.origin == DataOrigin.SIMULATED })
        assertFalse(first.first().readings == first.last().readings)
    }

    @Test
    fun `values depend on elapsed time rather than polling frequency`() {
        val frequent = DemoTelemetrySource()
        val single = DemoTelemetrySource()
        frequent.connect()
        single.connect()

        repeat(45) { frequent.advanceBy() }
        single.advanceBy(45_000L)

        assertEquals(single.snapshot, frequent.snapshot)
        assertTrue(frequent.snapshot.readings.all { it.elapsedMs == 45_000L })
    }

    @Test
    fun `timeout replaces previous values with null and keeps the connection`() {
        val source = DemoTelemetrySource()
        source.connect()
        source.advanceBy(30_000L)

        val snapshot = source.setScenario(SimulationScenario.TIMEOUT)

        assertEquals(ConnectionState.CONNECTED, snapshot.connection)
        assertEquals(30_000L, snapshot.elapsedMs)
        assertEquals(6, snapshot.readings.size)
        snapshot.readings.forEach {
            assertEquals(ReadingStatus.TIMEOUT, it.status)
            assertNull(it.value)
        }
        assertTrue(source.advanceBy().readings.all { it.value == null })
    }

    @Test
    fun `missing temperatures are unavailable while other metrics remain valid`() {
        val source = DemoTelemetrySource()
        source.connect()

        val snapshot = source.setScenario(SimulationScenario.MISSING_DATA)
        val unavailable = snapshot.readings.filter { it.status == ReadingStatus.UNAVAILABLE }

        assertEquals(
            setOf(TelemetryMetric.COOLANT_TEMPERATURE, TelemetryMetric.INTAKE_TEMPERATURE),
            unavailable.map { it.metric }.toSet(),
        )
        assertTrue(unavailable.all { it.value == null })
        assertEquals(4, snapshot.readings.count { it.status == ReadingStatus.VALID && it.value != null })
        assertFalse(snapshot.readings.any { it.status == ReadingStatus.TIMEOUT })
    }

    @Test
    fun `normal scenario restores current values after errors without advancing time`() {
        val source = DemoTelemetrySource()
        source.connect()
        val expected = source.advanceBy(18_000L)
        source.setScenario(SimulationScenario.TIMEOUT)
        source.setScenario(SimulationScenario.MISSING_DATA)

        assertEquals(expected, source.setScenario(SimulationScenario.NORMAL))
    }

    @Test
    fun `disconnected clock does not advance and readings remain empty`() {
        val source = DemoTelemetrySource()
        val initial = source.snapshot

        assertEquals(initial, source.advanceBy(50_000L))
        source.connect()
        source.advanceBy(20_000L)
        val disconnected = source.disconnect()

        assertEquals(ConnectionState.DISCONNECTED, disconnected.connection)
        assertTrue(disconnected.readings.isEmpty())
        assertEquals(20_000L, disconnected.elapsedMs)
        assertEquals(disconnected, source.advanceBy(90_000L))
    }

    @Test
    fun `reconnect resumes the paused clock and preserves the selected scenario`() {
        val source = DemoTelemetrySource()
        source.connect()
        source.advanceBy(12_000L)
        val before = source.setScenario(SimulationScenario.MISSING_DATA)
        source.disconnect()
        source.advanceBy(30_000L)

        assertEquals(before, source.connect())
        assertEquals(13_000L, source.advanceBy().elapsedMs)
    }

    @Test
    fun `changing scenario while disconnected does not create readings`() {
        val source = DemoTelemetrySource()

        val disconnected = source.setScenario(SimulationScenario.TIMEOUT)

        assertEquals(ConnectionState.DISCONNECTED, disconnected.connection)
        assertTrue(disconnected.readings.isEmpty())
        assertEquals(0L, disconnected.elapsedMs)
        assertTrue(source.connect().readings.all { it.status == ReadingStatus.TIMEOUT })
    }

    @Test
    fun `reset clears state and reproduces the original connected snapshot`() {
        val source = DemoTelemetrySource()
        val initial = source.snapshot
        val initialConnected = source.connect()
        source.advanceBy(90_000L)
        source.setScenario(SimulationScenario.TIMEOUT)

        assertEquals(initial, source.reset())
        assertEquals(initialConnected, source.connect())
    }

    @Test
    fun `zero interval and repeated connect leave elapsed time and readings unchanged`() {
        val source = DemoTelemetrySource()
        source.connect()
        val expected = source.advanceBy(40_000L)

        assertEquals(expected, source.advanceBy(0L))
        assertEquals(expected, source.connect())
    }

    @Test
    fun `negative intervals are rejected without changing state even while disconnected`() {
        val source = DemoTelemetrySource()
        val initial = source.snapshot

        assertThrows(IllegalArgumentException::class.java) { source.advanceBy(-1L) }
        assertEquals(initial, source.snapshot)
        val connected = source.connect()
        assertThrows(IllegalArgumentException::class.java) { source.advanceBy(Long.MIN_VALUE) }
        assertEquals(connected, source.snapshot)
    }

    @Test
    fun `elapsed time cannot overflow or corrupt the previous snapshot`() {
        val source = DemoTelemetrySource()
        source.connect()
        val maximum = source.advanceBy(Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, maximum.elapsedMs)
        assertTrue(maximum.readings.all { it.value!!.isFinite() })
        assertThrows(IllegalArgumentException::class.java) { source.advanceBy() }
        assertEquals(maximum, source.snapshot)
    }

    @Test
    fun `normal demonstration values stay finite and within plausible display bounds`() {
        val source = DemoTelemetrySource()
        source.connect()

        repeat(600) {
            val snapshot = source.advanceBy()
            snapshot.readings.forEach { reading ->
                val value = reading.value!!
                assertTrue(value.isFinite())
                assertEquals(ReadingStatus.VALID, reading.status)
                val range = when (reading.metric) {
                    TelemetryMetric.ENGINE_RPM -> 600.0..9_000.0
                    TelemetryMetric.SPEED -> 0.0..130.0
                    TelemetryMetric.COOLANT_TEMPERATURE -> 20.0..110.0
                    TelemetryMetric.INTAKE_TEMPERATURE -> -10.0..65.0
                    TelemetryMetric.THROTTLE_POSITION,
                    TelemetryMetric.ENGINE_LOAD -> 0.0..100.0
                }
                assertTrue("${reading.metric} value $value is outside $range", value in range)
            }
        }
    }
}
