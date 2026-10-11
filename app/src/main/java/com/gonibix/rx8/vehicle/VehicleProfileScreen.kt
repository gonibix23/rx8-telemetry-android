package com.gonibix.rx8.vehicle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.telemetry.vehicle.Vin
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor

/** Forms keep only temporary edits; the caller owns persistence and identity decisions. */
@Composable
fun VehicleProfileScreen(
    state: VehicleState,
    onCreate: (alias: String, vin: String?) -> Unit,
    onSelect: (vehicleId: String) -> Unit,
    onRename: (vehicleId: String, alias: String) -> Unit,
    onAttachManualVin: (vehicleId: String, vin: String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isSaving: Boolean = false,
    message: String? = null,
    matchingVehicleId: String? = null,
    pendingVin: VinResponse? = null,
    onAttachDetectedVin: (vehicleId: String) -> Unit = {},
    onCreateDetectedVehicle: (alias: String) -> Unit = {},
    onDismissDetectedVin: () -> Unit = {},
) {
    var form by remember { mutableStateOf<ProfileForm?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var alias by remember { mutableStateOf("") }
    var vin by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var originalIds by remember { mutableStateOf(emptySet<String>()) }

    fun openForm(kind: ProfileForm, profile: VehicleProfile? = null) {
        form = kind
        targetId = profile?.id
        alias = profile?.alias.orEmpty()
        vin = ""
        submitted = false
        originalIds = state.profiles.map { it.id }.toSet()
    }

    // Keep edits visible on a failed save; close only when the requested change appears in storage.
    LaunchedEffect(state, submitted) {
        if (submitted) {
            val target = state.profiles.firstOrNull { it.id == targetId }
            val saved = when (form) {
                ProfileForm.CREATE, ProfileForm.CREATE_DETECTED -> state.profiles.any { it.id !in originalIds }
                ProfileForm.RENAME -> target?.alias == alias.trim()
                ProfileForm.ATTACH -> target?.vin != null && target.vin == Vin.normalize(vin)
                null -> false
            }
            if (saved) form = null
        }
    }
    LaunchedEffect(pendingVin) {
        if (pendingVin == null && form == ProfileForm.CREATE_DETECTED) form = null
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your vehicles", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Choose the car you are using. Its ID stays the same when you add a VIN.", color = MutedColor)
            }
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Loading saved vehicles…", modifier = Modifier.testTag("vehicles_loading"))
                }
            } else {
                Button(
                    onClick = { openForm(ProfileForm.CREATE) },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("create_vehicle"),
                ) { Text("Create vehicle") }
                if (state.profiles.isEmpty()) {
                    ProfileSurface {
                        Text("No vehicle profiles yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("vehicles_empty"))
                        Text("Give your car a name to start. A VIN is optional and can be added later.", color = MutedColor)
                    }
                }
                if (form == null && message != null) {
                    Text(message, color = AccentColor, modifier = Modifier.testTag("vehicle_message"))
                }
                if (form == null && matchingVehicleId != null) {
                    MatchingVehicleAction(state, matchingVehicleId, isSaving, onSelect)
                }
                if (pendingVin != null) {
                    PendingVinReview(
                        candidate = pendingVin,
                        state = state,
                        isSaving = isSaving,
                        onAttach = onAttachDetectedVin,
                        onSelect = onSelect,
                        onCreate = { openForm(ProfileForm.CREATE_DETECTED) },
                        onDismiss = onDismissDetectedVin,
                    )
                }
                state.profiles.forEach { profile ->
                    VehicleCard(
                        profile = profile,
                        selected = profile.id == state.activeVehicleId,
                        isSaving = isSaving,
                        onSelect = { onSelect(profile.id) },
                        onRename = { openForm(ProfileForm.RENAME, profile) },
                        onAddVin = { openForm(ProfileForm.ATTACH, profile) },
                    )
                }
                Text(
                    "Profiles are saved on this device. Trips and cloud backup are not available yet. Clearing app data or reinstalling removes these IDs; restoring them will require a compatible backup.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    form?.let { kind ->
        val needsAlias = kind != ProfileForm.ATTACH
        val needsVin = kind == ProfileForm.ATTACH || kind == ProfileForm.CREATE
        val normalizedVin = Vin.normalize(vin)
        val validAlias = !needsAlias || (alias.trim().isNotEmpty() && alias.trim().length <= 60)
        val validVin = !needsVin || (vin.isBlank() && kind == ProfileForm.CREATE) || normalizedVin != null
        var showVinInput by remember(kind, targetId) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isSaving) form = null },
            title = {
                Text(when (kind) {
                    ProfileForm.CREATE -> "Create vehicle"
                    ProfileForm.CREATE_DETECTED -> "Create vehicle with detected VIN"
                    ProfileForm.RENAME -> "Rename vehicle"
                    ProfileForm.ATTACH -> "Add a VIN"
                })
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (needsAlias) {
                        OutlinedTextField(
                            value = alias,
                            onValueChange = { alias = it; submitted = false },
                            label = { Text("Vehicle name") },
                            singleLine = true,
                            enabled = !isSaving,
                            isError = alias.trim().length > 60,
                            supportingText = { Text("For example, My RX-8 · up to 60 characters") },
                            modifier = Modifier.fillMaxWidth().testTag("vehicle_form_alias"),
                        )
                    }
                    if (needsVin) {
                        OutlinedTextField(
                            value = vin,
                            onValueChange = { vin = it; submitted = false },
                            label = { Text(if (kind == ProfileForm.CREATE) "VIN (optional)" else "VIN") },
                            singleLine = true,
                            enabled = !isSaving,
                            isError = vin.isNotBlank() && normalizedVin == null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                            visualTransformation = if (showVinInput) VisualTransformation.None else PasswordVisualTransformation(),
                            supportingText = { Text("17 letters and numbers, excluding I, O, and Q.") },
                            modifier = Modifier.fillMaxWidth().testTag("vehicle_form_vin"),
                        )
                        TextButton(onClick = { showVinInput = !showVinInput }) {
                            Text(if (showVinInput) "Hide entered VIN" else "Show entered VIN")
                        }
                    }
                    if (kind == ProfileForm.CREATE_DETECTED && pendingVin != null) {
                        VinDisplay(pendingVin.vin, "vehicle_form_detected_vin")
                        Text("Read from ECU ${pendingVin.ecu}. This creates a separate vehicle ID.", color = MutedColor)
                    }
                    if (kind == ProfileForm.ATTACH) Text("Adding this VIN keeps the vehicle's existing ID.", color = MutedColor)
                    if (submitted && message != null) Text(message, color = AccentColor, modifier = Modifier.testTag("vehicle_form_message"))
                    if (submitted && matchingVehicleId != null) {
                        MatchingVehicleAction(state, matchingVehicleId, isSaving) {
                            onSelect(it)
                            form = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validAlias && validVin && !isSaving,
                    modifier = Modifier.testTag("save_vehicle_form"),
                    onClick = {
                        submitted = true
                        when (kind) {
                            ProfileForm.CREATE -> onCreate(alias.trim(), normalizedVin)
                            ProfileForm.CREATE_DETECTED -> onCreateDetectedVehicle(alias.trim())
                            ProfileForm.RENAME -> targetId?.let { onRename(it, alias.trim()) }
                            ProfileForm.ATTACH -> targetId?.let { id -> normalizedVin?.let { onAttachManualVin(id, it) } }
                        }
                    },
                ) { Text(if (isSaving) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { form = null }, enabled = !isSaving) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun VehicleCard(
    profile: VehicleProfile,
    selected: Boolean,
    isSaving: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onAddVin: () -> Unit,
) {
    ProfileSurface(selected) {
        Text(
            if (selected) "SELECTED VEHICLE" else "SAVED VEHICLE",
            color = if (selected) MintColor else MutedColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("vehicle_selection_${profile.id}"),
        )
        Text(profile.alias, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (profile.vin == null) {
            Text("No VIN added · you can continue without it", color = MutedColor, modifier = Modifier.testTag("vehicle_vin_${profile.id}"))
        } else {
            VinDisplay(profile.vin, "vehicle_vin_${profile.id}")
            Text(
                when (profile.vinSource) {
                    VinSource.MANUAL -> "VIN source: entered manually"
                    VinSource.OBD -> "VIN source: read from ECU ${profile.vinEcuId.orEmpty()}"
                    null -> "VIN source: not recorded"
                },
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("vehicle_vin_source_${profile.id}"),
            )
        }
        Text("Permanent vehicle ID", color = MutedColor, style = MaterialTheme.typography.labelSmall)
        Text(profile.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vehicle_id_${profile.id}"))
        if (!selected) {
            Button(onClick = onSelect, enabled = !isSaving, modifier = Modifier.fillMaxWidth().testTag("select_vehicle_${profile.id}")) {
                Text("Select vehicle")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRename, enabled = !isSaving, modifier = Modifier.testTag("rename_vehicle_${profile.id}")) { Text("Rename") }
            if (profile.vin == null) {
                TextButton(onClick = onAddVin, enabled = !isSaving, modifier = Modifier.testTag("add_vin_${profile.id}")) { Text("Add VIN") }
            }
        }
    }
}

@Composable
private fun PendingVinReview(
    candidate: VinResponse,
    state: VehicleState,
    isSaving: Boolean,
    onAttach: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.profiles.firstOrNull { it.id == state.activeVehicleId }
    val matching = state.profiles.firstOrNull { it.vin == candidate.vin }
    ProfileSurface {
        Text("Review detected VIN", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pending_vin_review"))
        VinDisplay(candidate.vin, "pending_vin")
        Text("Read from ECU ${candidate.ecu}. Choose where it belongs before saving.", color = MutedColor)
        if (matching != null) {
            Text("This VIN already belongs to ${matching.alias}.", color = MutedColor)
            Button(onClick = { onSelect(matching.id) }, enabled = !isSaving, modifier = Modifier.fillMaxWidth().testTag("select_detected_match")) {
                Text(if (matching.id == state.activeVehicleId) "Keep selected vehicle" else "Select ${matching.alias}")
            }
        } else {
            if (active != null && active.vin == null) {
                Button(onClick = { onAttach(active.id) }, enabled = !isSaving, modifier = Modifier.fillMaxWidth().testTag("attach_detected_vin")) {
                    Text("Add VIN to ${active.alias}")
                }
                Text("The permanent vehicle ID will stay the same.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            } else if (active?.vin != null) {
                Text("This VIN differs from ${active.alias}. Create a separate vehicle; the selected vehicle will keep its VIN and ID.", color = AccentColor, modifier = Modifier.testTag("detected_vin_conflict"))
            }
            OutlinedButton(onClick = onCreate, enabled = !isSaving, modifier = Modifier.fillMaxWidth().testTag("create_detected_vehicle")) {
                Text("Create a vehicle with this VIN")
            }
        }
        TextButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.testTag("dismiss_detected_vin")) { Text("Dismiss") }
    }
}

@Composable
private fun MatchingVehicleAction(state: VehicleState, matchingId: String, isSaving: Boolean, onSelect: (String) -> Unit) {
    state.profiles.firstOrNull { it.id == matchingId }?.let { matching ->
        OutlinedButton(onClick = { onSelect(matching.id) }, enabled = !isSaving, modifier = Modifier.testTag("select_matching_vehicle")) {
            Text("Select ${matching.alias}")
        }
    }
}

@Composable
private fun VinDisplay(vin: String, tag: String) {
    // Intentionally not saveable: leaving this composition returns the VIN to its masked form.
    var revealed by remember(vin) { mutableStateOf(false) }
    Column {
        Text(
            if (revealed) vin else "•••••••••••${vin.takeLast(6)}",
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag(tag),
        )
        TextButton(onClick = { revealed = !revealed }, modifier = Modifier.testTag("${tag}_visibility")) {
            Text(if (revealed) "Hide VIN" else "Show VIN")
        }
    }
}

@Composable
private fun ProfileSurface(selected: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) AccentColor else BorderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

private enum class ProfileForm { CREATE, CREATE_DETECTED, RENAME, ATTACH }
