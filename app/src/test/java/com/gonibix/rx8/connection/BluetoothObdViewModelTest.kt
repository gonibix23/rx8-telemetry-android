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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class BluetoothObdViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = AdapterDevice("02:00:00:00:00:01", "Test OBDII")
    private val gateway = TestGateway()
    private var starts = 0
    private var cancelled = false
    private var emit: ((ObdSessionSnapshot) -> Unit)? = null
    private var failure: Exception? = null
    private lateinit var viewModel: BluetoothConnectionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = BluetoothConnectionViewModel(gateway, dispatcher, ObdSessionFactory {
            object : ObdSession {
                override suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit) {
                    starts++
                    emit = onSnapshot
                    failure?.let { throw it }
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        })
    }

    @After
    fun tearDown() {
        viewModel.disconnect()
        Dispatchers.resetMain()
    }

    @Test
    fun bluetoothConnectionDoesNotStartObdUntilRequested() = sessionTest {
        viewModel.startObd()
        runCurrent()
        assertEquals(0, starts)
        connect()
        assertEquals(0, starts)
        assertEquals(ObdPhase.IDLE, viewModel.uiState.value.obd.phase)
        viewModel.startObd()
        viewModel.startObd()
        runCurrent()
        assertEquals(1, starts)
        assertEquals(ObdPhase.INITIALIZING, viewModel.uiState.value.obd.phase)
    }

    @Test
    fun realReadingsRetainTheirSourceTimestampAndValidZero() = sessionTest {
        connectAndRead()
        emit!!(liveSnapshot())
        val reading = viewModel.uiState.value.obd.readings.single()
        assertEquals(0.0, reading.value!!, 0.0)
        assertEquals("7E8", reading.ecuId)
        assertEquals(123_456L, reading.observedAtEpochMillis)
        assertEquals(ObdReadingStatus.VALID, reading.status)
    }

    @Test
    fun backgroundClosesTheLinkAndMarksCachedValuesStale() = sessionTest {
        connectAndRead()
        emit!!(liveSnapshot())
        viewModel.setForeground(false)
        runCurrent()
        assertTrue(cancelled)
        assertTrue(gateway.link.closed)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(ObdPhase.STOPPED, viewModel.uiState.value.obd.phase)
        assertEquals(ObdReadingStatus.STALE, viewModel.uiState.value.obd.readings.single().status)
        viewModel.setForeground(true)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun oldSessionUpdatesCannotOverwriteAReconnectedAdapter() = sessionTest {
        connectAndRead()
        val staleObserver = emit!!
        viewModel.disconnect()
        runCurrent()
        gateway.link = TestConnection()
        connect()
        staleObserver(liveSnapshot())
        assertEquals(ObdPhase.IDLE, viewModel.uiState.value.obd.phase)
        assertTrue(viewModel.uiState.value.obd.readings.isEmpty())
        assertFalse(gateway.link.closed)
    }

    @Test
    fun adapterFailureRequiresManualReconnectAndNeverRestartsItself() = sessionTest {
        failure = IOException("Synthetic transport failure")
        connectAndRead()
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(ObdPhase.ERROR, viewModel.uiState.value.obd.phase)
        assertTrue(gateway.link.closed)
        viewModel.startObd()
        runCurrent()
        assertEquals(1, starts)
        assertEquals(1, gateway.connects)
    }

    @Test
    fun removingTheActiveDeviceCannotLeaveLiveReadingsBehind() = sessionTest {
        connectAndRead()
        emit!!(liveSnapshot())
        gateway.devices = emptyList()
        viewModel.refresh()
        runCurrent()
        assertEquals(ObdPhase.STOPPED, viewModel.uiState.value.obd.phase)
        assertEquals(ObdReadingStatus.STALE, viewModel.uiState.value.obd.readings.single().status)
        assertTrue(gateway.link.closed)
    }

    @Test
    fun selectingAnotherAdapterClearsThePreviousCarsReadings() = sessionTest {
        connectAndRead()
        emit!!(liveSnapshot())
        viewModel.disconnect()
        val other = device.copy(address = "02:00:00:00:00:02")
        gateway.devices = listOf(device, other)
        viewModel.refresh()
        viewModel.selectDevice(other)
        assertTrue(viewModel.uiState.value.obd.readings.isEmpty())
        assertEquals(ObdPhase.IDLE, viewModel.uiState.value.obd.phase)
    }

    private fun liveSnapshot() = ObdSessionSnapshot(
        phase = ObdPhase.LIVE,
        readings = listOf(ObdReading(ObdMetric.SPEED, 0.0, ObdReadingStatus.VALID, "7E8", 123_456L, 1_000L)),
        elapsedMillis = 1_000L,
    )

    private fun TestScope.connect() {
        viewModel.setForeground(true)
        viewModel.selectDevice(device)
        viewModel.connect()
        runCurrent()
    }

    private fun TestScope.connectAndRead() {
        connect()
        viewModel.startObd()
        runCurrent()
    }

    private fun sessionTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { viewModel.disconnect(); runCurrent() }
    }

    private inner class TestGateway : BluetoothGateway {
        var devices = listOf(device)
        var link = TestConnection()
        var connects = 0
        override fun availability() = BluetoothAvailability.READY
        override fun pairedDevices() = devices
        override suspend fun connect(device: AdapterDevice): AdapterConnection { connects++; return link }
    }

    private class TestConnection : AdapterConnection {
        var closed = false
        override suspend fun read(timeoutMillis: Long) = byteArrayOf()
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = Unit
        override fun close() { closed = true }
    }
}
