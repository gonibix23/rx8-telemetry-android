package com.gonibix.rx8.obd

import com.gonibix.rx8.telemetry.obd.VinResponse
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElmVinSessionTest {
    // Deliberately synthetic; no real vehicle identity is used in tests.
    private val syntheticVin = "ABCDEFGHJKLMNPR12"
    private val canVinBody = listOf(
        "7E8 10 14 49 02 01 41 42 43",
        "7E8 21 44 45 46 47 48 4A 4B",
        "7E8 22 4C 4D 4E 50 52 31 32",
    ).joinToString("\r")

    @Test
    fun `normal initialization and polling never request VIN automatically`() = runTest {
        val connection = FakeObdConnection { command, _ -> defaultReply(command) }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        assertFalse(session.requestVin())
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        advanceTimeBy(2_501L)
        runCurrent()

        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertFalse(connection.commands.any { it == "0902" || it == "ATCFC1" })
        job.cancel()
        runCurrent()
        assertFalse(session.requestVin())
    }

    @Test
    fun `explicit VIN waits for the current fragmented sensor response and preserves serial order`() = runTest {
        val connection = FakeObdConnection { command, occurrence ->
            when {
                command == "010C" && occurrence == 1 -> listOf(
                    TestFragment("7E8 04 41 0C"),
                    TestFragment(" 1F 40\r>", delayMillis = 1_000L),
                )
                command == "ATCFC1" -> reply("OK")
                command == "0902" -> canVinBody.chunked(11).map { TestFragment(it) } + TestFragment("\r>")
                else -> defaultReply(command, setOf(0x0C, 0x0D))
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertEquals("010C", connection.commands.last())
        assertTrue(session.requestVin())
        runCurrent()
        assertFalse("ATCFC1" in connection.commands)
        advanceTimeBy(1_499L)
        runCurrent()
        assertFalse("0902" in connection.commands)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("010C", "ATCFC1", "0902", "010D"), connection.commands.takeLast(4))
        assertEquals(listOf(VinResponse("7E8", syntheticVin)), snapshots.last().vin.candidates)
        assertEquals(ObdVinStatus.AVAILABLE, snapshots.last().vin.status)
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertEquals(0, connection.closeCalls)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `duplicate clicks create one pending VIN request through completion`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> reply(canVinBody, delayMillis = 1_000L)
                else -> defaultReply(command)
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        repeat(5) { assertFalse(session.requestVin()) }
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1, connection.commands.count { it == "0902" })
        repeat(5) { assertFalse(session.requestVin()) }
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(ObdVinStatus.AVAILABLE, snapshots.last().vin.status)
        assertEquals(1, connection.commands.count { it == "0902" })
        assertEquals(1, connection.commands.count { it == "ATCFC1" })
        assertTrue(session.requestVin())
        assertFalse(session.requestVin())
        job.cancel()
        runCurrent()
        assertEquals(1, connection.commands.count { it == "0902" })
    }

    @Test
    fun `VIN NO DATA retries once after delay then resumes Mode 01 without reconnecting`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> reply("NO DATA")
                else -> defaultReply(command, setOf(0x0C, 0x0D))
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1, connection.commands.count { it == "0902" })
        advanceTimeBy(499L)
        runCurrent()
        assertEquals(1, connection.commands.count { it == "0902" })
        assertFalse("010D" in connection.commands)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("ATCFC1", "0902", "0902", "010D"), connection.commands.takeLast(4))
        assertEquals(ObdVinStatus.NO_DATA, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertEquals(0, connection.closeCalls)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, connection.commands.count { it == "0902" })
        assertTrue(connection.commands.count { it == "010C" } > 1)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `framed adapter VIN failure has bounded retries without stopping sensors`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> reply("CAN ERROR")
                else -> defaultReply(command, setOf(0x0C, 0x0D))
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(750L)
        runCurrent()

        assertEquals(2, connection.commands.count { it == "0902" })
        assertEquals(ObdVinStatus.ERROR, snapshots.last().vin.status)
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertTrue("010D" in connection.commands)
        assertEquals(0, connection.closeCalls)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `complete malformed and negative VIN replies are unavailable without an immediate retry`() = runTest {
        for ((body, expectedStatus) in listOf(
            "7E8 10 14 49 02 01 41 42 43" to ObdVinStatus.ERROR,
            "7E8 03 7F 09 12" to ObdVinStatus.NO_DATA,
            canVinBody.replace("31 32", "31 49") to ObdVinStatus.ERROR,
        )) {
            val connection = FakeObdConnection { command, _ ->
                when (command) {
                    "ATCFC1" -> reply("OK")
                    "0902" -> reply(body)
                    else -> defaultReply(command, setOf(0x0C, 0x0D))
                }
            }
            val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
            val snapshots = mutableListOf<ObdSessionSnapshot>()
            val job = launch { session.run(snapshots::add) }
            runCurrent()
            assertTrue(session.requestVin())
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(1, connection.commands.count { it == "0902" })
            assertEquals(expectedStatus, snapshots.last().vin.status)
            assertTrue(snapshots.last().vin.candidates.isEmpty())
            assertEquals(ObdPhase.LIVE, snapshots.last().phase)
            assertEquals("010D", connection.commands.last())
            assertEquals(0, connection.closeCalls)
            job.cancel()
            runCurrent()
        }
    }

    @Test
    fun `failed CAN flow control acknowledgement skips VIN but preserves sensor polling`() = runTest {
        for (body in listOf("?", "NO DATA", "OK\rUNEXPECTED", "")) {
            val connection = FakeObdConnection { command, _ ->
                if (command == "ATCFC1") reply(body) else defaultReply(command, setOf(0x0C, 0x0D))
            }
            val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
            val snapshots = mutableListOf<ObdSessionSnapshot>()
            val job = launch { session.run(snapshots::add) }
            runCurrent()
            assertTrue(session.requestVin())
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(1, connection.commands.count { it == "ATCFC1" })
            assertFalse("0902" in connection.commands)
            assertEquals(ObdVinStatus.ERROR, snapshots.last().vin.status)
            assertEquals(ObdPhase.LIVE, snapshots.last().phase)
            assertEquals("010D", connection.commands.last())
            assertEquals(0, connection.closeCalls)
            job.cancel()
            runCurrent()
        }
    }

    @Test
    fun `VIN is still available when no dashboard metric is supported`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> reply(canVinBody)
                else -> defaultReply(command, emptySet())
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()

        assertEquals(listOf("0100"), connection.commands.filter { it.startsWith("01") })
        assertEquals(listOf(VinResponse("7E8", syntheticVin)), snapshots.last().vin.candidates)
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertTrue(snapshots.last().readings.all { it.status == ObdReadingStatus.UNSUPPORTED })
        assertEquals(0, connection.closeCalls)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `legacy VIN requests do not send CAN flow control settings`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATDPN" -> reply("A3")
                "0100" -> reply("48 6B 10 41 00 00 00 00 00 04")
                "0902" -> reply(legacyVinBody())
                else -> defaultReply(command)
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()

        assertFalse("ATCFC1" in connection.commands)
        assertEquals(listOf(VinResponse("10", syntheticVin)), snapshots.last().vin.candidates)
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `missing VIN prompt times out once closes the link and discards previous candidates`() = runTest {
        val connection = FakeObdConnection { command, occurrence ->
            when {
                command == "ATCFC1" -> reply("OK")
                command == "0902" && occurrence == 1 -> reply(canVinBody)
                command == "0902" -> listOf(TestFragment(canVinBody), TestFragment("\r>", 6_000L))
                else -> defaultReply(command, setOf(0x0C, 0x0D))
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val result = async { runCatching { session.run(snapshots::add) } }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(ObdVinStatus.AVAILABLE, snapshots.last().vin.status)
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(ObdVinStatus.READING, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        advanceTimeBy(5_000L)
        runCurrent()

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is ObdSessionException)
        assertTrue((failure as ObdSessionException).linkClosed)
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertEquals(2, connection.commands.count { it == "0902" })
        assertEquals("0902", connection.commands.last())
        assertEquals(1, connection.closeCalls)
        assertFalse(session.requestVin())
        val commandCount = connection.commands.size
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(commandCount, connection.commands.size)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
    }

    @Test
    fun `missing CAN flow control prompt is terminal and never sends VIN`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            if (command == "ATCFC1") emptyList() else defaultReply(command)
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val result = async { runCatching { session.run(snapshots::add) } }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(5_250L)
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is ObdSessionException)
        assertFalse("0902" in connection.commands)
        assertEquals(1, connection.commands.count { it == "ATCFC1" })
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `cancelling a queued VIN before the active sensor completes sends no VIN query`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            if (command == "010C") reply("7E8 04 41 0C 1F 40", 1_000L) else defaultReply(command)
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        job.cancel()
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(job.isCancelled)
        assertFalse(connection.commands.any { it == "ATCFC1" || it == "0902" })
        assertEquals(ObdPhase.STOPPED, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertFalse(session.requestVin())
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `cancelling an in flight fragmented VIN discards its bytes without later publication`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> listOf(TestFragment(canVinBody.take(20)), TestFragment(canVinBody.drop(20) + "\r>", 1_000L))
                else -> defaultReply(command)
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(ObdVinStatus.READING, snapshots.last().vin.status)
        job.cancel()
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(1, connection.commands.count { it == "0902" })
        assertEquals(ObdPhase.STOPPED, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertFalse(snapshots.any { it.vin.status == ObdVinStatus.AVAILABLE })
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `stopping after a successful VIN clears it from the terminal snapshot`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> reply(canVinBody)
                else -> defaultReply(command)
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { session.run(snapshots::add) }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(ObdVinStatus.AVAILABLE, snapshots.last().vin.status)
        job.cancel()
        runCurrent()

        assertEquals(ObdPhase.STOPPED, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `transport loss during VIN request closes instead of publishing stale candidates`() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "ATCFC1" -> reply("OK")
                "0902" -> throw IOException("Synthetic disconnected transport")
                else -> defaultReply(command)
            }
        }
        val session = Elm327Session(connection, SchedulerObdClock(testScheduler))
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val result = async { runCatching { session.run(snapshots::add) } }
        runCurrent()
        assertTrue(session.requestVin())
        advanceTimeBy(250L)
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is ObdSessionException)
        assertEquals(ObdPhase.ERROR, snapshots.last().phase)
        assertEquals(ObdVinStatus.NOT_REQUESTED, snapshots.last().vin.status)
        assertTrue(snapshots.last().vin.candidates.isEmpty())
        assertFalse(snapshots.any { it.vin.status == ObdVinStatus.AVAILABLE })
        assertEquals("0902", connection.commands.last())
        assertEquals(1, connection.closeCalls)
    }

    private fun legacyVinBody(): String =
        (listOf(0, 0, 0) + syntheticVin.map(Char::code)).chunked(4).mapIndexed { index, characters ->
            val frame = listOf(0x48, 0x6B, 0x10, 0x49, 0x02, index + 1) + characters
            (frame + (frame.sum() and 0xFF)).joinToString(" ") { it.toString(16).padStart(2, '0').uppercase() }
        }.joinToString("\r")
}
