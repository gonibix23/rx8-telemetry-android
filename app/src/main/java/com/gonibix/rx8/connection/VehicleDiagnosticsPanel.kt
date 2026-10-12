package com.gonibix.rx8.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.vehicle.VinDetectionPanel

/** Vehicle actions in Live edit mode; adapter diagnostics belong to Adapter. */
@Composable
fun VehicleDiagnosticsPanel(
    state: BluetoothConnectionUiState,
    onOpenVehicles: () -> Unit,
    onReadVin: () -> Unit,
    onReviewVin: (VinResponse) -> Unit,
    onOpenCapabilities: () -> Unit,
    modifier: Modifier = Modifier,
    canReviewVin: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when (state.obd.phase) {
            ObdPhase.IDLE -> "ECU not checked"
            ObdPhase.INITIALIZING, ObdPhase.DISCOVERING -> "ECU check in progress"
            ObdPhase.LIVE -> "ECU readings active"
            ObdPhase.ERROR -> "ECU reading error"
            ObdPhase.STOPPED -> "ECU readings stopped"
        }, modifier = Modifier.testTag("ecu_status"))
        state.automaticMessage?.let {
            Text(it, color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("automatic_session_message"))
        }
        if (state.isAwaitingVehicle) {
            OutlinedButton(onClick = onOpenVehicles, modifier = Modifier.fillMaxWidth().testTag("automatic_choose_vehicle")) { Text("Choose recording vehicle") }
        }
        if (state.obd.ecus.isNotEmpty() && state.obd.phase in setOf(ObdPhase.LIVE, ObdPhase.STOPPED, ObdPhase.ERROR)) {
            OutlinedButton(onClick = onOpenCapabilities, modifier = Modifier.fillMaxWidth().testTag("open_capabilities")) { Text("Vehicle capabilities") }
        }
        if (state.obd.phase == ObdPhase.LIVE) {
            VinDetectionPanel(state.obd.vin, onReadVin, onReviewVin, canReview = canReviewVin)
        }
    }
}
