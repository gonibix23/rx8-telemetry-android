package com.gonibix.rx8.connection

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSession
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.obd.ObdSessionSnapshot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticAcquisitionTest {
    private val device = AdapterDevice("02:00:00:00:00:29", "Synthetic automatic adapter")

    @Test fun authorizedConnectionContinuesAfterScreenLockAndPreparesRecordingBeforeFirstCallback() = runTest {
        val fixture = Fixture(this)
        val socket = CompletableDeferred<AdapterConnection>()
        fixture.gateway.open = { socket.await() }
        fixture.controller.setForeground(true)
        fixture.controller.selectDevice(device)
        fixture.controller.connect()
        runCurrent()
        fixture.controller.setForeground(false)
        socket.complete(fixture.gateway.link)
        runCurrent()
        assertEquals(listOf("prepare", "engine", "snapshot"), fixture.order)
        assertEquals(ObdPhase.LIVE, fixture.controller.uiState.value.obd.phase)
        assertFalse(fixture.controller.uiState.value.isForeground)
        fixture.controller.close()
        runCurrent()
    }

    @Test fun noReadingCanRaceAStillLoadingSelectedProfile() = runTest {
        val fixture = Fixture(this)
        val profile = CompletableDeferred<Unit>()
        fixture.prepare = { profile.await() }
        fixture.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.CONNECTED, fixture.controller.uiState.value.phase)
        assertEquals(0, fixture.engineStarts)
        fixture.controller.setForeground(false)
        profile.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.engineStarts)
        fixture.controller.close()
    }

    @Test fun disconnectDuringProfilePreparationClosesSocketAndNeverStartsTheEngine() = runTest {
        val fixture = Fixture(this)
        val profile = CompletableDeferred<Unit>()
        fixture.prepare = { withContext(NonCancellable) { profile.await() } }
        fixture.connect()
        runCurrent()
        fixture.controller.disconnect()
        profile.complete(Unit)
        runCurrent()
        assertEquals(0, fixture.engineStarts)
        assertEquals(1, fixture.gateway.link.closes)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, fixture.controller.uiState.value.phase)
        fixture.controller.close()
    }

    @Test fun obsoletePreparationCannotStartAcquisitionForAReplacementLink() = runTest {
        val fixture = Fixture(this)
        val old = CompletableDeferred<Unit>()
        fixture.prepare = { withContext(NonCancellable) { old.await() } }
        fixture.connect()
        runCurrent()
        fixture.controller.disconnect()
        fixture.gateway.link = Link()
        fixture.prepare = {}
        fixture.connect()
        runCurrent()
        assertEquals(1, fixture.engineStarts)
        old.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.engineStarts)
        assertEquals(0, fixture.gateway.link.closes)
        fixture.controller.close()
    }

    @Test fun permissionRevocationDuringPreparationReleasesSocketWithoutAutomaticRetry() = runTest {
        val fixture = Fixture(this)
        val profile = CompletableDeferred<Unit>()
        fixture.prepare = { profile.await() }
        fixture.connect()
        runCurrent()
        fixture.gateway.ready = BluetoothAvailability.PERMISSION_REQUIRED
        fixture.controller.refresh()
        profile.complete(Unit)
        runCurrent()
        fixture.gateway.ready = BluetoothAvailability.READY
        fixture.controller.refresh()
        runCurrent()
        assertEquals(0, fixture.engineStarts)
        assertEquals(1, fixture.gateway.connects)
        assertEquals(1, fixture.gateway.link.closes)
        fixture.controller.close()
    }

    @Test fun preparationFailureClosesOpenedSocketInsteadOfLeakingAConnectedLink() = runTest {
        val fixture = Fixture(this)
        fixture.prepare = { throw IOException("synthetic internal failure") }
        fixture.connect()
        runCurrent()
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, fixture.controller.uiState.value.phase)
        assertEquals(1, fixture.gateway.link.closes)
        assertEquals(0, fixture.engineStarts)
        assertFalse(fixture.controller.uiState.value.message.orEmpty().contains("internal"))
        fixture.controller.close()
    }

    @Test fun automaticAcquisitionCannotBeStartedTwiceByCompatibilityStartAction() = runTest {
        val fixture = Fixture(this)
        fixture.connect()
        runCurrent()
        repeat(3) { fixture.controller.startObd() }
        runCurrent()
        assertEquals(1, fixture.engineStarts)
        fixture.controller.disconnect()
        fixture.controller.startObd()
        runCurrent()
        assertEquals(1, fixture.engineStarts)
        fixture.controller.close()
    }

    @Test fun immediatePreferenceFailureRemainsVisibleWhenAutomaticAcquisitionStarts() = runTest {
        val fixture = Fixture(this)
        fixture.prepare = { fixture.controller.setConnectionMessage("Connected, but the adapter could not be remembered.") }
        fixture.connect()
        runCurrent()
        assertEquals(ObdPhase.LIVE, fixture.controller.uiState.value.obd.phase)
        assertTrue(fixture.controller.uiState.value.message!!.contains("could not be remembered"))
        fixture.controller.close()
    }

    @Test fun serviceAuthorizationCanStartFromAnotherVisibleTabWithoutEnablingDiscovery() = runTest {
        val fixture = Fixture(this)
        fixture.controller.refresh()
        fixture.controller.selectDevice(device)
        fixture.controller.connectFromVisibleApp()
        runCurrent()
        assertEquals(1, fixture.gateway.connects)
        assertEquals(1, fixture.engineStarts)
        assertFalse(fixture.controller.uiState.value.isForeground)
        fixture.controller.close()
    }

    @Test fun privateRememberedAddressCanConnectWithoutPairedOrScanResults() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.paired = emptyList()
        assertTrue(fixture.controller.selectRememberedDevice(device.address.lowercase()))
        val selected = fixture.controller.uiState.value.selectedDevice!!
        assertEquals(device.address, selected.address)
        assertEquals("Last connected adapter", selected.name)
        assertFalse(selected.isPaired)
        assertEquals(0, fixture.gateway.connects)
        fixture.controller.connectFromVisibleApp()
        runCurrent()
        assertEquals(1, fixture.gateway.connects)
        assertEquals(1, fixture.engineStarts)
        fixture.controller.close()
    }

    @Test fun rememberedSelectionKeepsPairedNameAndRejectsBadAddressOrMissingPermission() = runTest {
        val fixture = Fixture(this)
        assertTrue(fixture.controller.selectRememberedDevice(device.address))
        assertEquals(device, fixture.controller.uiState.value.selectedDevice)
        fixture.controller.disconnect()
        assertFalse(fixture.controller.selectRememberedDevice("not-a-Bluetooth-address"))
        fixture.gateway.ready = BluetoothAvailability.PERMISSION_REQUIRED
        assertFalse(fixture.controller.selectRememberedDevice(device.address))
        assertNull(fixture.controller.uiState.value.selectedDevice)
        assertEquals(0, fixture.gateway.connects)
        fixture.controller.close()
    }

    private inner class Fixture(scope: TestScope) {
        val gateway = Gateway()
        var prepare: suspend () -> Unit = {}
        val order = mutableListOf<String>()
        var engineStarts = 0
        val controller = BluetoothSessionController(
            gateway, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler),
            obdSessionFactory = ObdSessionFactory {
                object : ObdSession {
                    override suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit) {
                        engineStarts++
                        order += "engine"
                        onSnapshot(ObdSessionSnapshot(phase = ObdPhase.LIVE))
                        awaitCancellation()
                    }
                }
            },
            onObdSnapshot = { order += "snapshot" },
            autoStartObd = true,
            onConnected = { order += "prepare"; prepare() },
        )
        fun connect() {
            controller.setForeground(true)
            controller.selectDevice(device)
            controller.connect()
        }
    }

    private inner class Gateway : BluetoothGateway {
        var ready = BluetoothAvailability.READY
        var paired = listOf(device)
        var link = Link()
        var connects = 0
        var open: suspend () -> AdapterConnection = { link }
        override fun availability() = ready
        override fun pairedDevices() = paired
        override suspend fun connect(device: AdapterDevice): AdapterConnection { connects++; return open() }
    }

    private class Link : AdapterConnection {
        var closes = 0
        override suspend fun read(timeoutMillis: Long) = byteArrayOf()
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = Unit
        override fun close() { closes++ }
    }
}
