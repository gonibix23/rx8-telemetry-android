package com.gonibix.rx8.connection

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSession
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.obd.ObdSessionSnapshot
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothSessionControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = AdapterDevice("02:00:00:00:00:01", "Synthetic adapter")
    private val gateway = ControllerGateway()
    private lateinit var ownerScope: CoroutineScope
    private lateinit var controller: BluetoothSessionController
    private var sampleCount = 0L
    private var sessionStarts = 0
    private var sessionCancelled = false
    private var vinRequests = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ownerScope = CoroutineScope(SupervisorJob() + dispatcher)
        controller = BluetoothSessionController(
            gateway = gateway,
            scope = ownerScope,
            dispatcher = dispatcher,
            obdSessionFactory = ObdSessionFactory {
                object : ObdSession {
                    override fun requestVin(): Boolean { vinRequests++; return true }

                    override suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit) {
                        sessionStarts++
                        try {
                            onSnapshot(sample())
                            while (true) {
                                delay(1_000L)
                                sampleCount++
                                onSnapshot(sample())
                            }
                        } finally {
                            sessionCancelled = true
                        }
                    }
                }
            },
        )
    }

    @After
    fun tearDown() {
        controller.close()
        ownerScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun automaticOpportunitySurvivesConflatedStopAndResumeWithoutRearmingUnchangedReadiness() = controllerTest {
        val observed = mutableListOf<Pair<Boolean, Long>>()
        val collector = ownerScope.launch {
            controller.uiState.map { it.automaticConnectionReady to it.automaticConnectionRequestId }
                .distinctUntilChanged().collect { observed += it }
        }
        runCurrent()
        controller.setAutomaticConnectionReady(true)
        runCurrent()
        assertEquals(listOf(false to 0L, true to 1L), observed)

        // Stop and Resume can complete between UI frames. The observer misses false, but must
        // still see a distinct eligible request instead of treating the final true as unchanged.
        controller.setAutomaticConnectionReady(false)
        controller.setAutomaticConnectionReady(true)
        runCurrent()
        assertEquals(listOf(false to 0L, true to 1L, true to 2L), observed)

        repeat(20) { controller.setAutomaticConnectionReady(true) }
        runCurrent()
        assertEquals(3, observed.size)
        assertEquals(2L, controller.uiState.value.automaticConnectionRequestId)
        controller.setAutomaticConnectionReady(false)
        controller.setAutomaticConnectionReady(false)
        runCurrent()
        assertEquals(false to 2L, observed.last())
        collector.cancel()
    }

    @Test
    fun closingTheControllerIsTerminalAndReleasesAnIdleLinkOnlyOnce() = controllerTest {
        connect()
        controller.close()
        controller.close()
        runCurrent()
        assertEquals(1, gateway.link.closeCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)

        controller.setForeground(true)
        controller.refresh()
        controller.selectDevice(device)
        controller.connect()
        controller.startObd()
        controller.startDiscovery()
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(0, gateway.scanCalls)
        assertEquals(0, sessionStarts)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
    }

    @Test
    fun cancellingTheOwnerScopeClosesAnIdleConnectedSocket() = controllerTest {
        connect()
        assertEquals(0, gateway.link.closeCalls)
        ownerScope.cancel()
        runCurrent()
        assertEquals(1, gateway.link.closeCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
        controller.setForeground(true)
        controller.connect()
        runCurrent()
        assertEquals(1, gateway.connectCalls)
    }

    @Test
    fun lateConnectionSuccessAfterCloseIsReleasedWithoutRevivingTheController() = controllerTest {
        lateinit var completion: Continuation<AdapterConnection>
        gateway.connectBehavior = { suspendCoroutine { completion = it } }
        controller.setForeground(true)
        controller.selectDevice(device)
        controller.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTING, controller.uiState.value.phase)
        controller.close()
        val late = ControllerConnection()
        completion.resume(late)
        runCurrent()
        assertEquals(1, late.closeCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
        assertEquals(0, sessionStarts)
    }

    @Test
    fun backgroundSamplesKeepAdvancingUntilExplicitControllerClose() = controllerTest {
        connect()
        controller.startObd()
        runCurrent()
        assertEquals(ObdPhase.LIVE, controller.uiState.value.obd.phase)
        controller.setForeground(false)
        advanceTimeBy(3_100L)
        runCurrent()

        assertEquals(3L, sampleCount)
        assertEquals(3_000L, controller.uiState.value.obd.elapsedMillis)
        assertEquals(ObdReadingStatus.VALID, controller.uiState.value.obd.readings.single().status)
        assertEquals(3.0, controller.uiState.value.obd.readings.single().value!!, 0.0)
        assertFalse(sessionCancelled)
        assertEquals(0, gateway.link.closeCalls)
        controller.readVin()
        assertEquals(0, vinRequests)
        controller.setForeground(true)
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(1, sessionStarts)

        controller.close()
        runCurrent()
        assertTrue(sessionCancelled)
        assertEquals(1, gateway.link.closeCalls)
        assertEquals(ObdPhase.STOPPED, controller.uiState.value.obd.phase)
        assertEquals(ObdReadingStatus.STALE, controller.uiState.value.obd.readings.single().status)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(3L, sampleCount)
    }

    @Test
    fun permissionLossInTheBackgroundStillClosesTheSessionWithoutRetry() = controllerTest {
        connect()
        controller.setForeground(false)
        gateway.availabilityValue = BluetoothAvailability.PERMISSION_REQUIRED
        controller.refresh()
        runCurrent()
        assertEquals(1, gateway.link.closeCalls)
        assertEquals(BluetoothAvailability.PERMISSION_REQUIRED, controller.uiState.value.availability)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
        gateway.availabilityValue = BluetoothAvailability.READY
        controller.setForeground(true)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
    }

    @Test
    fun closingTheControllerCancelsDiscoveryAndPreventsNewScans() = controllerTest {
        var scanCancelled = false
        gateway.scanBehavior = {
            flow {
                emit(device.copy(isPaired = false))
                try { awaitCancellation() } finally { scanCancelled = true }
            }
        }
        controller.setForeground(true)
        controller.startDiscovery()
        runCurrent()
        assertTrue(controller.uiState.value.isScanning)
        controller.close()
        runCurrent()
        assertTrue(scanCancelled)
        assertFalse(controller.uiState.value.isScanning)
        controller.setForeground(true)
        controller.startDiscovery()
        runCurrent()
        assertEquals(1, gateway.scanCalls)
    }

    private fun sample() = ObdSessionSnapshot(
        phase = ObdPhase.LIVE,
        readings = listOf(ObdReading(
            metric = ObdMetric.SPEED,
            value = sampleCount.toDouble(),
            status = ObdReadingStatus.VALID,
            ecuId = "7E8",
            observedAtEpochMillis = 123_000L + sampleCount * 1_000L,
            elapsedMillis = sampleCount * 1_000L,
        )),
        elapsedMillis = sampleCount * 1_000L,
    )

    private fun TestScope.connect() {
        controller.setForeground(true)
        controller.selectDevice(device)
        controller.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTED, controller.uiState.value.phase)
    }

    private fun controllerTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally {
            controller.close()
            ownerScope.cancel()
            runCurrent()
        }
    }

    private inner class ControllerGateway : BluetoothGateway {
        var availabilityValue = BluetoothAvailability.READY
        val link = ControllerConnection()
        var connectCalls = 0
        var scanCalls = 0
        var connectBehavior: suspend () -> AdapterConnection = { link }
        var scanBehavior: () -> Flow<AdapterDevice> = { emptyFlow() }
        override fun availability() = availabilityValue
        override fun pairedDevices() = listOf(device)
        override fun discover(): Flow<AdapterDevice> { scanCalls++; return scanBehavior() }
        override suspend fun connect(device: AdapterDevice): AdapterConnection { connectCalls++; return connectBehavior() }
    }

    private class ControllerConnection : AdapterConnection {
        var closeCalls = 0
        override suspend fun read(timeoutMillis: Long) = byteArrayOf()
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = Unit
        override fun close() { closeCalls++ }
    }
}
