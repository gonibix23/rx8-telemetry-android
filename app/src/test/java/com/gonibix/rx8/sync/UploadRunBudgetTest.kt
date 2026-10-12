package com.gonibix.rx8.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRunBudgetTest {
    @Test fun fastSuccessfulRequestsCanProcess250BatchesButCannotGrowWithoutBound() {
        val budget = UploadRunBudget(nowNanos = { 0L })
        var requests = 0
        while (budget.tryStartRequest()) requests++
        assertEquals(250, requests)
        assertFalse(budget.tryStartRequest())
    }

    @Test fun fourMinuteBudgetStopsBeforeStartingAnotherRequestAtTheBoundary() {
        var now = 0L
        val budget = UploadRunBudget(nowNanos = { now })
        assertTrue(budget.tryStartRequest())
        now = 239_999_999_999L
        assertTrue(budget.tryStartRequest())
        now = 240_000_000_000L
        assertFalse(budget.tryStartRequest())
    }

    @Test fun aSlowFirstRequestConsumesTheBudgetWithoutStartingASecondRequest() {
        var now = 20_000_000L
        val budget = UploadRunBudget(nowNanos = { now })
        assertTrue(budget.tryStartRequest())
        now += 240_000_000_001L
        assertFalse(budget.tryStartRequest())
    }

    @Test fun finalRequestMayCrossBudgetButNoFurtherRequestStarts() {
        var now = 0L
        val budget = UploadRunBudget(nowNanos = { now })
        now = 239_000_000_000L
        assertTrue(budget.tryStartRequest())
        // The existing request timeout, not this between-request budget, controls this call.
        now += 10_000_000_000L
        assertFalse(budget.tryStartRequest())
    }

    @Test fun monotonicClockWrapRetainsElapsedDurationAndInvalidLimitsAreRejected() {
        var now = Long.MAX_VALUE - 1_000_000L
        val budget = UploadRunBudget(maxDurationMillis = 10L, nowNanos = { now })
        assertTrue(budget.tryStartRequest())
        now += 2_000_000L
        assertTrue(budget.tryStartRequest())
        now += 8_000_000L
        assertFalse(budget.tryStartRequest())
        assertTrue(runCatching { UploadRunBudget(maxRequests = 0) }.isFailure)
        assertTrue(runCatching { UploadRunBudget(maxDurationMillis = 0L) }.isFailure)
        assertTrue(runCatching { UploadRunBudget(maxDurationMillis = Long.MAX_VALUE) }.isFailure)
    }
}
