package com.gonibix.rx8.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleFavoriteRetryTest {
    @Test fun explicitFavoriteIsReadyOnceAndFailureWaitsThirtySeconds() = runTest {
        var ready = false
        val retry = VisibleFavoriteRetry(backgroundScope, { ready = it })
        retry.update("favorite", true)
        assertTrue(ready)
        assertTrue(retry.consume())
        assertFalse(retry.consume())
        retry.update("favorite", false) // Attempt owns the transport.
        advanceTimeBy(90_000)
        assertFalse(ready)
        retry.update("favorite", true) // Failed or lost link, now disconnected.
        runCurrent()
        advanceTimeBy(29_999)
        assertFalse(ready)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(ready)
        assertTrue(retry.consume())
    }

    @Test fun hiddenScanningPermissionAndRadioGatesCancelTimerAndDoNotCatchUp() = runTest {
        var ready = false
        val retry = VisibleFavoriteRetry(backgroundScope, { ready = it })
        retry.update("favorite", true)
        retry.consume()
        repeat(4) {
            retry.update("favorite", true)
            runCurrent()
            advanceTimeBy(20_000)
            retry.update("favorite", false)
            advanceTimeBy(120_000)
            runCurrent()
            assertFalse(ready)
        }
        retry.update("favorite", true)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(ready)
    }

    @Test fun repeatedStateAndTickerUpdatesCannotCreateParallelTimersOrAccelerateRetry() = runTest {
        val changes = mutableListOf<Boolean>()
        val retry = VisibleFavoriteRetry(backgroundScope, { changes += it })
        retry.update("favorite", true)
        retry.consume()
        retry.update("favorite", true)
        runCurrent()
        repeat(29) {
            advanceTimeBy(1_000)
            retry.update("favorite", true)
        }
        assertEquals(listOf(true, false), changes)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(true, false, true), changes)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun pauseAndFavoriteRemovalPreventRetriesUntilExplicitlyReenabled() = runTest {
        var ready = false
        val retry = VisibleFavoriteRetry(backgroundScope, { ready = it })
        retry.update("favorite", true)
        retry.consume()
        retry.update(null, false)
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(ready)
        retry.update("favorite", false)
        advanceTimeBy(120_000)
        assertFalse(ready)
        retry.update("favorite", true)
        assertTrue(ready)
    }

    @Test fun manualAttemptDefersRetryAndCloseRevokesAlreadyReadyCallback() = runTest {
        var ready = false
        val retry = VisibleFavoriteRetry(backgroundScope, { ready = it })
        retry.update("favorite", true)
        retry.manualAttempt()
        retry.update("favorite", true)
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(ready)
        retry.close()
        assertFalse(ready)
        assertFalse(retry.consume())
    }
}
