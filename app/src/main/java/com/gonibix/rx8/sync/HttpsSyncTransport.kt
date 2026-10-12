package com.gonibix.rx8.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Uses platform TLS verification. No redirects, authentication, or mutable request reserialization. */
class HttpsSyncTransport private constructor(
    baseUrl: String,
    private val factory: (String) -> HttpsURLConnection,
    private val dispatcher: CoroutineDispatcher,
    internal val isProductionTransport: Boolean,
) : SyncTransport {
    private val baseUrl = requireNotNull(UploadEndpoint.normalize(baseUrl))
    constructor(baseUrl: String) : this(baseUrl, { URI(it).toURL().openConnection() as HttpsURLConnection }, Dispatchers.IO, true)
    override val enabled = true

    override suspend fun send(request: PreparedRequest): RemoteReply = suspendCancellableCoroutine { continuation ->
        val pending = AtomicReference<HttpsURLConnection?>()
        continuation.invokeOnCancellation { pending.getAndSet(null)?.disconnect() }
        CoroutineScope(continuation.context).launch(dispatcher) {
            var connection: HttpsURLConnection? = null
            try {
                if (!continuation.isActive) return@launch
                require(request.payloadBytes <= 1_048_576)
                val path = when (request.kind) {
                    SyncRequestKind.VEHICLE -> "/v1/vehicles/${request.resourceId}"
                    SyncRequestKind.SESSION -> "/v1/sessions/${request.resourceId}"
                    SyncRequestKind.TELEMETRY -> "/v1/telemetry/batches"
                }
                require(Regex("[0-9a-fA-F-]{36}").matches(request.resourceId))
                connection = factory(baseUrl + path)
                pending.set(connection)
                if (!continuation.isActive) return@launch
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = if (request.kind == SyncRequestKind.TELEMETRY) "POST" else "PUT"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                request.idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
                val bytes = request.payloadJson.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes); it.flush() }
                val code = connection.responseCode
                val reply = if (code in 200..299) {
                    val body = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4_096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > SyncAckJson.MAX_BYTES) throw InvalidBody()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    SyncAckJson.decode(body, request)
                } else RemoteReply.HttpError(code, retryAfter(connection.getHeaderField("Retry-After")))
                if (continuation.isActive) continuation.resume(reply)
            } catch (_: InvalidBody) {
                if (continuation.isActive) continuation.resume(RemoteReply.InvalidAcknowledgment)
            } catch (failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(RemoteReply.Unavailable)
            } finally {
                pending.compareAndSet(connection, null)
                connection?.disconnect()
            }
        }
    }

    private class InvalidBody : IOException()
    companion object {
        internal fun forTest(baseUrl: String, factory: (String) -> HttpsURLConnection, dispatcher: CoroutineDispatcher) =
            HttpsSyncTransport(baseUrl, factory, dispatcher, false)

        internal fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
            if (value == null || value.length > 100) return null
            value.trim().toLongOrNull()?.let { return if (it < 0) null else if (it > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else it * 1_000 }
            return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
        }
    }
}
