package com.gonibix.rx8.sync

import org.junit.Assert.*
import org.junit.Test

class UploadFeedbackTest {
    private val enabled = UploadSettings("https://example.test", true)
    private fun state(confirmed: Long = 0, waiting: Long = 0, blocked: Long = 0) =
        UploadDeliveryState(UploadDeliveryCounts(confirmed + waiting + blocked, confirmed, waiting, blocked))

    @Test fun missingAndEmptyStatusNeverClaimsDelivery() {
        assertNull(uploadFeedback(enabled, UploadRuntimeStatus(), UploadDeliveryState()))
        assertNull(uploadFeedback(enabled, UploadRuntimeStatus(), state()))
    }
    @Test fun onlyDurableCompleteTripCountsProduceSuccess() {
        assertEquals("Trips uploaded successfully", uploadFeedback(enabled, UploadRuntimeStatus(), state(confirmed = 2))!!.title)
        assertEquals("1 trip waiting to upload", uploadFeedback(enabled, UploadRuntimeStatus(), state(confirmed = 2, waiting = 1))!!.title)
        assertTrue(uploadFeedback(enabled, UploadRuntimeStatus(), state(confirmed = 2, blocked = 1))!!.isError)
    }
    @Test fun runningRequestIsNotAConfirmation() {
        val feedback = uploadFeedback(enabled, UploadRuntimeStatus(isRunning = true), state(waiting = 1))!!
        assertEquals("Uploading trips…", feedback.title)
        assertTrue(feedback.detail.contains("0 of 1"))
    }
    @Test fun pauseOfflineAndUnconfiguredRemainWaiting() {
        assertEquals("Uploads paused", uploadFeedback(enabled.copy(enabled = false), UploadRuntimeStatus(), state(waiting = 1))!!.title)
        assertEquals("Uploads paused", uploadFeedback(UploadSettings(null, false, enabled.baseUrl), UploadRuntimeStatus(), state(waiting = 1))!!.title)
        assertEquals("Trips saved on this phone", uploadFeedback(UploadSettings(), UploadRuntimeStatus(), state(waiting = 1))!!.title)
        assertTrue(uploadFeedback(enabled, UploadRuntimeStatus(), state(waiting = 1))!!.detail.contains("next retry"))
    }
    @Test fun errorsAreVisibleWithoutInventingMissingCounts() {
        assertTrue(uploadFeedback(enabled, UploadRuntimeStatus(), UploadDeliveryState(error = "Storage unavailable"))!!.isError)
        assertTrue(uploadFeedback(enabled, UploadRuntimeStatus(error = "Connection failed"), state(waiting = 1))!!.detail.contains("Connection failed"))
    }
}
