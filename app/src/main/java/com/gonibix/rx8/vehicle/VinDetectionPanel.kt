package com.gonibix.rx8.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.obd.ObdVinSnapshot
import com.gonibix.rx8.obd.ObdVinStatus
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MutedColor

@Composable
fun VinDetectionPanel(
    snapshot: ObdVinSnapshot,
    onRead: () -> Unit,
    onReview: (VinResponse) -> Unit,
    modifier: Modifier = Modifier,
    canReview: Boolean = true,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = CardColor) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vehicle identification", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.message, style = MaterialTheme.typography.bodySmall, color = MutedColor,
                modifier = Modifier.testTag("vin_detection_status"))
            OutlinedButton(onClick = onRead, enabled = snapshot.status != ObdVinStatus.READING,
                modifier = Modifier.testTag("read_vin")) {
                Text(if (snapshot.status == ObdVinStatus.READING) "Reading VIN…" else "Read VIN (optional)")
            }
            snapshot.candidates.forEach { candidate ->
                Text("VIN ending ${candidate.vin.takeLast(6)} · ECU ${candidate.ecu}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onReview(candidate) }, enabled = canReview,
                    modifier = Modifier.testTag("review_vin_${candidate.ecu}")) {
                    Text("Review in Vehicles")
                }
            }
            if (snapshot.candidates.isNotEmpty()) {
                Text("Readings continue while you review. Nothing is saved until you confirm the profile.",
                    style = MaterialTheme.typography.bodySmall, color = MutedColor)
            }
        }
    }
}
