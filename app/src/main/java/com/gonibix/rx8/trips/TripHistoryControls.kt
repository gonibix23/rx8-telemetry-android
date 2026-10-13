package com.gonibix.rx8.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.vehicle.VehicleProfile
import java.util.Locale

/** Filtering affects only history; profile selection and recording ownership live elsewhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripHistoryControls(
    state: TripsUiState,
    vehicles: List<VehicleProfile>,
    busy: Boolean,
    onSelectVehicle: (String?) -> Unit,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
    onNewest: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    var choosingVehicle by rememberSaveable { mutableStateOf(false) }
    val choices = remember(vehicles) {
        vehicles.distinctBy { it.id }.sortedWith(compareBy({ it.alias.lowercase(Locale.ROOT) }, { it.id }))
    }
    val selected = choices.firstOrNull { it.id == state.historyVehicleId }
    val label = if (state.historyVehicleId == null) "All vehicles" else selected?.alias ?: "Saved vehicle"
    if (choosingVehicle) {
        AlertDialog(
            onDismissRequest = { choosingVehicle = false },
            title = { Text("Filter trip history") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This does not change the vehicle selected for recording.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup().testTag("history_vehicle_choices")) {
                        item {
                            VehicleChoice("All vehicles", null, state.historyVehicleId == null, !state.isDeleting) {
                                choosingVehicle = false
                                onSelectVehicle(null)
                            }
                        }
                        items(choices, key = { it.id }) { vehicle ->
                            VehicleChoice(vehicle.alias, vehicle.id, vehicle.id == state.historyVehicleId, !state.isDeleting) {
                                choosingVehicle = false
                                onSelectVehicle(vehicle.id)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingVehicle = false }) { Text("Close") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("trip_history_controls")) {
        Text("Saved trips", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { choosingVehicle = true },
            enabled = !state.isDeleting,
            modifier = Modifier.fillMaxWidth().testTag("history_vehicle_filter"),
        ) { Text("History: $label") }
        state.historyVehicleId?.let { Text("Vehicle ID: $it", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("history_vehicle_id")) }
        Text("Newest first · up to 50 trips per page", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        if (!state.isLoading && state.historyError == null && state.trips.isNotEmpty()) {
            Text(
                when {
                    state.hasNewerTrips && state.hasOlderTrips -> "More trips are available in both directions."
                    state.hasNewerTrips -> "Oldest trips · no older trips."
                    state.hasOlderTrips -> "Newest trips · older trips are available."
                    state.historyVehicleId != null -> "All saved trips for this vehicle are shown."
                    else -> "All saved trips are shown."
                },
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("history_page_status"),
            )
        }
        if (state.hasNewerTrips || state.hasOlderTrips) {
            TripHistoryNavigation(state, busy, onNewer, onOlder, onNewest)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !state.isLoading && !busy, modifier = Modifier.testTag("refresh_trips")) { Text("Refresh") }
            if (state.historyError != null) {
                TextButton(onClick = onRetry, enabled = !state.isLoading && !busy, modifier = Modifier.testTag("retry_trip_history")) { Text("Retry history") }
            }
        }
        if (state.isLoading) {
            Text(if (state.trips.isEmpty()) "Loading saved trips…" else "Loading history… Showing the previous results for this vehicle filter.", color = MutedColor, modifier = Modifier.testTag("history_loading"))
        }
        state.historyError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history_error"))
            if (state.trips.isNotEmpty()) Text("Showing the previous results for this vehicle filter. They have not been refreshed.", color = MutedColor, modifier = Modifier.testTag("history_previous_results"))
        }
        if (!state.isLoading && state.historyError == null && state.trips.isEmpty()) {
            Text(
                if (state.historyVehicleId == null) "No saved trips yet. Select a vehicle and connect an adapter to begin a journey."
                else "No saved trips for this vehicle. Choose All vehicles to browse other recordings.",
                color = MutedColor,
                modifier = Modifier.testTag("empty_trips"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripHistoryNavigation(
    state: TripsUiState,
    busy: Boolean,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
    onNewest: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("trip_history_navigation")) {
        OutlinedButton(onClick = onNewer, enabled = state.hasNewerTrips && !state.isLoading && !busy, modifier = Modifier.testTag("newer_trips")) { Text("Newer") }
        OutlinedButton(onClick = onOlder, enabled = state.hasOlderTrips && !state.isLoading && !busy, modifier = Modifier.testTag("older_trips")) { Text("Older") }
        TextButton(onClick = onNewest, enabled = state.hasNewerTrips && !state.isLoading && !busy, modifier = Modifier.testTag("newest_trips")) { Text("Newest") }
    }
}

@Composable
private fun VehicleChoice(alias: String, id: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .testTag("history_vehicle_${id ?: "all"}").padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(alias, style = MaterialTheme.typography.bodyLarge)
            id?.let { Text("ID: $it", color = MutedColor, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
