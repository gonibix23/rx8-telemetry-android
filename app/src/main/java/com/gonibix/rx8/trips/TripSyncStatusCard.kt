package com.gonibix.rx8.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MutedColor

/** Sample acknowledgment counts are independent of settings and metadata delivery. */
@Composable
internal fun TripSyncStatusCard(
    status: TripSyncStatus,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("trip_sync_status"),
        color = CardColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Saved sample queue", style = MaterialTheme.typography.titleMedium)
            Text("These counts describe samples, not complete trips or vehicle details. A zero waiting count does not confirm that everything was uploaded.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            when {
                status.isLoading -> Text("Reading local status…", color = MutedColor, modifier = Modifier.testTag("trip_sync_loading"))
                status.error != null -> Text(status.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trip_sync_error"))
                status.summary != null -> {
                    val summary = status.summary
                    Text("Waiting locally: ${summary.pending + summary.sending} samples", modifier = Modifier.testTag("trip_sync_waiting"))
                    Text("Needs attention: ${summary.blocked} samples", modifier = Modifier.testTag("trip_sync_blocked"))
                    if (summary.acknowledged > 0) {
                        Text("Confirmed by server: ${summary.acknowledged} samples", modifier = Modifier.testTag("trip_sync_acknowledged"))
                    }
                }
                else -> Text("Local status is not available. Refresh Trips to try again.", color = MutedColor, modifier = Modifier.testTag("trip_sync_error"))
            }
            Text(
                if (isRecording) "Counts are from the last refresh. The current trip waits until recording has finished."
                else "Counts are from the last refresh. Refresh Trips to update them; saved trips stay on this device.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trip_sync_snapshot"),
            )
        }
    }
}
