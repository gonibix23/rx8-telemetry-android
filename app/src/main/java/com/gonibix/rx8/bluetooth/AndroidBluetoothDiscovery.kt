package com.gonibix.rx8.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded Classic inquiry. Cancellation completes cleanup before a new inquiry can start. */
internal class AndroidBluetoothDiscovery(
    private val context: Context,
    private val adapter: () -> BluetoothAdapter?,
    private val readiness: () -> BluetoothAvailability,
) {
    private val searchMutex = Mutex()
    private val activeSearch = AtomicReference<Job?>()

    fun discover(): Flow<AdapterDevice> = channelFlow {
        searchMutex.withLock {
            val owner = coroutineContext.job
            activeSearch.set(owner)
            try {
                requireDiscoveryReady(readiness())
                val currentAdapter = checkNotNull(adapter()) { "Bluetooth is unavailable." }
                stopPreviousDiscovery(currentAdapter)
                boundedDiscovery(15_000L) { classicDevices(currentAdapter) }.collect { send(it) }
            } finally {
                activeSearch.compareAndSet(owner, null)
            }
        }
    }

    suspend fun stop() {
        activeSearch.get()?.cancelAndJoin()
    }

    @SuppressLint("MissingPermission") // Discovery readiness has checked SCAN/legacy permissions.
    private suspend fun stopPreviousDiscovery(currentAdapter: BluetoothAdapter) {
        if (!currentAdapter.isDiscovering) return
        if (!currentAdapter.cancelDiscovery()) throw IOException("Existing Bluetooth discovery could not be stopped.")
        val stopped = withTimeoutOrNull(2_000L) {
            while (currentAdapter.isDiscovering) {
                requireDiscoveryReady(readiness())
                delay(50L)
            }
            true
        } ?: false
        if (!stopped) throw IOException("Existing Bluetooth discovery did not stop in time.")
    }

    @SuppressLint("MissingPermission") // Entry and watchdog check permissions; receiver handles revocation.
    private fun classicDevices(currentAdapter: BluetoothAdapter): Flow<AdapterDevice> = callbackFlow {
        requireDiscoveryReady(readiness())
        var startedBroadcastReceived = false
        var registered = false
        var discoveryRequested = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    requireDiscoveryReady(readiness())
                    when (intent.action) {
                        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> startedBroadcastReceived = true
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (startedBroadcastReceived) close()
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                                close(IllegalStateException("Bluetooth was turned off during discovery."))
                            }
                        }
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.bluetoothDevice() ?: return
                            // Metadata may still be unresolved. Only exclude devices known to be LE-only.
                            if (isClassicCandidate(device.type)) {
                                trySend(
                                    AdapterDevice(
                                        address = device.address,
                                        name = discoveryDeviceName(intent.getStringExtra(BluetoothDevice.EXTRA_NAME), device.name),
                                        isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
                                    ),
                                )
                            }
                        }
                    }
                } catch (failure: Exception) {
                    close(failure)
                }
            }
        }
        val watchdog = launch {
            while (isActive) {
                delay(500L)
                try {
                    requireDiscoveryReady(readiness())
                } catch (failure: Exception) {
                    close(failure)
                    return@launch
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            // Bluetooth broadcasts originate from a privileged process outside the system UID.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            discoveryRequested = true
            if (!currentAdapter.startDiscovery()) throw IOException("Classic Bluetooth discovery could not start.")
            awaitClose { }
        } finally {
            watchdog.cancel()
            try {
                if (discoveryRequested) currentAdapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Permission revocation must not prevent receiver cleanup.
            } finally {
                if (registered) context.unregisterReceiver(receiver)
            }
        }
    }.buffer(Channel.UNLIMITED)

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}

/** The timeout is normal completion; caller cancellation and real scanner errors still propagate. */
internal fun <T> boundedDiscovery(durationMillis: Long, source: () -> Flow<T>): Flow<T> = flow {
    require(durationMillis > 0L) { "Discovery duration must be positive." }
    withTimeoutOrNull(durationMillis) {
        source().collect { emit(it) }
    }
}

internal fun requireDiscoveryReady(availability: BluetoothAvailability) {
    when (availability) {
        BluetoothAvailability.READY -> Unit
        BluetoothAvailability.PERMISSION_REQUIRED -> throw SecurityException("Bluetooth discovery permission is required.")
        BluetoothAvailability.OFF -> throw IllegalStateException("Bluetooth is turned off.")
        BluetoothAvailability.LOCATION_DISABLED -> throw IllegalStateException("Enable Location services to discover devices on this Android version.")
        BluetoothAvailability.UNSUPPORTED -> throw IllegalStateException("Classic Bluetooth is not supported.")
    }
}

internal fun isClassicCandidate(deviceType: Int): Boolean = deviceType != BluetoothDevice.DEVICE_TYPE_LE

internal fun discoveryDeviceName(advertisedName: String?, cachedName: String?): String =
    advertisedName?.trim()?.takeIf(String::isNotEmpty)
        ?: cachedName?.trim()?.takeIf(String::isNotEmpty)
        ?: "Unnamed Bluetooth device"
