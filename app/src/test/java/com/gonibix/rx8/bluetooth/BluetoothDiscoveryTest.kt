package com.gonibix.rx8.bluetooth

import android.bluetooth.BluetoothDevice
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothDiscoveryTest {
    @Test
    fun `address identity ignores case name and bonding updates`() {
        val initial = AdapterDevice("aa:bb:cc:dd:ee:ff", "Unnamed Bluetooth device", isPaired = false)
        val paired = AdapterDevice("AA:BB:CC:DD:EE:FF", "OBDII", isPaired = true)

        assertEquals(initial.key, paired.key)
        assertFalse(initial.isPaired)
        assertTrue(paired.isPaired)
    }

    @Test
    fun `unresolved and dual devices remain Classic candidates while LE-only devices do not`() {
        assertTrue(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
        assertTrue(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertTrue(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertFalse(isClassicCandidate(BluetoothDevice.DEVICE_TYPE_LE))
    }

    @Test
    fun `missing or blank names never remove discovered devices`() {
        assertEquals("Unnamed Bluetooth device", discoveryDeviceName(null, null))
        assertEquals("Unnamed Bluetooth device", discoveryDeviceName("  ", "\t"))
        assertEquals("OBDII", discoveryDeviceName(" OBDII ", "Old name"))
        assertEquals("OBDII", discoveryDeviceName(null, " OBDII "))
    }

    @Test
    fun `inquiry stays active beyond the first watchdog interval and closes at its deadline`() = runTest {
        var cleanedUp = 0
        val found = mutableListOf<AdapterDevice>()
        val scan = launch {
            boundedDiscovery(15_000L) {
                callbackFlow {
                    send(AdapterDevice("00:11:22:33:44:55", "OBDII", isPaired = false))
                    awaitClose { cleanedUp++ }
                }
            }.toList(found)
        }
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(scan.isActive)
        assertEquals(0, cleanedUp)
        assertEquals(1, found.size)

        advanceTimeBy(14_499L)
        runCurrent()
        assertTrue(scan.isActive)
        advanceTimeBy(1L)
        runCurrent()

        assertTrue(scan.isCompleted)
        assertFalse(scan.isCancelled)
        assertEquals(1, cleanedUp)
        assertEquals("OBDII", found.single().name)
    }

    @Test
    fun `caller cancellation promptly cleans up and is not mistaken for a completed search`() = runTest {
        var cleanedUp = 0
        val scan = launch {
            boundedDiscovery<AdapterDevice>(15_000L) {
                callbackFlow { awaitClose { cleanedUp++ } }
            }.toList()
        }
        runCurrent()

        scan.cancel()
        runCurrent()

        assertTrue(scan.isCancelled)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, cleanedUp)
    }

    @Test
    fun `scanner failure propagates while already discovered devices remain available`() = runTest {
        val found = mutableListOf<AdapterDevice>()
        val result = runCatching {
            boundedDiscovery(15_000L) {
                flow {
                    emit(AdapterDevice("00:11:22:33:44:55", "OBDII", isPaired = false))
                    throw IOException("Synthetic inquiry failure")
                }
            }.toList(found)
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("Synthetic inquiry failure", result.exceptionOrNull()?.message)
        assertEquals(1, found.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `natural inquiry completion does not wait for the full deadline`() = runTest {
        val found = boundedDiscovery(15_000L) {
            flow { emit(AdapterDevice("00:11:22:33:44:55", "OBDII", isPaired = false)) }
        }.toList()

        assertEquals(1, found.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `availability failures distinguish permission from disabled services`() {
        requireDiscoveryReady(BluetoothAvailability.READY)
        assertThrows(SecurityException::class.java) { requireDiscoveryReady(BluetoothAvailability.PERMISSION_REQUIRED) }
        listOf(BluetoothAvailability.OFF, BluetoothAvailability.LOCATION_DISABLED, BluetoothAvailability.UNSUPPORTED).forEach {
            assertThrows(IllegalStateException::class.java) { requireDiscoveryReady(it) }
        }
    }

    @Test
    fun `invalid discovery duration does not start a scanner`() = runTest {
        var started = false
        val result = runCatching {
            boundedDiscovery<AdapterDevice>(0L) {
                started = true
                flow { }
            }.toList()
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(started)
    }
}
