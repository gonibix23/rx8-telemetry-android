package com.gonibix.rx8.connection

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = AdapterDevice("02:00:00:00:00:01", "Test OBD adapter")
    private lateinit var gateway: FakeGateway
    private lateinit var viewModel: BluetoothConnectionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway(device)
        viewModel = BluetoothConnectionViewModel(gateway, dispatcher)
    }

    @After
    fun tearDown() {
        viewModel.setForeground(false)
        viewModel.disconnect()
        Dispatchers.resetMain()
    }

    @Test
    fun missingPermissionNeverListsOrConnectsDevices() = connectionTest {
        gateway.availabilityValue = BluetoothAvailability.PERMISSION_REQUIRED
        val pairedCallsBefore = gateway.pairedCalls
        viewModel.setForeground(true)
        viewModel.connect()
        runCurrent()

        assertEquals(BluetoothAvailability.PERMISSION_REQUIRED, viewModel.uiState.value.availability)
        assertEquals(pairedCallsBefore, gateway.pairedCalls)
        assertEquals(0, gateway.connectCalls)
        assertTrue(viewModel.uiState.value.devices.isEmpty())
        assertNull(viewModel.uiState.value.selectedDevice)
    }

    @Test
    fun offAndUnsupportedStatesClearDevicesAndSelection() = connectionTest {
        viewModel.setForeground(true)
        viewModel.selectDevice(device)
        for (availability in listOf(BluetoothAvailability.OFF, BluetoothAvailability.UNSUPPORTED)) {
            gateway.availabilityValue = availability
            viewModel.refresh()
            assertEquals(availability, viewModel.uiState.value.availability)
            assertTrue(viewModel.uiState.value.devices.isEmpty())
            assertNull(viewModel.uiState.value.selectedDevice)
            assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        }
    }

    @Test
    fun permissionRevokedWhileListingHasARecoveryState() = connectionTest {
        gateway.pairedFailure = SecurityException("Synthetic permission denial")
        viewModel.refresh()
        assertEquals(BluetoothAvailability.PERMISSION_REQUIRED, viewModel.uiState.value.availability)
        assertTrue(viewModel.uiState.value.devices.isEmpty())
    }

    @Test
    fun pairedDevicesAreDeduplicatedAndUnlistedDevicesCannotBeSelected() = connectionTest {
        val mixedCase = AdapterDevice("02:00:00:AA:BB:CC", "Adapter A")
        gateway.devices = listOf(mixedCase, mixedCase.copy(address = "02:00:00:aa:bb:cc"), device)
        viewModel.refresh()
        assertEquals(2, viewModel.uiState.value.devices.size)
        viewModel.selectDevice(AdapterDevice("02:00:00:00:00:99", "Unpaired"))
        assertNull(viewModel.uiState.value.selectedDevice)
        viewModel.selectDevice(mixedCase.copy(address = "02:00:00:aa:bb:cc"))
        assertEquals(mixedCase, viewModel.uiState.value.selectedDevice)
    }

    @Test
    fun duplicateConnectRequestsUseOnePendingSession() = connectionTest {
        val result = CompletableDeferred<AdapterConnection>()
        gateway.connectBehavior = { result.await() }
        prepareSelection()
        viewModel.connect()
        viewModel.connect()
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.CONNECTING, viewModel.uiState.value.phase)
        result.complete(gateway.connection)
        runCurrent()
        viewModel.connect()
        assertEquals(1, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun cancellingPendingConnectionCancelsTheGatewayPromptly() = connectionTest {
        var gatewayCancelled = false
        gateway.connectBehavior = {
            try {
                awaitCancellation()
            } finally {
                gatewayCancelled = true
            }
        }
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.disconnect()
        runCurrent()

        assertTrue(gatewayCancelled)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun lateSuccessIsClosedWithoutReplacingANewerConnection() = connectionTest {
        lateinit var oldResult: Continuation<AdapterConnection>
        gateway.connectBehavior = { suspendCoroutine { oldResult = it } }
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.disconnect()

        val current = FakeConnection()
        gateway.connectBehavior = { current }
        viewModel.connect()
        runCurrent()
        val stale = FakeConnection()
        oldResult.resume(stale)
        runCurrent()

        assertEquals(1, stale.closeCalls)
        assertEquals(0, current.closeCalls)
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        viewModel.disconnect()
        assertEquals(1, current.closeCalls)
    }

    @Test
    fun backgroundClosesConnectionAndDoesNotReconnectOnReturn() = connectionTest {
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.setForeground(false)

        assertEquals(1, gateway.connection.closeCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.isForeground)
        viewModel.setForeground(true)
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(device, viewModel.uiState.value.selectedDevice)
    }

    @Test
    fun backgroundCancelsPendingConnectionAndLateSuccessIsClosed() = connectionTest {
        lateinit var result: Continuation<AdapterConnection>
        gateway.connectBehavior = { suspendCoroutine { result = it } }
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.setForeground(false)
        val lateConnection = FakeConnection()
        result.resume(lateConnection)
        runCurrent()
        assertEquals(1, lateConnection.closeCalls)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun BluetoothOffClosesTheCurrentLink() = connectionTest {
        prepareSelection()
        viewModel.connect()
        runCurrent()
        gateway.availabilityValue = BluetoothAvailability.OFF
        viewModel.refresh()
        assertEquals(1, gateway.connection.closeCalls)
        assertEquals(BluetoothAvailability.OFF, viewModel.uiState.value.availability)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun deviceDisconnectOnlyAffectsTheMatchingSession() = connectionTest {
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.onDeviceDisconnected("02:00:00:00:00:99")
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        assertEquals(0, gateway.connection.closeCalls)
        viewModel.onDeviceDisconnected(device.address)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(1, gateway.connection.closeCalls)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("lost"))
    }

    @Test
    fun staleDisconnectBroadcastDoesNotCancelImmediateReconnectToSameDevice() = connectionTest {
        prepareSelection()
        viewModel.connect()
        runCurrent()
        viewModel.disconnect()
        assertEquals(1, gateway.connection.closeCalls)

        val reconnectResult = CompletableDeferred<AdapterConnection>()
        gateway.connectBehavior = { reconnectResult.await() }
        viewModel.connect()
        runCurrent()
        viewModel.onDeviceDisconnected(device.address)
        assertEquals(BluetoothConnectionPhase.CONNECTING, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.message)

        val newConnection = FakeConnection()
        reconnectResult.complete(newConnection)
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        assertEquals(0, newConnection.closeCalls)
        viewModel.onDeviceDisconnected("02:00:00:00:00:99")
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
        viewModel.onDeviceDisconnected(device.address)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertEquals(1, newConnection.closeCalls)
    }

    @Test
    fun timeoutReturnsToManualRetryWithoutAnAutomaticAttempt() = connectionTest {
        gateway.connectBehavior = { withTimeout(1L) { delay(100L); gateway.connection } }
        prepareSelection()
        viewModel.connect()
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("timed out"))
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        gateway.connectBehavior = { gateway.connection }
        viewModel.connect()
        runCurrent()
        assertEquals(2, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun permissionRevokedDuringConnectReturnsToPermissionRecovery() = connectionTest {
        gateway.connectBehavior = { throw SecurityException("Synthetic denial") }
        prepareSelection()
        viewModel.connect()
        runCurrent()
        assertEquals(BluetoothAvailability.PERMISSION_REQUIRED, viewModel.uiState.value.availability)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.selectedDevice)
    }

    @Test
    fun unpairingSelectedDeviceClosesItsLink() = connectionTest {
        prepareSelection()
        viewModel.connect()
        runCurrent()
        gateway.devices = emptyList()
        viewModel.refresh()
        assertEquals(1, gateway.connection.closeCalls)
        assertNull(viewModel.uiState.value.selectedDevice)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun connectionCannotStartWhileBackgrounded() = connectionTest {
        viewModel.selectDevice(device)
        viewModel.connect()
        runCurrent()
        assertEquals(0, gateway.connectCalls)
    }

    @Test
    fun discoveryMergesByAddressAndKeepsEveryDeviceName() = connectionTest {
        val nearby = AdapterDevice("02:00:00:AA:BB:CC", "OBDII", isPaired = false)
        gateway.discoverBehavior = {
            flow {
                emit(device.copy(address = device.address.lowercase(), isPaired = false))
                emit(nearby)
                emit(nearby.copy(address = nearby.address.lowercase()))
                emit(AdapterDevice("02:00:00:00:00:88", "Other device", isPaired = false))
            }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()

        assertEquals(3, viewModel.uiState.value.devices.size)
        assertTrue(viewModel.uiState.value.devices.first { it.address == device.address }.isPaired)
        assertTrue(viewModel.uiState.value.devices.any { it.name == "Other device" })
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(viewModel.uiState.value.hasScanned)
        viewModel.refresh()
        assertEquals(3, viewModel.uiState.value.devices.size)
        viewModel.selectDevice(nearby)
        assertEquals("OBDII", viewModel.uiState.value.selectedDevice?.name)
    }

    @Test
    fun emptyDiscoveryHasExplicitRetryGuidance() = connectionTest {
        gateway.devices = emptyList()
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        assertTrue(viewModel.uiState.value.devices.isEmpty())
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(viewModel.uiState.value.discoveryMessage.orEmpty().contains("No nearby devices found"))
    }

    @Test
    fun partialDiscoveryResultsSurviveFailure() = connectionTest {
        val nearby = AdapterDevice("02:00:00:00:00:44", "OBDII", isPaired = false)
        gateway.discoverBehavior = { flow { emit(nearby); throw IOException("Synthetic discovery failure") } }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        assertTrue(viewModel.uiState.value.devices.contains(nearby))
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(viewModel.uiState.value.discoveryMessage.orEmpty().contains("Scan failed"))
    }

    @Test
    fun scanPermissionIsIndependentOfPairedConnectionPermission() = connectionTest {
        gateway.discoveryAvailabilityValue = BluetoothAvailability.PERMISSION_REQUIRED
        prepareSelection()
        viewModel.startDiscovery()
        runCurrent()
        assertEquals(0, gateway.discoverCalls)
        assertEquals(BluetoothAvailability.READY, viewModel.uiState.value.availability)
        assertEquals(BluetoothAvailability.PERMISSION_REQUIRED, viewModel.uiState.value.discoveryAvailability)
        viewModel.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun disabledLocationOnlyBlocksDiscovery() = connectionTest {
        gateway.discoveryAvailabilityValue = BluetoothAvailability.LOCATION_DISABLED
        prepareSelection()
        viewModel.startDiscovery()
        runCurrent()
        assertEquals(0, gateway.discoverCalls)
        viewModel.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun backgroundCancelsDiscoveryAndDoesNotRestartItOnReturn() = connectionTest {
        var cancelled = false
        gateway.discoverBehavior = {
            flow {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        viewModel.startDiscovery()
        runCurrent()
        assertEquals(1, gateway.discoverCalls)
        assertTrue(viewModel.uiState.value.isScanning)
        viewModel.setForeground(false)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.isScanning)
        viewModel.setForeground(true)
        runCurrent()
        assertEquals(1, gateway.discoverCalls)
    }

    @Test
    fun connectWaitsForScanCleanupAndAcceptsAnUnpairedDiscoveredDevice() = connectionTest {
        var cleaned = false
        val nearby = AdapterDevice("02:00:00:00:00:44", "OBDII", isPaired = false)
        gateway.discoverBehavior = {
            flow {
                emit(nearby)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(500L); cleaned = true }
                }
            }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        viewModel.selectDevice(nearby)
        viewModel.connect()
        runCurrent()
        assertFalse(cleaned)
        assertEquals(0, gateway.connectCalls)
        assertEquals(BluetoothConnectionPhase.CONNECTING, viewModel.uiState.value.phase)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(cleaned)
        assertEquals(nearby, gateway.lastConnectedDevice)
        assertEquals(BluetoothConnectionPhase.CONNECTED, viewModel.uiState.value.phase)
    }

    @Test
    fun scanPermissionRevocationCancelsScanButKeepsPairedDevices() = connectionTest {
        var cancelled = false
        gateway.discoverBehavior = {
            flow { try { awaitCancellation() } finally { cancelled = true } }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        gateway.discoveryAvailabilityValue = BluetoothAvailability.PERMISSION_REQUIRED
        viewModel.refresh()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(listOf(device), viewModel.uiState.value.devices)
        assertEquals(BluetoothAvailability.READY, viewModel.uiState.value.availability)
    }

    @Test
    fun BluetoothOffCancelsDiscoveryAndClearsResults() = connectionTest {
        var cancelled = false
        gateway.discoverBehavior = {
            flow {
                emit(AdapterDevice("02:00:00:00:00:44", "OBDII", isPaired = false))
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        gateway.availabilityValue = BluetoothAvailability.OFF
        viewModel.refresh()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(viewModel.uiState.value.devices.isEmpty())
    }

    @Test
    fun staleScanFailureCannotReplaceTheNextScansState() = connectionTest {
        gateway.discoverBehavior = {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(500L) }
                    throw IOException("Synthetic stale cleanup error")
                }
            }
        }
        viewModel.setForeground(true)
        viewModel.startDiscovery()
        runCurrent()
        viewModel.stopDiscovery()
        val nearby = AdapterDevice("02:00:00:00:00:44", "OBDII", isPaired = false)
        gateway.discoverBehavior = { flow { emit(nearby) } }
        viewModel.startDiscovery()
        runCurrent()
        assertTrue(viewModel.uiState.value.isScanning)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(viewModel.uiState.value.devices.contains(nearby))
        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals("Scan complete. Found devices remain available for selection.", viewModel.uiState.value.discoveryMessage)
    }


    @Test
    fun discoveryCannotStartBeforeTheAdapterScreenIsForeground() = connectionTest {
        viewModel.startDiscovery()
        runCurrent()
        assertEquals(0, gateway.discoverCalls)
        assertFalse(viewModel.uiState.value.isScanning)
        viewModel.setForeground(true)
        runCurrent()
        assertEquals(0, gateway.discoverCalls)
        viewModel.startDiscovery()
        runCurrent()
        assertEquals(1, gateway.discoverCalls)
    }

    private fun prepareSelection() {
        viewModel.setForeground(true)
        viewModel.selectDevice(device)
    }

    private fun connectionTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            viewModel.setForeground(false)
            runCurrent()
        }
    }

    private class FakeGateway(device: AdapterDevice) : BluetoothGateway {
        var availabilityValue = BluetoothAvailability.READY
        var devices = listOf(device)
        var pairedFailure: Exception? = null
        var pairedCalls = 0
        var discoveryAvailabilityValue: BluetoothAvailability? = null
        var discoverCalls = 0
        var discoverBehavior: () -> Flow<AdapterDevice> = { emptyFlow() }
        var lastConnectedDevice: AdapterDevice? = null
        var connectCalls = 0
        val connection = FakeConnection()
        var connectBehavior: suspend (AdapterDevice) -> AdapterConnection = { connection }

        override fun availability() = availabilityValue

        override fun discoveryAvailability() = discoveryAvailabilityValue ?: availabilityValue

        override fun discover(): Flow<AdapterDevice> {
            discoverCalls++
            return discoverBehavior()
        }

        override fun pairedDevices(): List<AdapterDevice> {
            pairedCalls++
            pairedFailure?.let { throw it }
            return devices
        }

        override suspend fun connect(device: AdapterDevice): AdapterConnection {
            connectCalls++
            lastConnectedDevice = device
            return connectBehavior(device)
        }
    }

    private class FakeConnection : AdapterConnection {
        var closeCalls = 0
        override suspend fun read(timeoutMillis: Long) = byteArrayOf()
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = Unit
        override fun close() { closeCalls++ }
    }
}
