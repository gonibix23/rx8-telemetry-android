package com.gonibix.rx8.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.overlay.OverlayUiState
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.MutedColor

/** Normal mode renders only the saved layout. Editing never changes acquisition or recording. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModularLiveDashboard(
    snapshot: ObdSessionSnapshot,
    cards: List<DashboardCardConfig>,
    onToggleSensor: (ObdMetric) -> Unit,
    onUpdateCard: (DashboardCardConfig) -> Unit,
    onMoveCard: (ObdMetric, Int) -> Unit,
    columns: DashboardColumns,
    onColumnsChange: (DashboardColumns) -> Unit,
    overlayState: OverlayUiState?,
    onToggleOverlay: () -> Unit,
    modifier: Modifier,
    isSimulated: Boolean,
    additionalContent: @Composable () -> Unit,
) {
    val model = modularDashboardModel(snapshot, cards)
    var editMode by rememberSaveable { mutableStateOf(false) }
    var editingMetric by rememberSaveable { mutableStateOf<String?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var sensorQuery by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishSearch: () -> Unit = { focus.clearFocus(); keyboard?.hide() }
    val editing = cards.firstOrNull { it.metric.name == editingMetric }
    if (editMode && editing != null) CardEditor(editing, cards.indexOf(editing), cards.size,
        cards.count { it.showInOverlay }, onUpdateCard, { onMoveCard(editing.metric, it) },
        { onToggleSensor(editing.metric) }, { editingMetric = null })

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editMode) "Edit Live" else if (model.stopped) "Last readings" else "Live",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag("dashboard_mode_title"))
            TextButton(onClick = {
                finishSearch()
                editingMetric = null
                editMode = !editMode
            }, modifier = Modifier.testTag("dashboard_edit_mode")) { Text(if (editMode) "Done" else "Edit") }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            // Narrow windows and large fonts use full-width cards instead of clipping readings.
            val twoColumns = columns == DashboardColumns.TWO &&
                maxWidth - 32.dp >= 280.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            LazyColumn(Modifier.fillMaxSize().testTag("live_dashboard"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isSimulated) item {
                    Text("SIMULATOR · SYNTHETIC DATA", color = AccentColor,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("dashboard_origin"))
                }
                if (snapshot.phase != ObdPhase.LIVE) item {
                    Text(when (snapshot.phase) {
                        ObdPhase.INITIALIZING, ObdPhase.DISCOVERING -> "Waiting for vehicle readings…"
                        ObdPhase.STOPPED -> "Readings stopped. Retained values are stale; ages are frozen at stop."
                        ObdPhase.ERROR -> "Reading interrupted. Retained values are stale."
                        else -> "No live readings. Connect in Adapter."
                    }, color = if (model.stopped) AccentColor else MutedColor,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dashboard_session_status"))
                }
                if (editMode) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Layout", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashboardColumns.entries.forEach { choice -> FilterChip(columns == choice,
                                { onColumnsChange(choice) }, label = { Text(if (choice == DashboardColumns.ONE) "One column" else "Two columns") },
                                modifier = Modifier.testTag("dashboard_columns_${choice.name}")) }
                        }
                        Text("Compact cards share a row in two-column mode. Large cards use the full width. Narrow screens and larger fonts use one column when needed.",
                            color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            if (showPicker) finishSearch()
                            showPicker = !showPicker
                        }, modifier = Modifier.testTag("dashboard_choose_sensors")) { Text(if (showPicker) "Hide sensors" else "Choose sensors") }
                        Text("While a trip is recording, all supported, implemented sensors are included, even sensors hidden here. Edit a card to change its appearance, size, order or floating selection.",
                            color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        if (showPicker) SensorPicker(model, cards.map { it.metric }.toSet(), sensorQuery,
                            { sensorQuery = it }, finishSearch, onToggleSensor, modular = true)
                        if (overlayState != null) {
                            TextButton(onClick = onToggleOverlay, modifier = Modifier.testTag("dashboard_toggle_overlay")) {
                                Text(if (overlayState.enabled) "Disable floating panel" else "Enable floating panel")
                            }
                            Text("Select up to six floating sensors in the card editor. Outside the app, drag a label to move the panel; hold still anywhere on it to close.",
                                color = MutedColor, style = MaterialTheme.typography.bodySmall)
                            overlayState.error?.let { Text(it, color = AccentColor, modifier = Modifier.testTag("dashboard_overlay_error")) }
                        }
                    }
                }
                if (cards.isEmpty()) item {
                    Text("No sensors selected. Choose Edit to add readings.", color = MutedColor, modifier = Modifier.testTag("dashboard_empty_layout"))
                }
                val configurations = cards.associateBy { it.metric }
                model.ecus.forEach { ecu ->
                    if (model.ecus.size > 1) item(key = "source_${ecu.ecuId}") {
                        Text("Vehicle ECU · ${ecu.ecuId}", color = MutedColor, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("dashboard_ecu_${ecu.ecuId}"))
                    }
                    dashboardRows(ecu.readings, configurations, twoColumns).forEach { readings ->
                        item(key = "${ecu.ecuId}_${readings.first().metric.name}") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                readings.forEach { reading -> CustomSensorCard(reading, configurations.getValue(reading.metric), model.stopped,
                                    { editingMetric = reading.metric.name }, Modifier.weight(1f), editingEnabled = editMode,
                                    dense = twoColumns && configurations.getValue(reading.metric).size == DashboardCardSize.COMPACT) }
                                if (twoColumns && readings.size == 1 && configurations.getValue(readings.first().metric).size == DashboardCardSize.COMPACT)
                                    Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (editMode) item { additionalContent() }
            }
        }
    }
}

/** Large cards span the row; an odd compact card retains the same width as its neighbours. */
internal fun dashboardRows(readings: List<DashboardReading>, configs: Map<ObdMetric, DashboardCardConfig>, twoColumns: Boolean): List<List<DashboardReading>> {
    val rows = mutableListOf<List<DashboardReading>>()
    val pending = mutableListOf<DashboardReading>()
    readings.forEach { reading ->
        if (!twoColumns || configs.getValue(reading.metric).size == DashboardCardSize.LARGE) {
            if (pending.isNotEmpty()) { rows += pending.toList(); pending.clear() }
            rows += listOf(reading)
        } else {
            pending += reading
            if (pending.size == 2) { rows += pending.toList(); pending.clear() }
        }
    }
    if (pending.isNotEmpty()) rows += pending.toList()
    return rows
}
