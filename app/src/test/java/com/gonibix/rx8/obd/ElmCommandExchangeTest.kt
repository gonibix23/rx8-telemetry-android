package com.gonibix.rx8.obd

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
class ElmCommandExchangeTest {
    @Test
    fun `fragmented echo body and prompt are assembled without adding bytes`() = runTest {
        val connection = FakeObdConnection { _, _ -> listOf(TestFragment("010"), TestFragment("C\r7E8 04 41 0C 1F"), TestFragment(" 40\r"), TestFragment(">")) }
        val exchange = ElmCommandExchange(connection)

        assertEquals("010C\r7E8 04 41 0C 1F 40\r", exchange.execute("010C"))
        assertEquals(listOf("010C"), connection.commands)
        assertEquals(0, connection.closeCalls)
    }

    @Test
    fun `only reset accepts leading idle prompts`() = runTest {
        val connection = FakeObdConnection { _, _ -> listOf(TestFragment("\r>\r>ATZ\rELM327 v1.5\r>")) }
        assertEquals("ATZ\rELM327 v1.5\r", ElmCommandExchange(connection).execute("ATZ"))

        val empty = FakeObdConnection { _, _ -> reply("") }
        assertEquals("\r", ElmCommandExchange(empty).execute("ATE0"))
    }

    @Test
    fun `extra nonempty prompt body invalidates link before next command`() = runTest {
        val connection = FakeObdConnection { _, _ -> listOf(TestFragment("OK\r>7E8 03 41 0D 28\r>")) }
        val exchange = ElmCommandExchange(connection)
        val failure = runCatching { exchange.execute("ATE0") }.exceptionOrNull()

        assertTrue(failure is ObdExchangeException)
        assertEquals(ObdReadingStatus.PARSE_ERROR, (failure as ObdExchangeException).status)
        assertEquals(1, connection.closeCalls)
        assertTrue(runCatching { exchange.execute("010C") }.isFailure)
        assertEquals(listOf("ATE0"), connection.commands)
    }

    @Test
    fun `whole command deadline includes all fragments and closes once`() = runTest {
        val connection = FakeObdConnection { _, _ -> List(20) { TestFragment(" ", 400L) } }
        val result = runCatching { ElmCommandExchange(connection).execute("0100", 2_000L) }

        assertEquals(2_000L, testScheduler.currentTime)
        assertEquals(ObdReadingStatus.TIMEOUT, (result.exceptionOrNull() as ObdExchangeException).status)
        assertEquals(1, connection.closeCalls)
        assertEquals(listOf("0100"), connection.commands)
    }

    @Test
    fun `cancellation stops a pending read and closes the stream`() = runTest {
        val connection = FakeObdConnection { _, _ -> emptyList() }
        val operation = launch { ElmCommandExchange(connection).execute("0100", 20_000L) }
        runCurrent()
        operation.cancel()
        runCurrent()

        assertTrue(operation.isCancelled)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `response limit and binary control bytes are rejected`() = runTest {
        for (body in listOf("A".repeat(8_193), "OK\u0000")) {
            val connection = FakeObdConnection { _, _ -> reply(body) }
            val failure = runCatching { ElmCommandExchange(connection).execute("ATE0") }.exceptionOrNull()
            assertEquals(ObdReadingStatus.PARSE_ERROR, (failure as ObdExchangeException).status)
            assertEquals(1, connection.closeCalls)
        }
    }

    @Test
    fun `unapproved commands never reach the adapter`() = runTest {
        val connection = FakeObdConnection { _, _ -> error("Must not write") }
        val exchange = ElmCommandExchange(connection)
        for (command in listOf("04", "0902", "ATSH7E0", "010C\r04", "", "010C\n", "ATD")) {
            assertTrue(runCatching { exchange.execute(command) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(connection.commands.isEmpty())
        assertEquals(0, connection.closeCalls)
    }

    @Test
    fun `commands serialize through complete responses`() = runTest {
        val connection = FakeObdConnection { command, _ -> reply(command, 100L) }
        val exchange = ElmCommandExchange(connection)
        val rpm = async { exchange.execute("010C") }
        val speed = async { exchange.execute("010D") }
        runCurrent()
        assertEquals(listOf("010C"), connection.commands)

        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf("010C", "010D"), connection.commands)
        assertTrue(rpm.isCompleted)
        assertFalse(speed.isCompleted)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("010C\r", rpm.await())
        assertEquals("010D\r", speed.await())
    }
}
