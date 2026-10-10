package com.gonibix.rx8.bluetooth

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class BluetoothAvailability {
    READY,
    OFF,
    PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    UNSUPPORTED,
}

data class AdapterDevice(
    val address: String,
    val name: String,
    val isPaired: Boolean = true,
) {
    val key: String get() = address.uppercase(Locale.ROOT)
}

interface BluetoothGateway {
    fun availability(): BluetoothAvailability
    fun discoveryAvailability(): BluetoothAvailability = availability()
    fun pairedDevices(): List<AdapterDevice>
    fun discover(): Flow<AdapterDevice> = emptyFlow()
    suspend fun connect(device: AdapterDevice): AdapterConnection
}

/** Byte transport only. A successful connection does not establish OBD or vehicle compatibility. */
interface AdapterConnection {
    /** Returns a nonempty fragment. EOF throws IOException; no framing or decoding is performed. */
    suspend fun read(timeoutMillis: Long = 5_000L): ByteArray

    /** Writes exactly the supplied bytes. No terminator or initialization commands are added. */
    suspend fun write(bytes: ByteArray, timeoutMillis: Long = 5_000L)

    /** Idempotently releases the socket and aborts any pending operation. */
    fun close()
}
