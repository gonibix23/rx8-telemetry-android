package com.gonibix.rx8.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Provisional Classic/SPP transport; compatibility with the user's adapter still needs validation. */
class AndroidBluetoothGateway(context: Context) : BluetoothGateway {
    private val applicationContext = context.applicationContext
    private val discovery = AndroidBluetoothDiscovery(applicationContext, { adapter }, ::discoveryAvailability)

    private val adapter: BluetoothAdapter?
        get() = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission") // Permission is checked before protected adapter access.
    override fun availability(): BluetoothAvailability {
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            return BluetoothAvailability.UNSUPPORTED
        }
        val currentAdapter = adapter ?: return BluetoothAvailability.UNSUPPORTED
        if (!hasConnectPermission()) return BluetoothAvailability.PERMISSION_REQUIRED
        return try {
            if (currentAdapter.isEnabled) BluetoothAvailability.READY else BluetoothAvailability.OFF
        } catch (_: SecurityException) {
            BluetoothAvailability.PERMISSION_REQUIRED
        }
    }

    override fun discoveryAvailability(): BluetoothAvailability {
        val connectionAvailability = availability()
        if (connectionAvailability != BluetoothAvailability.READY) return connectionAvailability
        if (!hasScanPermission()) return BluetoothAvailability.PERMISSION_REQUIRED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return BluetoothAvailability.PERMISSION_REQUIRED
            }
            val location = applicationContext.getSystemService(LocationManager::class.java)
                ?: return BluetoothAvailability.LOCATION_DISABLED
            val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                location.isLocationEnabled
            } else {
                location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            if (!enabled) return BluetoothAvailability.LOCATION_DISABLED
        }
        return BluetoothAvailability.READY
    }

    @SuppressLint("MissingPermission") // Availability gates access; permission revocation still propagates.
    override fun pairedDevices(): List<AdapterDevice> {
        if (availability() != BluetoothAvailability.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .filter { it.bondState == BluetoothDevice.BOND_BONDED && isClassicCandidate(it.type) }
            .map { device ->
                AdapterDevice(
                    address = device.address,
                    name = device.name?.trim()?.takeIf(String::isNotEmpty) ?: "Unnamed Bluetooth device",
                    isPaired = true,
                )
            }
            .sortedWith(compareBy<AdapterDevice> { it.name.lowercase(Locale.ROOT) }.thenBy { it.key })
    }

    override fun discover(): Flow<AdapterDevice> = discovery.discover()

    @SuppressLint("MissingPermission") // Entry checks permissions; runtime revocation propagates.
    override suspend fun connect(device: AdapterDevice): AdapterConnection {
        discovery.stop()
        requireConnectionReady()
        val address = device.key
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "The selected Bluetooth address is invalid." }
        val currentAdapter = checkNotNull(adapter) { "Bluetooth is unavailable." }
        val (remote, connectionTimeout) = withContext(Dispatchers.IO) {
            requireConnectionReady()
            val selected = currentAdapter.getRemoteDevice(address)
            require(isClassicCandidate(selected.type)) { "This device requires a BLE connection, which is not supported here." }
            val timeout = if (selected.bondState == BluetoothDevice.BOND_BONDED) 15_000L else 45_000L
            selected to timeout
        }
        return openAdapterConnection(
            socketFactory = {
                requireConnectionReady()
                // CONNECT-only access can still connect a paired device when discovery is not permitted.
                if (hasScanPermission()) currentAdapter.cancelDiscovery()
                // Android can initiate pairing for an unbonded device; no createBond or app commands are sent.
                AndroidSocket(remote.createRfcommSocketToServiceRecord(SPP_UUID))
            },
            timeoutMillis = connectionTimeout,
        )
    }

    private fun requireConnectionReady() {
        when (availability()) {
            BluetoothAvailability.PERMISSION_REQUIRED ->
                throw SecurityException("Bluetooth connection permission is required.")
            BluetoothAvailability.OFF -> throw IllegalStateException("Bluetooth is turned off.")
            BluetoothAvailability.UNSUPPORTED -> throw IllegalStateException("Classic Bluetooth is not supported.")
            BluetoothAvailability.LOCATION_DISABLED -> throw IllegalStateException("Location services are disabled.")
            BluetoothAvailability.READY -> Unit
        }
    }

    private fun hasConnectPermission(): Boolean = hasPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH,
    )

    private fun hasScanPermission(): Boolean = hasPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.BLUETOOTH_ADMIN,
    )

    private fun hasPermission(permission: String): Boolean =
        applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private class AndroidSocket(private val socket: BluetoothSocket) : BlockingBluetoothSocket {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream

        @SuppressLint("MissingPermission") // Caller checks permission; revocation propagates.
        override fun connect() = socket.connect()
        override fun close() = socket.close()
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
