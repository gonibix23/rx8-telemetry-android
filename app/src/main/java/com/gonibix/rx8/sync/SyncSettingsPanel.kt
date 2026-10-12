package com.gonibix.rx8.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MutedColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncSettingsPanel(
    state: SyncSettingsUiState,
    onSave: (String?, Boolean) -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val busy = state.isLoading || state.isSaving || state.isRetrying
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(settings?.baseUrl, settings?.boundBaseUrl) {
        mutableStateOf(settings?.boundBaseUrl ?: settings?.baseUrl.orEmpty())
    }
    Surface(modifier.fillMaxWidth().testTag("upload_settings"), color = CardColor, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Uploads", style = MaterialTheme.typography.titleMedium)
            val status = when {
                state.isLoading -> "Loading upload settings…"
                settings == null -> "Upload settings unavailable"
                settings.baseUrl == null && settings.boundBaseUrl == null -> "Server not configured"
                !settings.enabled -> "Uploads paused"
                state.runtime.isRunning -> "Sending saved trips…"
                else -> "Uploads enabled"
            }
            Text(status, color = AccentColor, modifier = Modifier.testTag("upload_settings_status"))
            Text("Trips are saved on this device first. Completed and interrupted trips can upload when a server and connection are available.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            (state.error ?: state.runtime.error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("upload_settings_error"))
            }
            (state.message ?: state.runtime.message)?.let {
                Text(it, color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("upload_settings_message"))
            }
            if (settings == null && !state.isLoading) {
                OutlinedButton(onReload, modifier = Modifier.testTag("reload_upload_settings")) { Text("Reload settings") }
            }
            if (settings != null) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle_upload_settings")) {
                    Text(if (expanded) "Hide upload settings" else "Upload settings")
                }
                if (expanded) {
                    val locked = settings.boundBaseUrl != null
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("HTTPS server address") },
                        placeholder = { Text("https://your-server.example") },
                        enabled = !busy,
                        readOnly = locked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("upload_server_url"),
                    )
                    Text(
                        if (locked) "The server address stays fixed after the first upload attempt. You can pause and resume uploads to this server."
                        else "Use the base address supplied for your API. No account or password is required.",
                        color = MutedColor, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("upload_endpoint_policy"),
                    )
                    Text("Uploads include vehicle ID, alias, saved VIN when present, trip times and readings. They do not include Bluetooth addresses or GPS.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { onSave(draft, true) },
                            enabled = !busy && draft.isNotBlank(),
                            modifier = Modifier.testTag("enable_uploads"),
                        ) { Text(if (state.isSaving) "Saving…" else if (settings.enabled) "Save server" else "Enable uploads") }
                        if (settings.enabled) {
                            OutlinedButton(
                                onClick = { onSave(settings.baseUrl ?: settings.boundBaseUrl, false) },
                                enabled = !busy,
                                modifier = Modifier.testTag("pause_uploads"),
                            ) { Text("Pause uploads") }
                        }
                    }
                }
                if (settings.enabled) {
                    OutlinedButton(
                        onClick = onRetry,
                        enabled = !busy && !state.runtime.isRunning,
                        modifier = Modifier.testTag("retry_uploads"),
                    ) { Text(if (state.isRetrying) "Requesting retry…" else "Try uploads again") }
                    Text("Retries connection failures. Rejected or conflicting records still need attention.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
