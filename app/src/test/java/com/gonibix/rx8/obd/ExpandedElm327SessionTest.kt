package com.gonibix.rx8.obd

import com.gonibix.rx8.telemetry.obd.ObdPid
import com.gonibix.rx8.telemetry.obd.ObdPollingGroup
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ExpandedElm327SessionTest {
    @Test fun oneOxygenRequestPublishesBothChannelsAtOneTimeWithoutRecordingPlaceholders() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100" -> reply(bitmapFrame("7E8", 0, setOf(0x14)))
                "0114" -> reply("7E8 04 41 14 64 80")
                else -> defaultReply(command)
            }
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent()
        val placeholders = snapshots.first { it.phase == ObdPhase.LIVE }.readings
        assertTrue(placeholders.all { !it.isRequestResult })
        val oxygen = snapshots.last().readings.filter { it.metric.pid == 0x14 }
        assertEquals(listOf(0.5, 0.0), oxygen.map { it.value })
        assertTrue(oxygen.all { it.isRequestResult && it.status == ObdReadingStatus.VALID })
        assertEquals(1, oxygen.map { it.observedAtEpochMillis }.distinct().size)
        assertEquals(1, connection.commands.count { it == "0114" })
        // Two channels represent one ECU reply, not two responses.
        assertEquals(1_000.0, snapshots.last().effectiveResponsesPerSecond, 0.0)
        job.cancel(); runCurrent()
    }

    @Test fun customScalingRemainsPerEcuAndMissingEcuReplyNeverBorrowsAValue() = runTest {
        val pids = setOf(0x0B, 0x24, 0x4F)
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100", "0120", "0140" -> reply(capabilities("7E8", command, pids) + "\r" + capabilities("7E9", command, pids))
                "014F" -> reply("7E8 06 41 4F 04 10 00 4D\r7E9 06 41 4F 00 00 00 00")
                "010B" -> reply("7E8 03 41 0B 7F\r7E9 03 41 0B 7F")
                "0124" -> reply("7E8 06 41 24 FF FF FF FF")
                else -> defaultReply(command)
            }
        }
        var latest = ObdSessionSnapshot()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { latest = it } }
        runCurrent(); advanceTimeBy(251L); runCurrent()
        fun reading(ecu: String, metric: ObdMetric) = latest.readings.single { it.ecuId == ecu && it.metric == metric }
        assertEquals(127.0 * 770 / 255, reading("7E8", ObdMetric.MANIFOLD_PRESSURE).value!!, 0.0000001)
        assertEquals(127.0, reading("7E9", ObdMetric.MANIFOLD_PRESSURE).value!!, 0.0)
        assertEquals(4.0, reading("7E8", ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO).value)
        assertEquals(16.0, reading("7E8", ObdMetric.WIDE_OXYGEN_SENSOR_1_VOLTAGE).value)
        assertNull(reading("7E9", ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO).value)
        assertEquals(ObdReadingStatus.NO_DATA, reading("7E9", ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO).status)
        assertTrue(connection.commands.indexOf("014F") < connection.commands.indexOf("0124"))
        job.cancel(); runCurrent()
    }

    @Test fun failedAdvertisedScalingLeavesDependentReadingsNullWhileOtherSensorsContinue() = runTest {
        val pids = setOf(0x0B, 0x0C, 0x4F)
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100", "0120", "0140" -> reply(capabilities("7E8", command, pids))
                "014F" -> reply("NO DATA")
                "010B" -> reply("7E8 03 41 0B 64")
                else -> defaultReply(command)
            }
        }
        var latest = ObdSessionSnapshot()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { latest = it } }
        runCurrent(); advanceTimeBy(1_001L); runCurrent()
        assertEquals(ObdPhase.LIVE, latest.phase)
        val pressure = latest.readings.single { it.metric == ObdMetric.MANIFOLD_PRESSURE }
        assertEquals(ObdReadingStatus.NO_DATA, pressure.status)
        assertTrue(pressure.isRequestResult)
        assertNull(pressure.value)
        assertEquals(2_000.0, latest.readings.single { it.metric == ObdMetric.ENGINE_RPM }.value)
        assertEquals(2, connection.commands.count { it == "014F" })
        assertEquals(0, connection.closeCalls)
        job.cancel(); runCurrent()
    }

    @Test fun massAirFlowUsesItsSeparateAdvertisedScalingConfigurationOnce() = runTest {
        val pids = setOf(0x0C, 0x10, 0x50)
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100", "0120", "0140" -> reply(capabilities("7E8", command, pids))
                "0150" -> reply("7E8 06 41 50 64 00 00 00")
                "0110" -> reply("7E8 04 41 10 FF FF")
                else -> defaultReply(command)
            }
        }
        var latest = ObdSessionSnapshot()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { latest = it } }
        runCurrent(); advanceTimeBy(2_001L); runCurrent()
        assertEquals(1_000.0, latest.readings.single { it.metric == ObdMetric.MASS_AIR_FLOW }.value)
        assertEquals(1, connection.commands.count { it == "0150" })
        assertFalse("014F" in connection.commands)
        job.cancel(); runCurrent()
    }

    @Test fun absentOptionalBankAndUnusedOxygenTrimDoNotBecomeZeroSamples() = runTest {
        val connection = FakeObdConnection { command, _ ->
            when (command) {
                "0100" -> reply(bitmapFrame("7E8", 0, setOf(0x06, 0x14)))
                "0106" -> reply("7E8 03 41 06 80")
                "0114" -> reply("7E8 04 41 14 64 FF")
                else -> defaultReply(command)
            }
        }
        var latest = ObdSessionSnapshot()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { latest = it } }
        runCurrent(); advanceTimeBy(251L); runCurrent()
        val bank3 = latest.readings.single { it.metric == ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_3 }
        assertEquals(ObdReadingStatus.UNSUPPORTED, bank3.status)
        assertNull(bank3.value)
        val trim = latest.readings.single { it.metric == ObdMetric.OXYGEN_SENSOR_1_FUEL_TRIM }
        assertEquals(ObdReadingStatus.NO_DATA, trim.status)
        assertNull(trim.value)
        assertEquals(0.5, latest.readings.single { it.metric == ObdMetric.OXYGEN_SENSOR_1_VOLTAGE }.value)
        job.cancel(); runCurrent()
    }

    @Test fun fullCatalogRemainsFairBoundedAndOnlyAdvertisedReadOnlyPidsAreQueried() = runTest {
        val pids = ObdPid.entries.map { it.pid }.toSet() + 0x51 + 0x68
        val connection = FakeObdConnection { command, _ ->
            when {
                command.startsWith("01") && command.drop(2).toInt(16) % 0x20 == 0 -> reply(capabilities("7E8", command, pids))
                command.startsWith("01") -> {
                    val parameter = ObdPid.entries.single { it.command == command }
                    val data = List(parameter.dataBytes) { 0 }
                    reply(frame("7E8", parameter.pid, data))
                }
                else -> defaultReply(command)
            }
        }
        var latest = ObdSessionSnapshot()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run { latest = it } }
        runCurrent(); advanceTimeBy(90_000L); runCurrent()
        val queries = connection.commands.filter { it.startsWith("01") }.map { it.drop(2).toInt(16) }
        assertTrue(ObdPid.entries.all { it.pid in queries })
        assertFalse(0x51 in queries)
        assertFalse(0x68 in queries)
        assertEquals(1, queries.count { it == 0x4F })
        assertEquals(1, queries.count { it == 0x50 })
        val rpmPositions = queries.withIndex().filter { it.value == 0x0C }.map { it.index }
        assertTrue(rpmPositions.size > 40)
        assertTrue(rpmPositions.zipWithNext().all { (a, b) -> b - a <= 6 })
        assertTrue(latest.readings.all { it.isRequestResult })
        assertEquals(0, connection.closeCalls)
        job.cancel(); runCurrent()
    }

    @Test fun slowMetricsHaveExplicitFreshnessAndClockTicksNeverRefreshTheirObservationTime() = runTest {
        val pids = setOf(0x0C, 0x46)
        val connection = FakeObdConnection { command, count ->
            when (command) {
                "0100", "0120", "0140" -> reply(capabilities("7E8", command, pids))
                "0146" -> reply("7E8 03 41 46 28")
                "010C" -> if (count >= 3) emptyList() else defaultReply(command)
                else -> defaultReply(command)
            }
        }
        val snapshots = mutableListOf<ObdSessionSnapshot>()
        val job = launch { Elm327Session(connection, SchedulerObdClock(testScheduler)).run(snapshots::add) }
        runCurrent(); advanceTimeBy(5_500L); runCurrent()
        val actual = snapshots.first { s -> s.readings.any { it.metric == ObdMetric.AMBIENT_TEMPERATURE && it.status == ObdReadingStatus.VALID } }
            .readings.single { it.metric == ObdMetric.AMBIENT_TEMPERATURE }
        assertEquals(500L, actual.elapsedMillis)
        val retained = snapshots.last().readings.single { it.metric == ObdMetric.AMBIENT_TEMPERATURE }
        assertEquals(ObdPhase.LIVE, snapshots.last().phase)
        assertEquals(ObdReadingStatus.VALID, retained.status)
        assertEquals(actual.observedAtEpochMillis, retained.observedAtEpochMillis)
        assertEquals(actual.elapsedMillis, retained.elapsedMillis)
        assertEquals(180_000L, ObdMetric.AMBIENT_TEMPERATURE.freshnessMillis)
        job.cancel(); runCurrent()
    }

    private fun capabilities(ecu: String, command: String, supported: Set<Int>): String {
        val base = command.drop(2).toInt(16)
        val page = supported.filter { it in base + 1..base + 32 }.toMutableSet()
        if (supported.any { it > base + 32 }) page += base + 32
        return bitmapFrame(ecu, base, page)
    }

    private fun frame(ecu: String, pid: Int, data: List<Int>) =
        ecu + " " + (listOf(data.size + 2, 0x41, pid) + data).joinToString(" ") { "%02X".format(Locale.ROOT, it) }
}

