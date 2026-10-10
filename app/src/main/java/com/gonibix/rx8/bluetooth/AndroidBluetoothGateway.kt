package com.gonibix.rx8.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/** Provisional Classic/SPP transport; compatibility with the user's adapter is not yet verified. */
class AndroidBluetoothGateway(context: Context) : BluetoothGateway {
    private val applicationContext = context.applicationContext

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

    @SuppressLint("MissingPermission") // Availability gates access; revocation can still throw to the caller.
    override fun pairedDevices(): List<AdapterDevice> {
        if (availability() != BluetoothAvailability.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .filter { it.bondState == BluetoothDevice.BOND_BONDED && it.supportsClassic() }
            .map {
                AdapterDevice(
                    address = it.address,
                    name = it.name?.trim()?.takeIf(String::isNotEmpty) ?: "Paired Bluetooth device",
                )
            }
            .sortedWith(compareBy<AdapterDevice> { it.name.lowercase(Locale.ROOT) }.thenBy { it.address })
    }

    @SuppressLint("MissingPermission") // Permission is rechecked on IO immediately before opening the socket.
    override suspend fun connect(device: AdapterDevice): AdapterConnection = openAdapterConnection(
        socketFactory = {
            when (availability()) {
                BluetoothAvailability.PERMISSION_REQUIRED ->
                    throw SecurityException("Bluetooth connection permission is required.")
                BluetoothAvailability.OFF -> throw IllegalStateException("Bluetooth is turned off.")
                BluetoothAvailability.UNSUPPORTED ->
                    throw IllegalStateException("Classic Bluetooth is not supported on this device.")
                BluetoothAvailability.READY -> Unit
            }
            require(BluetoothAdapter.checkBluetoothAddress(device.address)) {
                "The selected Bluetooth address is invalid."
            }
            val currentAdapter = checkNotNull(adapter) { "Bluetooth is unavailable." }
            val paired = currentAdapter.bondedDevices.firstOrNull { it.address == device.address }
            require(paired != null && paired.bondState == BluetoothDevice.BOND_BONDED) {
                "Pair this device in Android settings before connecting."
            }
            require(paired.supportsClassic()) { "The selected device does not support Classic Bluetooth." }
            AndroidSocket(paired.createRfcommSocketToServiceRecord(SPP_UUID))
        },
    )

    private fun hasConnectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // Only called after permission checks in pairedDevices/connect.
    private fun BluetoothDevice.supportsClassic(): Boolean =
        type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL

    private class AndroidSocket(private val socket: BluetoothSocket) : BlockingBluetoothSocket {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream

        @SuppressLint("MissingPermission") // Caller checks permission; revocation propagates as SecurityException.
        override fun connect() = socket.connect()
        override fun close() = socket.close()
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
