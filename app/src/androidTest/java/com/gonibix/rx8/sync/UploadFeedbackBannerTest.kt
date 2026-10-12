package com.gonibix.rx8.sync

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Rule
import org.junit.Test

class UploadFeedbackBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleFeedbackFollowsDurableCountsWithoutReopeningSettings() {
        val delivery = mutableStateOf(UploadDeliveryState(UploadDeliveryCounts(1, 0, 1, 0)))
        val runtime = mutableStateOf(UploadRuntimeStatus())
        compose.setContent {
            Rx8Theme { UploadFeedbackBanner(UploadSettings("https://example.test", true), runtime.value, delivery.value) }
        }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("1 trip waiting to upload")
        compose.runOnIdle { runtime.value = UploadRuntimeStatus(isRunning = true) }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Uploading trips…")
        compose.runOnIdle {
            delivery.value = UploadDeliveryState(UploadDeliveryCounts(1, 1, 0, 0))
            runtime.value = UploadRuntimeStatus()
        }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Trips uploaded successfully")
        compose.onNodeWithTag("upload_feedback_detail").assertTextEquals("1 of 1 finished trips confirmed by server. Local copies are kept.")
        compose.runOnIdle { delivery.value = UploadDeliveryState(error = "Storage unavailable") }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Upload status unavailable")
    }
}
