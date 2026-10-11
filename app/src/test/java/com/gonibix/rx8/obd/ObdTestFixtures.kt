package com.gonibix.rx8.obd

import com.gonibix.rx8.bluetooth.AdapterConnection
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler

internal data class TestFragment(val text: String, val delayMillis: Long = 0L)

/** Scripted fragments travel through the same read/write boundary as the real Bluetooth transport. */
internal class FakeObdConnection(
    private val script: (command: String, occurrence: Int) -> List<TestFragment>,
) : AdapterConnection {
    val commands = mutableListOf<String>()
    private val queued = ArrayDeque<TestFragment>()
    var closeCalls = 0
        private set

    override suspend fun write(bytes: ByteArray, timeoutMillis: Long) {
        check(closeCalls == 0) { "Write after close" }
        val text = bytes.toString(Charsets.US_ASCII)
        check(text.endsWith('\r')) { "Missing command terminator" }
        val command = text.dropLast(1)
        commands += command
        queued.addAll(script(command, commands.count { it == command }))
    }

    override suspend fun read(timeoutMillis: Long): ByteArray {
        check(closeCalls == 0) { "Read after close" }
        if (queued.isEmpty()) awaitCancellation()
        val next = queued.removeFirst()
        delay(next.delayMillis)
        return next.text.toByteArray(Charsets.US_ASCII)
    }

    override fun close() {
        closeCalls++
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SchedulerObdClock(private val scheduler: TestCoroutineScheduler) : ObdClock {
    override fun epochMillis(): Long = 1_700_000_000_000L + scheduler.currentTime
    override fun monotonicMillis(): Long = scheduler.currentTime
}

internal fun reply(body: String, delayMillis: Long = 0L): List<TestFragment> = listOf(TestFragment("$body\r>", delayMillis))

internal fun defaultReply(command: String, pids: Set<Int> = ObdMetric.entries.map { it.pid }.toSet()): List<TestFragment> = when (command) {
    "ATZ" -> reply("ATZ\rELM327 v1.5")
    "ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATCAF1", "ATSP0" -> reply("$command\rOK")
    "ATDPN" -> reply("A6")
    "0100" -> reply(bitmapFrame("7E8", 0, pids))
    "010C" -> reply("7E8 04 41 0C 1F 40")
    "010D" -> reply("7E8 03 41 0D 28")
    "0105" -> reply("7E8 03 41 05 7B")
    "010F" -> reply("7E8 03 41 0F 50")
    "0111" -> reply("7E8 03 41 11 80")
    "0104" -> reply("7E8 03 41 04 40")
    else -> error("Unexpected command in test: $command")
}

internal fun bitmapFrame(ecu: String, base: Int, pids: Set<Int>): String {
    var bitmap = 0L
    pids.forEach { pid ->
        require(pid in base + 1..base + 32)
        bitmap = bitmap or (1L shl (32 - (pid - base)))
    }
    val data = "%08X".format(Locale.ROOT, bitmap).chunked(2).joinToString(" ")
    return "$ecu 06 41 %02X $data".format(Locale.ROOT, base)
}
