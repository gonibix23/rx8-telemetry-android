package com.gonibix.rx8.obd

import com.gonibix.rx8.bluetooth.AdapterConnection
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal class ObdExchangeException(
    val status: ObdReadingStatus,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A complete request owns the stream until its prompt. An incomplete reply invalidates the link. */
internal class ElmCommandExchange(private val connection: AdapterConnection) {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    suspend fun execute(command: String, timeoutMillis: Long = 5_000L): String {
        require(command in ALLOWED_COMMANDS) { "This command is outside the read-only OBD session." }
        require(timeoutMillis > 0L) { "The command timeout must be positive." }
        try {
            return mutex.withLock {
                check(!closed.get()) { "The OBD connection is closed." }
                withTimeout(timeoutMillis) {
                    connection.write((command + "\r").toByteArray(Charsets.US_ASCII), timeoutMillis)
                    readPrompt(command, timeoutMillis)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            close()
            // Parent cancellation is still cancellation, even if it also uses a timeout.
            currentCoroutineContext().ensureActive()
            throw ObdExchangeException(ObdReadingStatus.TIMEOUT, "The adapter response timed out. Reconnect to try again.", timeout)
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (failure: Exception) {
            close()
            if (failure is ObdExchangeException) throw failure
            throw ObdExchangeException(ObdReadingStatus.ADAPTER_ERROR, "The adapter connection was interrupted. Reconnect to try again.", failure)
        }
    }

    private suspend fun readPrompt(command: String, timeoutMillis: Long): String {
        val body = StringBuilder()
        var totalBytes = 0
        while (true) {
            val fragment = connection.read(timeoutMillis)
            if (fragment.isEmpty()) throw IOException("The adapter returned an empty fragment.")
            totalBytes += fragment.size
            if (totalBytes > MAX_RESPONSE_BYTES) framingFailure()
            for (index in fragment.indices) {
                val byte = fragment[index].toInt() and 0xff
                if (byte !in 32..126 && byte != 9 && byte != 10 && byte != 13) framingFailure()
                val character = byte.toChar()
                if (character == '>') {
                    // An adapter may send an idle prompt immediately after RFCOMM opens.
                    if (command == "ATZ" && body.isBlank()) {
                        body.clear()
                        continue
                    }
                    // No second response is allowed to leak into the following command.
                    if (fragment.drop(index + 1).any { (it.toInt() and 0xff).toChar() !in " \t\r\n" }) framingFailure()
                    return body.toString()
                }
                body.append(character)
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.close()
            } catch (_: IOException) {
                // Cleanup must preserve the original failure or cancellation.
            }
        }
    }

    private fun framingFailure(): Nothing = throw ObdExchangeException(
        ObdReadingStatus.PARSE_ERROR,
        "The adapter returned an invalid response. Reconnect to try again.",
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 8_192
        val ALLOWED_COMMANDS = setOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATCAF1", "ATSP0", "ATDPN", "ATCFC1", "0902") +
            com.gonibix.rx8.telemetry.obd.ObdPid.entries.map { it.command } +
            (0..0xe0 step 0x20).map { "01%02X".format(java.util.Locale.ROOT, it) }
    }
}
