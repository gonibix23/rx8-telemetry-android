package com.gonibix.rx8.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor

data class UploadDeliveryCounts(val total: Long, val confirmed: Long, val waiting: Long, val blocked: Long)
data class UploadDeliveryState(val counts: UploadDeliveryCounts? = null, val error: String? = null)
internal data class UploadFeedback(val title: String, val detail: String, val isError: Boolean = false)

/** Delivery is derived from committed ACKs, never HTTP attempts or an empty sample counter. */
internal fun uploadFeedback(settings: UploadSettings?, runtime: UploadRuntimeStatus, delivery: UploadDeliveryState): UploadFeedback? {
    delivery.error?.let { return UploadFeedback("Upload status unavailable", it, true) }
    val counts = delivery.counts ?: return runtime.error?.let { UploadFeedback("Upload needs attention", it, true) }
    if (counts.total == 0L && !runtime.isRunning && runtime.error == null) return null
    val confirmed = "${counts.confirmed} of ${counts.total} finished trips confirmed by server."
    return when {
        runtime.isRunning -> UploadFeedback("Uploading trips…", "$confirmed Local copies are kept.")
        counts.blocked > 0 -> UploadFeedback("${tripCount(counts.blocked)} ${if (counts.blocked == 1L) "needs" else "need"} attention", "$confirmed Open Trips to review uploads.", true)
        counts.waiting == 0L && counts.total > 0 -> UploadFeedback("Trips uploaded successfully", "$confirmed Local copies are kept.")
        runtime.error != null -> UploadFeedback("Upload could not finish", "${runtime.error} $confirmed", true)
        settings == null -> UploadFeedback("Checking upload settings", "$confirmed Trips remain saved locally.")
        settings.baseUrl == null && settings.boundBaseUrl == null -> UploadFeedback("Trips saved on this phone", "Configure a server in Trips to upload ${counts.waiting} waiting trips.")
        !settings.enabled -> UploadFeedback("Uploads paused", "${counts.waiting} trips waiting locally. $confirmed")
        else -> UploadFeedback("${tripCount(counts.waiting)} waiting to upload", "Waiting for a connection or the next retry. $confirmed")
    }
}

private fun tripCount(count: Long) = "$count ${if (count == 1L) "trip" else "trips"}"

/** Shown across tabs so the user can see a transfer finish without opening server settings. */
@Composable
fun UploadFeedbackBanner(settings: UploadSettings?, runtime: UploadRuntimeStatus, delivery: UploadDeliveryState, modifier: Modifier = Modifier) {
    val feedback = uploadFeedback(settings, runtime, delivery) ?: return
    Surface(modifier.fillMaxWidth().testTag("upload_feedback"), color = CardColor) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(feedback.title, style = MaterialTheme.typography.labelLarge,
                color = if (feedback.isError) MaterialTheme.colorScheme.error else MintColor,
                modifier = Modifier.testTag("upload_feedback_title"))
            Text(feedback.detail, color = MutedColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("upload_feedback_detail"))
            if (runtime.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
