package com.gonibix.rx8.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var now = 10_000L
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }

    @Test fun captureIsExplicitAndFailureKeepsThePriorReportWithoutClaimingSuccess() = runTest(dispatcher) {
        val model = model()
        assertNull(model.uiState.value.report)
        assertFalse(model.capture(ObdSessionSnapshot()))
        assertNotNull(model.uiState.value.error)
        assertNull(model.uiState.value.message)
        assertTrue(model.capture(snapshot()))
        val original = model.uiState.value.report!!
        assertEquals(now, original.capturedAtEpochMillis)
        assertFalse(model.capture(snapshot().copy(elapsedMillis = -1)))
        assertSame(original, model.uiState.value.report)
        assertNull(model.uiState.value.message)
        now += 1_000
        assertTrue(model.capture(snapshot(2_000)))
        assertNotEquals(original.reportId, model.uiState.value.report!!.reportId)
        assertEquals(now, model.uiState.value.report!!.capturedAtEpochMillis)
    }

    @Test fun pickerFreezesExactJsonAndRefusesDoubleLaunchOrReplacement() = runTest(dispatcher) {
        val model = model()
        val pids = mutableSetOf(0x0C, 0x0D)
        val current = snapshot().copy(ecus = listOf(ObdEcuCapabilities("7E8", pids)))
        assertTrue(model.capture(current))
        val report = model.uiState.value.report!!
        val expected = CapabilityReportJson.encode(report)
        assertEquals("RX8-capabilities-${report.reportId}.json", model.beginExport())
        assertTrue(model.uiState.value.isAwaitingDestination)
        assertNull(model.beginExport())
        assertFalse(model.capture(snapshot(3_000)))
        pids.clear()
        val output = ByteArrayOutputStream()
        model.completeExport { output }
        assertFalse(model.uiState.value.isAwaitingDestination)
        assertTrue(model.uiState.value.isExporting)
        assertFalse(model.capture(snapshot(4_000)))
        var duplicateOpened = false
        model.completeExport { duplicateOpened = true; ByteArrayOutputStream() }
        runCurrent()
        assertFalse(duplicateOpened)
        assertEquals(expected, output.toString(Charsets.UTF_8.name()))
        assertSame(report, model.uiState.value.report)
        assertTrue(model.uiState.value.message!!.contains("saved"))
    }

    @Test fun aNewActivityObserverUsingTheSameViewModelStoreRetainsPendingExport() = runTest(dispatcher) {
        val first = model()
        assertTrue(first.capture(snapshot()))
        val report = first.uiState.value.report!!
        assertNotNull(first.beginExport())
        val secondObserver = model()
        assertSame(first, secondObserver)
        assertTrue(secondObserver.uiState.value.isAwaitingDestination)
        val output = ByteArrayOutputStream()
        secondObserver.completeExport { output }
        runCurrent()
        assertEquals(CapabilityReportJson.encode(report), output.toString(Charsets.UTF_8.name()))
        assertFalse(first.uiState.value.isExporting)
    }

    @Test fun cancellingPickerKeepsPreviewAndAllowsAnExplicitRetryWithoutSuccess() = runTest(dispatcher) {
        val model = model()
        model.capture(snapshot())
        val report = model.uiState.value.report
        model.beginExport()
        model.completeExport(null)
        assertFalse(model.uiState.value.isAwaitingDestination)
        assertFalse(model.uiState.value.isExporting)
        assertSame(report, model.uiState.value.report)
        assertNull(model.uiState.value.message)
        assertNull(model.uiState.value.error)
        assertNotNull(model.beginExport())
    }

    @Test fun missingPendingStateNeverOpensOrWritesAReplacementReport() = runTest(dispatcher) {
        val model = model()
        var opened = false
        model.completeExport { opened = true; ByteArrayOutputStream() }
        runCurrent()
        assertFalse(opened)
        assertTrue(model.uiState.value.error!!.contains("no longer available"))
        assertNull(model.uiState.value.report)
        assertNull(model.uiState.value.message)
        model.capture(snapshot())
        val report = model.uiState.value.report
        model.completeExport { opened = true; ByteArrayOutputStream() }
        assertFalse(opened)
        assertSame(report, model.uiState.value.report)
        assertNull(model.uiState.value.message)
    }

    @Test fun unavailablePickerClearsThePendingRequestAndCanBeRetried() = runTest(dispatcher) {
        val model = model()
        model.capture(snapshot())
        model.beginExport()
        model.destinationUnavailable()
        assertFalse(model.uiState.value.isAwaitingDestination)
        assertTrue(model.uiState.value.error!!.contains("picker could not be opened"))
        assertNull(model.uiState.value.message)
        assertNotNull(model.beginExport())
    }

    @Test fun destinationIsWrittenFlushedAndClosedOnIoBeforePublishingSuccess() = runTest(dispatcher) {
        data class OutputObservation(
            val event: String,
            val thread: Thread,
            val isExporting: Boolean,
            val message: String?,
        )
        val ioThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CapabilityReportTestIO").also(ioThread::set)
        }.asCoroutineDispatcher().use { io ->
            val model = CapabilityReportViewModel("0.9.0-dev", 36, io) { now }
            models.put("realIo", model)
            model.capture(snapshot())
            val expected = CapabilityReportJson.encode(model.uiState.value.report!!)
            val observations = CopyOnWriteArrayList<OutputObservation>()
            fun observe(event: String) {
                val state = model.uiState.value
                observations += OutputObservation(event, Thread.currentThread(), state.isExporting, state.message)
            }
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) { observe("write"); super.write(bytes) }
                override fun flush() { observe("flush"); super.flush() }
                override fun close() { observe("close"); super.close() }
            }
            model.beginExport()
            model.completeExport {
                observe("open")
                output
            }
            model.uiState.first { !it.isExporting }
            // Assert in the test coroutine: a callback assertion would abort the export before it can finish.
            assertEquals(listOf("open", "write", "flush", "close"), observations.map { it.event })
            assertNotNull(ioThread.get())
            observations.forEach { observation ->
                // Coroutine debugging decorates thread names, so compare the actual executor thread.
                assertSame(observation.event, ioThread.get(), observation.thread)
                assertTrue(observation.event, observation.isExporting)
                assertNull(observation.event, observation.message)
            }
            assertEquals(expected, output.toString(Charsets.UTF_8.name()))
            assertTrue(model.uiState.value.message!!.contains("saved"))
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun openWriteFlushAndCloseFailuresRemainGenericAndNeverReportSuccess() = runTest(dispatcher) {
        val model = model()
        for (stage in listOf("open", "write", "flush", "close")) {
            model.capture(snapshot())
            model.beginExport()
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) {
                    if (stage == "write") throw IOException("private-provider-detail")
                    super.write(bytes)
                }
                override fun flush() {
                    if (stage == "flush") throw IOException("private-provider-detail")
                    super.flush()
                }
                override fun close() {
                    closed = true
                    if (stage == "close") throw IOException("private-provider-detail")
                    super.close()
                }
            }
            model.completeExport {
                if (stage == "open") throw SecurityException("private-provider-detail")
                output
            }
            runCurrent()
            assertEquals(stage != "open", closed)
            assertFalse(model.uiState.value.isExporting)
            assertNull(model.uiState.value.message)
            assertTrue(model.uiState.value.error!!.contains("may be incomplete"))
            assertFalse(model.uiState.value.error!!.contains("private-provider-detail"))
            assertNotNull(model.uiState.value.report)
        }
    }

    @Test fun cancelledOutputStillClosesAndDoesNotPublishSuccess() = runTest(dispatcher) {
        val model = model()
        model.capture(snapshot())
        model.beginExport()
        var closed = false
        model.completeExport {
            object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray) { throw CancellationException("synthetic cancellation") }
                override fun close() { closed = true; super.close() }
            }
        }
        runCurrent()
        assertTrue(closed)
        assertFalse(model.uiState.value.isExporting)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("cancelled"))
    }

    @Test fun clearingOwnerBeforeQueuedIoRunsDoesNotOpenDestinationOrKeepPendingSnapshot() = runTest(dispatcher) {
        val model = model()
        model.capture(snapshot())
        model.beginExport()
        var opened = false
        model.completeExport { opened = true; ByteArrayOutputStream() }
        models.clear()
        runCurrent()
        assertFalse(opened)
        assertFalse(model.uiState.value.isExporting)
        assertFalse(model.uiState.value.isAwaitingDestination)
        assertNull(model.uiState.value.message)
        assertFalse(model.capture(snapshot()))
        assertNull(model.beginExport())
    }

    private fun model(): CapabilityReportViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
            CapabilityReportViewModel("0.9.0-dev", 36, dispatcher) { now },
        ))
    })[CapabilityReportViewModel::class.java]

    private fun snapshot(elapsed: Long = 1_000) = ObdSessionSnapshot(
        phase = ObdPhase.LIVE, protocolLabel = ObdProtocol.CAN_11_500.description,
        ecus = listOf(ObdEcuCapabilities("7E8", setOf(0x0C, 0x0D))),
        readings = listOf(ObdReading(ObdMetric.SPEED, 0.0, ObdReadingStatus.VALID, "7E8", 9_500, elapsed)),
        effectiveResponsesPerSecond = 3.5, elapsedMillis = elapsed,
    )
}