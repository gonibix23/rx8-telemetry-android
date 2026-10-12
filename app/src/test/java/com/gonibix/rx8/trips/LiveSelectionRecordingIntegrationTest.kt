package com.gonibix.rx8.trips

import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.connection.BluetoothConnectionPhase
import com.gonibix.rx8.connection.BluetoothSessionController
import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.dashboard.DashboardCardSize
import com.gonibix.rx8.dashboard.DashboardCardStyle
import com.gonibix.rx8.dashboard.DashboardFavoritesStorage
import com.gonibix.rx8.dashboard.DashboardFavoritesStore
import com.gonibix.rx8.dashboard.modularDashboardModel
import com.gonibix.rx8.obd.Elm327Session
import com.gonibix.rx8.obd.FakeObdConnection
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.obd.SchedulerObdClock
import com.gonibix.rx8.obd.bitmapFrame
import com.gonibix.rx8.obd.defaultReply
import com.gonibix.rx8.obd.reply
import com.gonibix.rx8.vehicle.VehicleProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real acquisition/controller/recorder and preference boundaries; only transport and disk are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSelectionRecordingIntegrationTest {
    @Test fun hiddenAndCompoundReadingsKeepRecordingAcrossLayoutChangesUntilRecordingAloneStops() = runTest {
        val clock = SchedulerObdClock(testScheduler)
        val repository = CapturingRepository()
        val recorder = TripRecorder(repository, backgroundScope, clock)
        val layoutStorage = LayoutStorage()
        val preferences = DashboardFavoritesStore(layoutStorage, backgroundScope)
        val vehicle = VehicleProfile("00000000-0000-4000-8000-000000000001", "Synthetic recording fixture", null, null, null, 0)
        val device = AdapterDevice("02:00:00:00:00:01", "Synthetic adapter")
        val supported = setOf(0x06, 0x0C, 0x0D, 0x14)
        val transport = FakeObdConnection { command, _ ->
            when (command) {
                "0100" -> reply(bitmapFrame("7E8", 0, supported) + "\r" + bitmapFrame("7E9", 0, supported))
                "010C" -> reply("7E8 04 41 0C 1F 40\r7E9 04 41 0C 0F A0")
                "010D" -> reply("7E8 03 41 0D 00\r7E9 03 41 0D 0A")
                // The absent optional bank is unsupported, not an artificial zero sample.
                "0106" -> reply("7E8 03 41 06 80\r7E9 03 41 06 78")
                // One request returns voltage and trim per ECU; FF is an unavailable trim value.
                "0114" -> reply("7E8 04 41 14 64 FF\r7E9 04 41 14 32 80")
                else -> defaultReply(command)
            }
        }
        val gateway = object : BluetoothGateway {
            override fun availability() = BluetoothAvailability.READY
            override fun pairedDevices() = listOf(device)
            override suspend fun connect(device: AdapterDevice) = transport
        }
        val controller = BluetoothSessionController(
            gateway, backgroundScope, StandardTestDispatcher(testScheduler),
            obdSessionFactory = ObdSessionFactory { Elm327Session(it, clock) },
            onObdSnapshot = recorder::onSnapshot,
            onSessionEnded = { recorder.stop(completed = it) },
            autoStartObd = true,
            onConnected = { assertTrue(recorder.startConnected(vehicle, clock.epochMillis(), clock.monotonicMillis())) },
        )
        try {
            runCurrent()
            controller.setForeground(true)
            controller.selectDevice(device)
            controller.connect()
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(ObdPhase.LIVE, controller.uiState.value.obd.phase)
            val initialView = modularDashboardModel(controller.uiState.value.obd, preferences.cards.value)
            assertEquals(2, initialView.ecus.size)
            assertTrue(initialView.ecus.all { it.readings.single().metric == ObdMetric.SPEED })
            assertRecordedOutcomes(repository.saved, transport.commands)

            val savedBeforeEditing = repository.saved.toList()
            val commandsBeforeEditing = transport.commands.toList()
            preferences.updateCard(DashboardCardConfig(ObdMetric.SPEED, DashboardCardStyle.BAR, DashboardCardSize.LARGE, true))
            preferences.toggleFavorite(ObdMetric.ENGINE_RPM)
            preferences.moveCard(ObdMetric.ENGINE_RPM, -1)
            runCurrent()
            assertEquals(listOf(ObdMetric.ENGINE_RPM, ObdMetric.SPEED), preferences.cards.value.map { it.metric })
            assertTrue(preferences.cards.value.last().showInOverlay)
            assertEquals(preferences.cards.value, layoutStorage.saved)
            assertEquals(commandsBeforeEditing, transport.commands)
            assertEquals(savedBeforeEditing, repository.saved)

            preferences.toggleFavorite(ObdMetric.SPEED)
            preferences.toggleFavorite(ObdMetric.ENGINE_RPM)
            preferences.toggleFavorite(ObdMetric.COOLANT_TEMPERATURE)
            runCurrent()
            val unsupportedView = modularDashboardModel(controller.uiState.value.obd, preferences.cards.value)
            assertTrue(unsupportedView.ecus.all { ecu ->
                ecu.readings.single().let { it.metric == ObdMetric.COOLANT_TEMPERATURE &&
                    it.status == ObdReadingStatus.UNSUPPORTED && it.value == null }
            })
            val hiddenStart = repository.saved.size
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(expectedMetrics, repository.saved.drop(hiddenStart).map { it.metric }.toSet())
            assertEquals(setOf("7E8", "7E9"), repository.saved.drop(hiddenStart).map { it.ecuId }.toSet())
            assertRecordedOutcomes(repository.saved, transport.commands)

            preferences.toggleFavorite(ObdMetric.COOLANT_TEMPERATURE)
            runCurrent()
            assertTrue(preferences.cards.value.isEmpty())
            assertTrue(modularDashboardModel(controller.uiState.value.obd, preferences.cards.value).ecus.all { it.readings.isEmpty() })
            val emptyViewStart = repository.saved.size
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(repository.saved.size > emptyViewStart)
            assertRecordedOutcomes(repository.saved, transport.commands)

            recorder.stop()
            runCurrent()
            assertEquals(TripStatus.COMPLETED, repository.session?.status)
            val finishedSamples = repository.saved.toList()
            val finishedCommands = transport.commands.size
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(finishedSamples, repository.saved)
            assertTrue(transport.commands.size > finishedCommands)
            assertEquals(BluetoothConnectionPhase.CONNECTED, controller.uiState.value.phase)
            assertEquals(ObdPhase.LIVE, controller.uiState.value.obd.phase)
            assertEquals(0, transport.closeCalls)
        } finally {
            controller.close()
            runCurrent()
        }
    }

    private fun assertRecordedOutcomes(samples: List<TripSample>, commands: List<String>) {
        val requests = mapOf(
            ObdMetric.ENGINE_RPM to "010C", ObdMetric.SPEED to "010D",
            ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_1 to "0106",
            ObdMetric.OXYGEN_SENSOR_1_VOLTAGE to "0114", ObdMetric.OXYGEN_SENSOR_1_FUEL_TRIM to "0114",
        )
        assertEquals(expectedMetrics, samples.map { it.metric }.toSet())
        // Every completed request has exactly one persisted outcome per implemented available field/ECU.
        // Engine ticker snapshots and cached readings would inflate these counts if accidentally recorded.
        requests.forEach { (metric, command) ->
            listOf("7E8", "7E9").forEach { ecu ->
                assertEquals("$metric / $ecu", commands.count { it == command }, samples.count { it.metric == metric && it.ecuId == ecu })
            }
        }
        assertEquals(samples.indices.map(Int::toLong), samples.map { it.sequence })
        assertEquals(samples.size, samples.map { Triple(it.ecuId, it.metric, it.observedAtEpochMillis) }.distinct().size)
        assertTrue(samples.none { it.quality == ObdReadingStatus.STALE || it.quality == ObdReadingStatus.UNSUPPORTED })
        assertTrue(samples.all { it.unit == it.metric.unit })
        val unavailable = samples.filter { it.metric == ObdMetric.OXYGEN_SENSOR_1_FUEL_TRIM && it.ecuId == "7E8" }
        assertTrue(unavailable.isNotEmpty())
        assertTrue(unavailable.all { it.quality == ObdReadingStatus.NO_DATA && it.value == null })
        assertTrue(samples.filterNot { it in unavailable }.all { it.quality == ObdReadingStatus.VALID && it.value != null })
        assertTrue(samples.filter { it.metric == ObdMetric.SPEED && it.ecuId == "7E8" }.all { it.value == 0.0 })
        assertTrue(samples.filter { it.metric == ObdMetric.SPEED && it.ecuId == "7E9" }.all { it.value == 10.0 })
    }

    private val expectedMetrics = setOf(ObdMetric.ENGINE_RPM, ObdMetric.SPEED,
        ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_1, ObdMetric.OXYGEN_SENSOR_1_VOLTAGE, ObdMetric.OXYGEN_SENSOR_1_FUEL_TRIM)

    private class LayoutStorage : DashboardFavoritesStorage {
        var saved = listOf(DashboardCardConfig(ObdMetric.SPEED))
        override suspend fun read() = emptySet<String>()
        override suspend fun write(ids: Set<String>) = error("This fixture must persist the complete layout")
        override suspend fun readCards() = saved
        override suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean { saved = cards.toList(); return true }
    }

    /** Captures the real recorder's ordered repository writes; Room durability has separate tests. */
    private class CapturingRepository : TripRepository {
        var session: TripSession? = null
        val saved = mutableListOf<TripSample>()
        override suspend fun recoverInterrupted() = Unit
        override suspend fun create(session: TripSession) { this.session = session }
        override suspend fun updateProtocol(tripId: String, protocolLabel: String) {
            session = requireNotNull(session).copy(protocolLabel = protocolLabel)
        }
        override suspend fun append(tripId: String, samples: List<TripSample>) {
            check(session?.id == tripId && session?.status == TripStatus.RECORDING)
            saved += samples
        }
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) {
            session = requireNotNull(session).copy(endedAtEpochMillis = endedAtEpochMillis, durationMillis = durationMillis, status = status, endReason = reason)
        }
        override suspend fun list(limit: Int) = listOfNotNull(session).take(limit)
        override suspend fun historyPage(request: TripHistoryRequest) = TripHistoryPage(list(request.limit))
        override suspend fun find(tripId: String) = session?.takeIf { it.id == tripId }
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = saved.filter { it.tripId == tripId && it.sequence > afterSequence }.take(limit)
        override suspend fun delete(tripId: String) = error("Deletion is outside this regression")
        override suspend fun storageBytes() = 0L
    }
}
