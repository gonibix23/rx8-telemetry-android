package com.gonibix.rx8.connection

import androidx.compose.foundation.BorderStroke
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
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MutedColor

@Composable
internal fun FavoriteAdapterControls(
    state: BluetoothConnectionUiState,
    onSet: () -> Unit,
    onClear: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val favorite = state.favoriteAdapter
    val editable = !state.favoritePreferenceLoading && !state.favoritePreferenceSaving
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardColor,
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Favorite adapter", style = MaterialTheme.typography.titleSmall)
            Text(
                favorite?.let { "${it.name.ifBlank { "Unnamed adapter" }} · ${it.address}" } ?: "No favorite selected",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("favorite_adapter"),
            )
            Text(
                when {
                    state.favoritePreferenceLoading -> "Loading favorite…"
                    state.favoritePreferenceSaving -> "Saving preference…"
                    favorite == null -> "Choose an adapter below and save it to enable automatic retries. This does not select a vehicle."
                    state.favoriteAutoConnectPaused -> "Automatic connections paused. Choose Resume to enable them again."
                    else -> "Retries about every 30 seconds while the app is visible and disconnected. Waiting during scans or when Bluetooth access is unavailable."
                },
                color = MutedColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("favorite_automatic_status"),
            )
            if (state.selectedDevice != null && !state.selectedDevice.address.equals(favorite?.address, ignoreCase = true)) {
                OutlinedButton(onClick = onSet, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("set_favorite_adapter")) {
                    Text("Use selected adapter as favorite")
                }
            }
            if (favorite != null) {
                OutlinedButton(
                    onClick = if (state.favoriteAutoConnectPaused) onResume else onPause,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().testTag("toggle_favorite_automatic"),
                ) { Text(if (state.favoriteAutoConnectPaused) "Resume automatic connections" else "Pause automatic connections") }
                TextButton(onClick = onClear, enabled = editable, modifier = Modifier.testTag("clear_favorite_adapter")) { Text("Remove favorite") }
            }
            state.favoriteMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("favorite_error")) }
        }
    }
}
