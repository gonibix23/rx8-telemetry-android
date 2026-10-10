package com.gonibix.rx8.bluetooth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAdapterConnectionTest {
    @Test
    fun `reads preserve incoming fragments without padding or decoding`() = runBlocking {
        val fragments = ArrayDeque(listOf(byteArrayOf(0x41, 0x0C), byteArrayOf(0x1A), byteArrayOf(0xF8.toByte(), 0x3E)))
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (fragments.isEmpty()) return -1
                val next = fragments.removeFirst()
                next.copyInto(bytes, offset)
                return next.size
            }
        }
        val socket = TestSocket(input = input)
        val connection = StreamAdapterConnection(socket)
        try {
            assertArrayEquals(byteArrayOf(0x41, 0x0C), connection.read())
            assertArrayEquals(byteArrayOf(0x1A), connection.read())
            assertArrayEquals(byteArrayOf(0xF8.toByte(), 0x3E), connection.read())
            assertEquals(0, socket.closeCalls.get())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `remote EOF closes the connection instead of returning an empty fragment`() = runBlocking {
        val socket = TestSocket()
        val connection = StreamAdapterConnection(socket)

        assertTrue(failureOf { connection.read() } is EOFException)
        assertEquals(1, socket.closeCalls.get())
        assertTrue(failureOf { connection.write(byteArrayOf(1)) } is IOException)
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `writes preserve exact bytes without initialization or terminators`() = runBlocking {
        val output = ByteArrayOutputStream()
        val socket = TestSocket(output = output)
        val connection = openAdapterConnection({ socket })
        try {
            assertEquals(1, socket.connectCalls.get())
            assertEquals(0, output.size())
            val bytes = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x0D)
            connection.write(bytes)
            assertArrayEquals(bytes, output.toByteArray())
            connection.write(byteArrayOf())
            assertArrayEquals(bytes, output.toByteArray())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `read timeout closes the live socket and unblocks its worker`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(input = blocked.input, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            failureOf { connection.read(timeoutMillis = 150L) }
        }
        blocked.awaitEntered()

        assertTrue(pending.await() is TimeoutCancellationException)
        blocked.awaitExited()
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `write timeout closes the live socket and unblocks its worker`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(output = blocked.output, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            failureOf { connection.write(byteArrayOf(1), timeoutMillis = 150L) }
        }
        blocked.awaitEntered()

        assertTrue(pending.await() is TimeoutCancellationException)
        blocked.awaitExited()
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `cancelling a read closes the socket without waiting for the read timeout`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(input = blocked.input, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.read(60_000L) }
        blocked.awaitEntered()

        pending.cancelAndJoin()

        blocked.awaitExited()
        assertTrue(pending.isCancelled)
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `cancelling a write closes the socket without waiting for the write timeout`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(output = blocked.output, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            connection.write(byteArrayOf(1), 60_000L)
        }
        blocked.awaitEntered()

        pending.cancelAndJoin()

        blocked.awaitExited()
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `connection timeout closes a pending socket and unblocks connect`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(onConnect = blocked::awaitRelease, onClose = blocked::release)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            failureOf { openAdapterConnection({ socket }, timeoutMillis = 150L) }
        }
        blocked.awaitEntered()

        assertTrue(pending.await() is TimeoutCancellationException)
        blocked.awaitExited()
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `cancelling a pending connect closes its socket`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(onConnect = blocked::awaitRelease, onClose = blocked::release)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            openAdapterConnection({ socket }, timeoutMillis = 60_000L)
        }
        blocked.awaitEntered()

        pending.cancelAndJoin()

        blocked.awaitExited()
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `socket created after cancellation is closed before attempting connect`() = runBlocking {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val socket = TestSocket(onClose = { socketClosed.countDown() })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            openAdapterConnection(socketFactory = {
                factoryEntered.countDown()
                check(releaseFactory.await(2, TimeUnit.SECONDS)) { "Factory was not released" }
                socket
            })
        }
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
        try {
            pending.cancelAndJoin()
        } finally {
            releaseFactory.countDown()
        }

        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
        assertEquals(0, socket.connectCalls.get())
        assertEquals(1, socket.closeCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation after successful connect but before resource delivery closes the socket`() = runTest {
        val ioDispatcher = QueuedDispatcher()
        val socket = TestSocket()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            openAdapterConnection({ socket }, ioDispatcher)
        }
        // Finish connect and resume its continuation, but do not let the caller receive it yet.
        ioDispatcher.runNext()
        assertEquals(1, socket.connectCalls.get())
        assertEquals(0, socket.closeCalls.get())
        assertTrue(!pending.isCompleted)

        pending.cancel()
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, socket.closeCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `write owns a copy so caller mutation cannot change queued bytes`() = runTest {
        val ioDispatcher = QueuedDispatcher()
        val output = ByteArrayOutputStream()
        val socket = TestSocket(output = output)
        val connection = StreamAdapterConnection(socket, ioDispatcher)
        val bytes = byteArrayOf(1, 2, 3)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.write(bytes) }
        bytes.fill(9)
        try {
            ioDispatcher.runNext()
            runCurrent()
            pending.await()

            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `connect failure releases the socket and preserves the exception`() = runBlocking {
        val original = IOException("Synthetic connect failure")
        val socket = TestSocket(onConnect = { throw original })

        val failure = failureOf { openAdapterConnection({ socket }) }
        assertEquals(IOException::class.java, failure.javaClass)
        assertEquals(original.message, failure.message)
        // Coroutine stack recovery may copy the exception while retaining the original as its cause.
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `read and write may run concurrently without a shared-direction deadlock`() = runBlocking {
        val blocked = BlockingOperation()
        val output = ByteArrayOutputStream()
        val socket = TestSocket(input = blocked.input, output = output, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val reading = async(start = CoroutineStart.UNDISPATCHED) { connection.read(60_000L) }
        blocked.awaitEntered()
        try {
            connection.write(byteArrayOf(0x30, 0x31, 0x0D))
            assertArrayEquals(byteArrayOf(0x30, 0x31, 0x0D), output.toByteArray())
        } finally {
            reading.cancelAndJoin()
        }
        blocked.awaitExited()
    }

    @Test
    fun `queued reads are serialized and cancelling one invalidates the socket`() = runBlocking {
        val blocked = BlockingOperation()
        val socket = TestSocket(input = blocked.input, onClose = blocked::release)
        val connection = StreamAdapterConnection(socket)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            failureOf { connection.read(60_000L) }
        }
        blocked.awaitEntered()
        val second = async(start = CoroutineStart.UNDISPATCHED) { connection.read(60_000L) }

        second.cancelAndJoin()

        assertTrue(first.await() is IOException)
        assertEquals(1, blocked.entries.get())
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `close is idempotent and prevents subsequent IO`() = runBlocking {
        val socket = TestSocket(input = ByteArrayInputStream(byteArrayOf(1)))
        val connection = StreamAdapterConnection(socket)

        connection.close()
        connection.close()

        assertTrue(failureOf { connection.read() } is IOException)
        assertTrue(failureOf { connection.write(byteArrayOf(1)) } is IOException)
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `invalid timeouts fail without closing an otherwise usable connection`() = runBlocking {
        val socket = TestSocket(input = ByteArrayInputStream(byteArrayOf(1)))
        val connection = StreamAdapterConnection(socket)
        try {
            assertTrue(failureOf { connection.read(0L) } is IllegalArgumentException)
            assertTrue(failureOf { connection.write(byteArrayOf(1), -1L) } is IllegalArgumentException)
            assertEquals(0, socket.closeCalls.get())
            assertArrayEquals(byteArrayOf(1), connection.read())
        } finally {
            connection.close()
        }
    }

    private suspend fun failureOf(operation: suspend () -> Unit): Exception {
        try {
            operation()
        } catch (failure: Exception) {
            return failure
        }
        throw AssertionError("Expected the operation to fail")
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }
        fun runNext() = tasks.removeFirst().run()
    }
    private class TestSocket(
        override val input: InputStream = ByteArrayInputStream(byteArrayOf()),
        override val output: OutputStream = ByteArrayOutputStream(),
        private val onConnect: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : BlockingBluetoothSocket {
        val connectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()

        override fun connect() {
            connectCalls.incrementAndGet()
            onConnect()
        }

        override fun close() {
            closeCalls.incrementAndGet()
            onClose()
            input.close()
            output.close()
        }
    }

    private class BlockingOperation {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val exited = CountDownLatch(1)
        val entries = AtomicInteger()

        val input = object : InputStream() {
            override fun read(): Int {
                awaitRelease()
                return -1
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                awaitRelease()
                return -1
            }
        }

        val output = object : OutputStream() {
            override fun write(value: Int) = awaitRelease()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = awaitRelease()
        }

        fun awaitRelease() {
            entries.incrementAndGet()
            entered.countDown()
            try {
                check(released.await(2, TimeUnit.SECONDS)) { "Blocked IO was not closed promptly" }
                throw IOException("Synthetic socket closed")
            } finally {
                exited.countDown()
            }
        }

        fun release() = released.countDown()
        fun awaitEntered() = assertTrue("IO did not start", entered.await(2, TimeUnit.SECONDS))
        fun awaitExited() = assertTrue("IO did not unblock", exited.await(2, TimeUnit.SECONDS))
    }
}
