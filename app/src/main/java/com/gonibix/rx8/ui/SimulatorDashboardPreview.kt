package com.gonibix.rx8.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.dashboard.LiveDashboard
import com.gonibix.rx8.dashboard.simulatorDashboardSnapshot
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.SimulationScenario
import com.gonibix.rx8.telemetry.TelemetrySnapshot
import java.util.Locale

/** The live layout consumes only the simulator snapshot in this preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimulatorDashboardPreview(
    snapshot: TelemetrySnapshot,
    favorites: Set<ObdMetric>,
    onToggleFavorite: (ObdMetric) -> Unit,
    onToggleSimulation: () -> Unit,
    onScenarioSelected: (SimulationScenario) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("close_dashboard_preview")) {
                Text("Back to simulator")
            }
            OutlinedButton(onClick = onToggleSimulation, modifier = Modifier.testTag("preview_toggle_simulator")) {
                Text(if (snapshot.connection == ConnectionState.CONNECTED) "Stop simulation" else "Start simulation")
            }
        }
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimulationScenario.entries.forEach { scenario ->
                FilterChip(
                    selected = snapshot.scenario == scenario,
                    onClick = { onScenarioSelected(scenario) },
                    label = {
                        Text(when (scenario) {
                            SimulationScenario.NORMAL -> "Normal"
                            SimulationScenario.TIMEOUT -> "Timeout"
                            SimulationScenario.MISSING_DATA -> "Missing data"
                        })
                    },
                    modifier = Modifier.testTag("preview_scenario_${scenario.name.lowercase(Locale.ROOT)}"),
                )
            }
        }
        LiveDashboard(
            snapshot = simulatorDashboardSnapshot(snapshot),
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onOpenAdapter = onBack,
            isSimulated = true,
            modifier = Modifier.weight(1f),
        )
    }
}
