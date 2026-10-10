package com.gonibix.rx8.bluetooth

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Injectable boundary around blocking platform IO. close must abort concurrent operations. */
internal interface BlockingBluetoothSocket {
    val input: InputStream
    val output: OutputStream
    fun connect()
    fun close()
}

/** Owns one socket; cancellation invalidates it because partially consumed bytes cannot be replayed. */
internal class StreamAdapterConnection(
    private val socket: BlockingBluetoothSocket,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdapterConnection {
    private val closed = AtomicBoolean(false)
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override suspend fun read(timeoutMillis: Long): ByteArray = bounded(timeoutMillis) {
        readMutex.withLock {
            blocking {
                val buffer = ByteArray(4_096)
                val count = socket.input.read(buffer)
                if (count < 0) throw EOFException("The Bluetooth connection was closed by the remote device.")
                if (count == 0) throw IOException("The Bluetooth stream returned no bytes.")
                buffer.copyOf(count)
            }
        }
    }

    override suspend fun write(bytes: ByteArray, timeoutMillis: Long) {
        val ownedBytes = bytes.copyOf()
        bounded(timeoutMillis) {
            writeMutex.withLock {
                blocking {
                    if (ownedBytes.isNotEmpty()) {
                        socket.output.write(ownedBytes)
                        socket.output.flush()
                    }
                }
            }
        }
    }

    internal fun connectSocket() {
        if (closed.get()) throw IOException("The Bluetooth connection is closed.")
        socket.connect()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                // BluetoothSocket.close is thread-safe and aborts blocking IO immediately.
                // Do not queue this behind the operation it must interrupt.
                socket.close()
            } catch (_: IOException) {
                // Already unusable: cleanup must not replace the original failure or cancellation.
            }
        }
    }

    private suspend fun <T> bounded(timeoutMillis: Long, operation: suspend () -> T): T {
        require(timeoutMillis > 0L) { "The operation timeout must be positive." }
        return try {
            withTimeout(timeoutMillis) { operation() }
        } catch (cancelled: CancellationException) {
            // Also handles cancellation while waiting for a same-direction operation's mutex.
            close()
            throw cancelled
        }
    }

    private suspend fun <T> blocking(operation: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            try {
                ioDispatcher.dispatch(continuation.context, Runnable {
                    if (!continuation.isActive) return@Runnable
                    try {
                        if (closed.get()) throw IOException("The Bluetooth connection is closed.")
                        val result = operation()
                        continuation.resume(result) { _, _, _ -> close() }
                    } catch (failure: Exception) {
                        close()
                        continuation.resumeWithException(failure)
                    }
                })
            } catch (failure: Exception) {
                close()
                continuation.resumeWithException(failure)
            }
        }
}

/** Covers creation, blocking connect, and cancellation before the caller receives ownership. */
internal suspend fun openAdapterConnection(
    socketFactory: () -> BlockingBluetoothSocket,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    timeoutMillis: Long = 15_000L,
): AdapterConnection {
    require(timeoutMillis > 0L) { "The connection timeout must be positive." }
    val pending = AtomicReference<StreamAdapterConnection?>()
    return try {
        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { pending.get()?.close() }
                try {
                    ioDispatcher.dispatch(continuation.context, Runnable {
                        if (!continuation.isActive) return@Runnable
                        try {
                            val connection = StreamAdapterConnection(socketFactory(), ioDispatcher)
                            pending.set(connection)
                            if (!continuation.isActive) {
                                connection.close()
                                return@Runnable
                            }
                            // The socket is now owned by pending and can be closed during connect.
                            connection.connectSocket()
                            continuation.resume(connection) { _, abandoned, _ -> abandoned.close() }
                        } catch (failure: Exception) {
                            pending.get()?.close()
                            continuation.resumeWithException(failure)
                        }
                    })
                } catch (failure: Exception) {
                    pending.get()?.close()
                    continuation.resumeWithException(failure)
                }
            }
        }
    } catch (failure: Exception) {
        pending.get()?.close()
        throw failure
    }
}