class ObdPollingScheduleTest {
    @Test fun slowCatalogCannotStarveFastOrPrimaryLanes() {
        val schedule = ObdPollingSchedule(ObdPid.entries)
        val selected = (0L until 600).map { schedule.next(it * 250)!! }
        assertTrue(ObdPid.entries.filter { it.pollingGroup != ObdPollingGroup.METADATA }.all { it in selected })
        val positions = selected.withIndex().filter { it.value == ObdPid.ENGINE_RPM }.map { it.index }
        assertTrue(positions.zipWithNext().all { (first, second) -> second - first == 6 })
        assertTrue(selected.count { it == ObdPid.ENGINE_RPM } > selected.count { it == ObdPid.MASS_AIR_FLOW })
        assertFalse(selected.any { it.pollingGroup == ObdPollingGroup.METADATA })
    }

    @Test fun repeatedFramedErrorsCoolDownOnlyThatPidAndLaterSuccessClearsPenalty() {
        val schedule = ObdPollingSchedule(listOf(ObdPid.ENGINE_RPM, ObdPid.SPEED))
        assertEquals(ObdPid.ENGINE_RPM, schedule.next(0))
        schedule.completed(ObdPid.ENGINE_RPM, 0, failed = true)
        repeat(10) { assertEquals(ObdPid.SPEED, schedule.next(4_999)) }
        val afterDeadline = listOf(schedule.next(5_000), schedule.next(5_000))
        assertTrue(ObdPid.ENGINE_RPM in afterDeadline)
        schedule.completed(ObdPid.ENGINE_RPM, 5_000, failed = false)
        assertTrue(ObdPid.ENGINE_RPM in listOf(schedule.next(5_000), schedule.next(5_000)))
    }

    @Test fun allCoolingDownReturnsNoCommandUntilDeadlineWithoutBusyQuerying() {
        val schedule = ObdPollingSchedule(listOf(ObdPid.ENGINE_RPM))
        schedule.completed(ObdPid.ENGINE_RPM, 100, failed = true)
        assertNull(schedule.next(5_099))
        assertEquals(ObdPid.ENGINE_RPM, schedule.next(5_100))
    }
}
