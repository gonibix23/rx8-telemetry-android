package com.gonibix.rx8.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Elm327SessionTest {
    @Test
    fun `initialization discovers protocol before publishing real readings`() = runTest {
        val connection = FakeObdConnection { command, _ -> defaultReply(command) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()

        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATCAF1", "ATSP0", "0100", "ATDPN"), connection.commands.take(10))
        assertTrue(snapshots.any { it.phase == ObdPhase.INITIALIZING })
        assertTrue(snapshots.any { it.phase == ObdPhase.DISCOVERING })
        val live = snapshots.last()
        assertEquals(ObdPhase.LIVE, live.phase)
        assertEquals("ELM327 V1.5", live.adapterIdentity)
        assertTrue(live.protocolLabel!!.contains("CAN"))
        assertEquals(6, live.supportedPidCount)
        val rpm = live.readings.single { it.metric == ObdMetric.ENGINE_RPM }
        assertEquals(2_000.0, rpm.value!!, 0.0)
        assertEquals(ObdReadingStatus.VALID, rpm.status)
        assertEquals(1_700_000_000_000L, rpm.observedAtEpochMillis)
        assertEquals(0L, rpm.elapsedMillis)
        job.cancel()
        runCurrent()
        assertEquals(ObdPhase.STOPPED, snapshots.last().phase)
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `non-OK adapter setting stops before any vehicle query`() = runTest {
        val connection = FakeObdConnection { command, _ -> if (command == "ATH1") reply("?") else defaultReply(command) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val failure = runCatching { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }.exceptionOrNull()

        assertTrue(failure is ObdSessionException)
        assertTrue((failure as ObdSessionException).linkClosed)
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertFalse(connection.commands.any { it.startsWith("01") })
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `reset requires an identifiable banner rather than an old OK`() = runTest {
        for (body in listOf("OK", "Hello", "ELM327 V1.5\rUnexpected text")) {
            val connection = FakeObdConnection { _, _ -> reply(body) }
            val snapshots = mutableListOf<ObdSessionSnapshot>()
            assertTrue(runCatching { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }.exceptionOrNull() is ObdSessionException)
            assertEquals(listOf("ATZ"), connection.commands)
            assertEquals(ObdPhase.ERROR, snapshots.last().phase)
            assertEquals(1, connection.closeCalls)
        }
    }

    @Test
    fun `ECU readings and unsupported capabilities remain distinct`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100" -> reply(bitmapFrame("7E8", 0, setOf(0x0c, 0x0d)) + "\r" + bitmapFrame("7E9", 0, setOf(0x0c)))
                "010C" -> reply("7E8 04 41 0C 1F 40\r7E9 04 41 0C 0F A0")
                else -> defaultReply(command)
            }
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()

        val live = snapshots.last()
        assertEquals(listOf("7E8", "7E9"), live.ecus.map { it.ecuId })
        assertEquals(2, live.supportedPidCount)
        assertEquals(listOf(2_000.0, 1_000.0), live.readings.filter { it.metric == ObdMetric.ENGINE_RPM }.map { it.value })
        val unavailable = live.readings.single { it.ecuId == "7E9" && it.metric == ObdMetric.SPEED }
        assertEquals(ObdReadingStatus.UNSUPPORTED, unavailable.status)
        assertNull(unavailable.value)
        assertFalse(connection.commands.any { it in setOf("0104", "0105", "010F", "0111") })
        assertTrue(live.effectiveResponsesPerSecond > 0.0)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `continuation discovery is bounded and ignores ECUs without page-zero evidence`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100" -> reply(bitmapFrame("7E8", 0, setOf(0x0c, 0x20)))
                "0120" -> reply(bitmapFrame("7E8", 0x20, setOf(0x21, 0x40)) + "\r" + bitmapFrame("7E9", 0x20, setOf(0x22, 0x40)))
                "0140" -> reply(bitmapFrame("7E8", 0x40, setOf(0x42)))
                else -> defaultReply(command)
            }
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()

        val live = snapshots.last()
        assertEquals(ObdPhase.LIVE, live.phase)
        assertEquals(listOf("7E8"), live.ecus.map { it.ecuId })
        assertEquals(setOf(0x0c, 0x21, 0x42), live.ecus.single().supportedPids)
        assertEquals(3, live.supportedPidCount)
        assertTrue("0120" in connection.commands)
        assertTrue("0140" in connection.commands)
        assertFalse("0160" in connection.commands)
        assertFalse(live.readings.any { it.ecuId == "7E9" })
        job.cancel()
        runCurrent()
    }

    @Test
    fun `auto detection allows twenty seconds then closes without a retry`() = runTest {
        val connection = FakeObdConnection { command, _ -> if (command == "0100") emptyList() else defaultReply(command) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val failure = runCatching { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }.exceptionOrNull()

        assertTrue(failure is ObdSessionException)
        assertEquals(20_000L, testScheduler.currentTime)
        assertEquals(1, connection.commands.count { it == "0100" })
        assertFalse("ATDPN" in connection.commands)
        assertFalse(snapshots.any { it.phase == ObdPhase.LIVE })
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `framed no-data has one delayed retry and can recover`() = runTest {
        val connection = FakeObdConnection { command, occurrence ->
            if (command == "010C" && occurrence == 1) reply("NO DATA") else defaultReply(command, setOf(0x0c))
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        assertEquals(1, connection.commands.count { it == "010C" })
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(2, connection.commands.count { it == "010C" })
        assertEquals(ObdReadingStatus.VALID, snapshots.last().readings.single { it.metric == ObdMetric.ENGINE_RPM }.status)
        assertEquals(0, connection.closeCalls)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `repeated framed no-data becomes null and backs off after one retry`() = runTest {
        val connection = FakeObdConnection { command, _ -> if (command == "010C") reply("NO DATA") else defaultReply(command, setOf(0x0c)) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()

        val failed = snapshots.last().readings.single { it.metric == ObdMetric.ENGINE_RPM }
        assertEquals(2, connection.commands.count { it == "010C" })
        assertEquals(ObdReadingStatus.NO_DATA, failed.status)
        assertNull(failed.value)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, connection.commands.count { it == "010C" })
        job.cancel()
        runCurrent()
    }

    @Test
    fun `malformed and negative vehicle replies never become zero or retry immediately`() = runTest {
        for ((body, expected) in listOf("7E8 03 41 0C 1F" to ObdReadingStatus.PARSE_ERROR, "7E8 03 7F 01 12" to ObdReadingStatus.ADAPTER_ERROR)) {
            val connection = FakeObdConnection { command, _ -> if (command == "010C") reply(body) else defaultReply(command, setOf(0x0c)) }
            val snapshots = mutableListOf<ObdSessionSnapshot>()
            val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
            runCurrent()
            val failed = snapshots.last().readings.single { it.metric == ObdMetric.ENGINE_RPM }
            assertEquals(expected, failed.status)
            assertNull(failed.value)
            assertEquals(1, connection.commands.count { it == "010C" })
            assertEquals(0, connection.closeCalls)
            job.cancel()
            runCurrent()
        }
    }

    @Test
    fun `partial ECU responses never copy a different ECUs value`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            if (command == "0100") reply(bitmapFrame("7E8", 0, setOf(0x0c)) + "\r" + bitmapFrame("7E9", 0, setOf(0x0c)))
            else defaultReply(command, setOf(0x0c))
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()

        val readings = snapshots.last().readings.filter { it.metric == ObdMetric.ENGINE_RPM }
        assertEquals(2_000.0, readings.single { it.ecuId == "7E8" }.value!!, 0.0)
        val missing = readings.single { it.ecuId == "7E9" }
        assertEquals(ObdReadingStatus.NO_DATA, missing.status)
        assertNull(missing.value)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `stalled command updates stale age before terminal timeout closes the link`() = runTest {
        val connection = FakeObdConnection { command, _ -> if (command == "010D") emptyList() else defaultReply(command, setOf(0x0c, 0x0d)) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val result = async { runCatching { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) } }
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        val waiting = snapshots.last()
        val oldRpm = waiting.readings.single { it.metric == ObdMetric.ENGINE_RPM }
        assertEquals(ObdPhase.LIVE, waiting.phase)
        assertEquals(5_000L, waiting.elapsedMillis)
        assertEquals(ObdReadingStatus.STALE, oldRpm.status)
        assertEquals(2_000.0, oldRpm.value!!, 0.0)
        assertEquals(0L, oldRpm.elapsedMillis)
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is ObdSessionException)
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertEquals(ObdReadingStatus.TIMEOUT, snapshots.last().readings.single { it.metric == ObdMetric.SPEED }.status)
        assertEquals(1, connection.commands.count { it == "010D" })
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `weighted polling prioritizes speed and rpm while slower sensors remain sampled`() = runTest {
        val connection = FakeObdConnection { command, _ -> defaultReply(command) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        advanceTimeBy(2_251L)
        runCurrent()

        val polling = connection.commands.filter { it.startsWith("01") && it != "0100" }
        assertEquals(listOf("010C", "010D", "0105", "010C", "010D", "010F", "010C", "010D", "0111", "0104"), polling.take(10))
        assertTrue(snapshots.last().readings.all { it.status == ObdReadingStatus.VALID })
        job.cancel()
        runCurrent()
    }

    @Test
    fun `slow responses increase pause instead of promising a fixed polling rate`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            if (command == "010C") reply("7E8 04 41 0C 1F 40", delayMillis = 1_000L) else defaultReply(command, setOf(0x0c, 0x0d))
        }
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { } }
        runCurrent()
        advanceTimeBy(1_499L)
        runCurrent()
        assertFalse("010D" in connection.commands)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue("010D" in connection.commands)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `verified ECU with no supported dashboard parameters is not queried repeatedly`() = runTest {
        val connection = FakeObdConnection { command, _ -> defaultReply(command, emptySet()) }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertTrue(snapshots.last().readings.all { it.status == ObdReadingStatus.UNSUPPORTED && it.value == null })
        assertEquals(listOf("0100"), connection.commands.filter { it.startsWith("01") })
        job.cancel()
        runCurrent()
    }

    @Test
    fun `cancelling initialization closes promptly and reports stopped rather than error`() = runTest {
        val connection = FakeObdConnection { _, _ -> emptyList() }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(ObdPhase.STOPPED, snapshots.last().phase)
        assertFalse(snapshots.any { it.phase == ObdPhase.ERROR })
        assertEquals(1, connection.closeCalls)
    }
}
