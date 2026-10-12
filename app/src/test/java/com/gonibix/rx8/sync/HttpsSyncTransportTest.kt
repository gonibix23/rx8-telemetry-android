package com.gonibix.rx8.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpsSyncTransportTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val metadata = PreparedRequest(SyncRequestKind.VEHICLE, id, id, id, revision = 1, payloadJson = "{\"frozen\":\"body\"}")
    private val acknowledgment = """{"schemaVersion":1,"resourceId":"$id","revision":1}"""

    @Test fun putsExactImmutableBodyOnBasePathAndNeverEnablesRedirects() = runTest {
        val connection = FakeConnection(200, acknowledgment)
        var url: String? = null
        val transport = HttpsSyncTransport.forTest("https://example.com/base/", { url = it; connection }, StandardTestDispatcher(testScheduler))
        assertTrue(transport.send(metadata) is RemoteReply.MetadataAck)
        assertEquals("https://example.com/base/v1/vehicles/$id", url)
        assertEquals("PUT", connection.requestMethod)
        assertEquals(metadata.payloadJson, connection.output.toString("UTF-8"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertNull(connection.getRequestProperty("Idempotency-Key"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertFalse(transport.isProductionTransport)
    }

    @Test fun batchUsesPostAndItsStableIdempotencyKeyWhileRedirectsAreNotFollowed() = runTest {
        val connection = FakeConnection(307, "")
        val target = PreparedRequest(SyncRequestKind.TELEMETRY, id, id, id, sessionId = id, payloadJson = "{}", sampleIds = listOf(id))
        val transport = HttpsSyncTransport.forTest("https://example.com", { connection }, StandardTestDispatcher(testScheduler))
        assertEquals(RemoteReply.HttpError(307), transport.send(target))
        assertEquals("POST", connection.requestMethod)
        assertEquals(id, connection.getRequestProperty("Idempotency-Key"))
        assertFalse(connection.readBody)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun oversizeOrMalformedSuccessCannotBecomeAnAckAndRetryAfterIsBoundedByCoordinatorPolicy() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (body in listOf("not JSON", " ".repeat(SyncAckJson.MAX_BYTES + 1))) {
            val connection = FakeConnection(200, body)
            assertEquals(RemoteReply.InvalidAcknowledgment, HttpsSyncTransport.forTest("https://example.com", { connection }, dispatcher).send(metadata))
            assertTrue(connection.disconnected)
        }
        assertEquals(120_000L, HttpsSyncTransport.retryAfter("120"))
        assertEquals(Long.MAX_VALUE, HttpsSyncTransport.retryAfter(Long.MAX_VALUE.toString()))
        assertNull(HttpsSyncTransport.retryAfter("-2"))
        assertEquals(30_000L, HttpsSyncTransport.retryAfter("Thu, 01 Jan 1970 00:00:30 GMT", 0))
    }

    @Test fun cancellingABlockedResponseClosesTheConnectionPromptlyOnIo() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val connection = object : FakeConnection(200, acknowledgment) {
            override fun getInputStream(): InputStream {
                entered.complete(Unit)
                check(released.await(3, TimeUnit.SECONDS))
                return super.getInputStream()
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val caller = Thread.currentThread()
            var factoryThread: Thread? = null
            val transport = HttpsSyncTransport.forTest("https://example.com", { factoryThread = Thread.currentThread(); connection }, dispatcher)
            val request = launch { transport.send(metadata) }
            withTimeout(2_000) { entered.await(); request.cancelAndJoin() }
            assertTrue(connection.disconnected)
            assertNotSame(caller, factoryThread)
        }
    }

    private open class FakeConnection(private val code: Int, private val body: String) :
        HttpsURLConnection(URI("https://example.com").toURL()) {
        val output = ByteArrayOutputStream()
        @Volatile var disconnected = false
        var readBody = false
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream { readBody = true; return ByteArrayInputStream(body.toByteArray()) }
        override fun getResponseCode() = code
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
