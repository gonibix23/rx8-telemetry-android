package com.gonibix.rx8.bluetooth

enum class BluetoothAvailability {
    READY,
    OFF,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
}

data class AdapterDevice(val address: String, val name: String)

interface BluetoothGateway {
    fun availability(): BluetoothAvailability
    fun pairedDevices(): List<AdapterDevice>
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
