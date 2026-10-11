package com.gonibix.rx8.connection

import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionWakeLockTest {
    @Test
    fun idleConnectionsAndStoppedOrFailedAcquisitionNeverHoldTheCpu() = runTest {
        val lock = FakeWakeLock()
        val owner = AcquisitionWakeLock(backgroundScope, lock) { error("Unexpected acquisition failure") }
        listOf(ObdPhase.IDLE, ObdPhase.STOPPED, ObdPhase.ERROR).forEach { phase ->
            owner.update(state(phase))
            runCurrent()
            assertFalse(lock.held)
        }
        owner.update(state(ObdPhase.LIVE, BluetoothConnectionPhase.DISCONNECTED))
        runCurrent()
        assertTrue(lock.timeouts.isEmpty())
        owner.close()
    }

    @Test
    fun activeAcquisitionRenewsBeforeItsBoundedTimeoutAndIgnoresScreenVisibility() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val lock = FakeWakeLock()
        val owner = AcquisitionWakeLock(scope, lock) { error("Unexpected acquisition failure") }
        try {
            owner.update(state(ObdPhase.INITIALIZING))
            runCurrent()
            assertEquals(listOf(120_000L), lock.timeouts)
            owner.update(state(ObdPhase.DISCOVERING))
            owner.update(state(ObdPhase.LIVE).copy(isForeground = false))
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf(120_000L, 120_000L), lock.timeouts)
            assertTrue(lock.held)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(3, lock.timeouts.size)
            assertTrue(lock.timeouts.all { it > 60_000L && it <= 120_000L })
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun stoppingWhileBoundReleasesImmediatelyAndCancelsRenewal() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val lock = FakeWakeLock()
        val owner = AcquisitionWakeLock(scope, lock) { error("Unexpected acquisition failure") }
        try {
            owner.update(state(ObdPhase.LIVE))
            runCurrent()
            assertTrue(lock.held)
            owner.stop()
            assertFalse(lock.held)
            advanceTimeBy(300_000L)
            runCurrent()
            assertEquals(1, lock.timeouts.size)
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun terminalAcquisitionStatesReleaseWithoutWaitingForServiceDestruction() = runTest {
        val lock = FakeWakeLock()
        val owner = AcquisitionWakeLock(backgroundScope, lock) { error("Unexpected acquisition failure") }
        for (phase in listOf(ObdPhase.ERROR, ObdPhase.STOPPED, ObdPhase.IDLE)) {
            owner.update(state(ObdPhase.LIVE))
            runCurrent()
            assertTrue(lock.held)
            owner.update(state(phase))
            assertFalse(lock.held)
        }
        owner.close()
    }

    @Test
    fun rapidStopAndRestartCannotLetOldRenewalReleaseOrRenewTheNewLock() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val lock = FakeWakeLock()
        val owner = AcquisitionWakeLock(scope, lock) { error("Unexpected acquisition failure") }
        try {
            owner.update(state(ObdPhase.LIVE))
            runCurrent()
            owner.stop()
            owner.update(state(ObdPhase.INITIALIZING))
            runCurrent()
            assertTrue(lock.held)
            assertEquals(2, lock.timeouts.size)
            advanceTimeBy(60_000L)
            runCurrent()
            assertTrue(lock.held)
            assertEquals(3, lock.timeouts.size)
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun acquisitionFailureReleasesAndReportsExactlyOnce() = runTest {
        val lock = FakeWakeLock().apply { failAcquisition = true }
        var failures = 0
        val owner = AcquisitionWakeLock(backgroundScope, lock) { failures++ }
        owner.update(state(ObdPhase.LIVE))
        runCurrent()
        assertEquals(1, failures)
        assertFalse(lock.held)
        owner.close()
        owner.close()
        owner.update(state(ObdPhase.LIVE))
        runCurrent()
        assertEquals(1, failures)
        assertEquals(1, lock.timeouts.size)
    }

    private fun state(
        phase: ObdPhase,
        connection: BluetoothConnectionPhase = BluetoothConnectionPhase.CONNECTED,
    ) = BluetoothConnectionUiState(
        phase = connection,
        isForeground = true,
        obd = ObdSessionSnapshot(phase = phase),
    )

    private class FakeWakeLock : SessionWakeLock {
        val timeouts = mutableListOf<Long>()
        var held = false
        var failAcquisition = false
        override fun acquire(timeoutMillis: Long) {
            timeouts += timeoutMillis
            if (failAcquisition) throw SecurityException("Synthetic acquisition failure")
            held = true
        }
        override fun release() { held = false }
    }
}
