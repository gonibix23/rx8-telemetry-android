package com.gonibix.rx8.dashboard

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.overlay.OverlayUiState
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.util.Locale

/** A presentation-only screen; session ownership and preference persistence stay with its caller. */
@Composable
fun LiveDashboard(
    snapshot: ObdSessionSnapshot,
    favorites: Set<ObdMetric>,
    onToggleFavorite: (ObdMetric) -> Unit,
    onOpenAdapter: () -> Unit,
    modifier: Modifier = Modifier,
    isSimulated: Boolean = false,
    connectionStatus: String? = null,
    cards: List<DashboardCardConfig>? = null,
    onUpdateCard: (DashboardCardConfig) -> Unit = {},
    onMoveCard: (ObdMetric, Int) -> Unit = { _, _ -> },
    overlayState: OverlayUiState? = null,
    onToggleOverlay: () -> Unit = {},
    additionalContent: @Composable () -> Unit = {},
    columns: DashboardColumns = DashboardColumns.ONE,
    onColumnsChange: (DashboardColumns) -> Unit = {},
) {
    if (cards != null) {
        ModularLiveDashboard(snapshot, cards, onToggleFavorite, onUpdateCard, onMoveCard, columns, onColumnsChange,
            overlayState, onToggleOverlay, modifier, isSimulated, additionalContent)
        return
    }
    val model = dashboardModel(snapshot, favorites)
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var sensorQuery by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    LazyColumn(
        modifier.fillMaxSize().imePadding().testTag("live_dashboard"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isSimulated) "SIMULATOR · SYNTHETIC DATA" else "VEHICLE · OBD",
                    color = if (isSimulated) AccentColor else MintColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("dashboard_origin"),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (model.stopped) "Last readings" else "Live dashboard",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenAdapter, modifier = Modifier.testTag("dashboard_open_adapter")) {
                        Text(if (isSimulated) "Simulator" else "Adapter")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sessionLabel(snapshot.phase, isSimulated),
                        color = if (model.stopped) AccentColor else MintColor,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("dashboard_session_status"),
                    )
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("dashboard_session_details")) {
                        Text(if (showDetails) "Hide details" else "Details")
                    }
                }
                if (!isSimulated && connectionStatus != null) Text(
                    connectionStatus,
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("dashboard_bluetooth_status"),
                )
                if (snapshot.phase != ObdPhase.LIVE || showDetails) {
                    Text(snapshot.statusMessage, color = MutedColor, style = MaterialTheme.typography.bodySmall)
                }
                if (showDetails && !isSimulated) {
                    Text(
                        "Protocol reported by adapter: ${snapshot.protocolLabel ?: "Not identified"}",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("dashboard_protocol"),
                    )
                    snapshot.adapterIdentity?.let {
                        Text("Adapter identification: $it", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (model.stopped) Text(
                    if (model.ecus.any { ecu -> ecu.readings.any { it.value != null } })
                        "Retained values are stale. Ages are frozen at stop." else "Updates have stopped. No current measurements are shown.",
                    color = AccentColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!model.hasCapabilities) Text(
                    "Sensor capabilities are not available yet. Connect an adapter to discover them automatically.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("dashboard_no_capabilities"),
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Favorite sensors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        if (showPicker) finishSearch()
                        showPicker = !showPicker
                    }, modifier = Modifier.testTag("dashboard_choose_sensors")) {
                        Text(if (showPicker) "Done" else "Choose sensors")
                    }
                }
                if (model.unavailableFavorites.isNotEmpty()) Text(
                    if (model.unavailableFavorites.size == 1)
                        "A saved favorite is unavailable in this session. Choose sensors to review or remove it." else
                        "${model.unavailableFavorites.size} saved favorites are unavailable in this session. Choose sensors to review or remove them.",
                    color = AccentColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("dashboard_unavailable_favorites"),
                )
                if (showPicker) SensorPicker(model, favorites, sensorQuery, { sensorQuery = it }, finishSearch, onToggleFavorite)
                if (overlayState != null) {
                    TextButton(onClick = onToggleOverlay, modifier = Modifier.testTag("dashboard_toggle_overlay")) {
                        Text(if (overlayState.enabled) "Close floating panel" else "Enable floating panel")
                    }
                    Text(if (overlayState.enabled) "Floating readings appear when you leave this app. Return here to customize them." else
                        "Edit a card to choose floating sensors, then enable the panel to view them over other apps.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                    overlayState.error?.let { Text(it, color = AccentColor, modifier = Modifier.testTag("dashboard_overlay_error")) }
                }
                if (model.hasCapabilities && model.unavailablePrimary.isNotEmpty()) Text(
                    "Not reported as supported: ${model.unavailablePrimary.joinToString { it.label }}.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        model.ecus.forEach { ecu ->
            item(key = "ecu_${ecu.ecuId}") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isSimulated) "Synthetic source · ${ecu.ecuId}" else "Vehicle ECU · ${ecu.ecuId}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("dashboard_ecu_${ecu.ecuId}"),
                    )
                    if (ecu.readings.isEmpty()) Text(
                        "No selected dashboard sensor is reported as supported by this source.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ecu.readings.firstOrNull { it.metric == ObdMetric.ENGINE_RPM }?.let { rpm ->
                        RealRpmGauge(rpm, model.stopped, Modifier.fillMaxWidth())
                    }
                    val otherReadings = ecu.readings.filter { it.metric != ObdMetric.ENGINE_RPM }
                    BoxWithConstraints {
                        val columns = when {
                            maxWidth >= 700.dp -> 3
                            maxWidth >= 340.dp -> 2
                            else -> 1
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            otherReadings.chunked(columns).forEach { readings ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    readings.forEach { reading ->
                                        DashboardCard(reading, model.stopped, Modifier.weight(1f))
                                    }
                                    repeat(columns - readings.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { additionalContent() }
        if (model.ecus.any { it.readings.isNotEmpty() }) item {
            Text(
                if (isSimulated) "Ages use the simulator clock. Simulation pauses when it is not visible." else
                    "Measurements arrive one at a time. Ages use elapsed session time, independent of the phone's clock. Journeys record automatically for the selected vehicle; manage recording in Trips.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun SensorPicker(
    model: DashboardModel,
    favorites: Set<ObdMetric>,
    query: String,
    onQueryChange: (String) -> Unit,
    onFinishSearch: () -> Unit,
    onToggle: (ObdMetric) -> Unit,
    modular: Boolean = false,
) {
    val choices = if (modular) ObdMetric.entries else extraDashboardMetrics.filter { it in model.availableExtras || it in model.unavailableFavorites }
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.map { if (it.matches(HEX_PID_QUERY)) it.drop(2) else it }
    val matches = choices.filter { metric ->
        val pid = String.format(Locale.ROOT, "%02X", metric.pid)
        terms.all { it == "pid" || metric.label.contains(it, ignoreCase = true) || pid.equals(it, ignoreCase = true) }
    }
    Surface(color = CardColor, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (modular) "Every sensor can be added or removed, including RPM, speed and coolant. Unsupported selections stay unavailable; selecting them does not add vehicle commands." else "Essentials stay visible when supported. Favorites apply to every supporting ECU.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search sensors") },
                placeholder = { Text("Name or PID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFinishSearch() }),
                trailingIcon = if (query.isNotEmpty()) ({
                    TextButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("dashboard_sensor_search_clear")
                        .semantics { contentDescription = "Clear sensor search" }) { Text("Clear") }
                }) else null,
                modifier = Modifier.fillMaxWidth().testTag("dashboard_sensor_search"),
            )
            Text("${matches.size} of ${choices.size} sensors", color = MutedColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("dashboard_sensor_search_count"))
            if (choices.isEmpty()) Text("No additional supported sensors are available.", style = MaterialTheme.typography.bodySmall)
            else if (matches.isEmpty()) Text("No sensors match your search.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("dashboard_sensor_search_empty"))
            matches.forEach { metric ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = metric in favorites,
                        onCheckedChange = { onToggle(metric) },
                        modifier = Modifier.testTag("dashboard_favorite_${metric.name.lowercase(Locale.ROOT)}")
                            .semantics { contentDescription = "Favorite: ${metric.label}" },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(metric.label, style = MaterialTheme.typography.bodyMedium)
                        if (modular && metric !in model.availableExtras) Text(
                            if (model.hasCapabilities) "Not reported as supported in this session." else "Support unknown until discovery.",
                            color = AccentColor, style = MaterialTheme.typography.bodySmall)
                        else if (metric in model.unavailableFavorites) Text(
                            if (model.hasCapabilities) "Not supported in this session. Uncheck to remove." else
                                "Support is unknown until live data starts. Uncheck to remove.",
                            color = AccentColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private val HEX_PID_QUERY = Regex("0x[0-9a-f]{2}")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardCard(reading: DashboardReading, stopped: Boolean, modifier: Modifier = Modifier, wide: Boolean = false) {
    val suffix = "${reading.ecuId}_${reading.metric.name.lowercase(Locale.ROOT)}"
    val warning = reading.status != ObdReadingStatus.VALID
    Surface(modifier.heightIn(min = if (wide) 96.dp else 140.dp), shape = RoundedCornerShape(16.dp), color = CardColor) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(reading.metric.label, color = MutedColor, style = MaterialTheme.typography.labelLarge)
            if (reading.isFavorite) Text("FAVORITE", color = MintColor, fontSize = 9.sp, letterSpacing = 1.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    reading.value?.let { formatValue(reading.metric, it) } ?: "—",
                    color = if (warning) AccentColor else WarmWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    modifier = Modifier.testTag("dashboard_value_$suffix"),
                )
                Text(reading.metric.unit, color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReadingStatus(reading, warning, suffix)
                ReadingAge(reading, stopped, suffix)
            } else {
                ReadingStatus(reading, warning, suffix)
                ReadingAge(reading, stopped, suffix)
            }
        }
    }
}

@Composable
internal fun ReadingStatus(reading: DashboardReading, warning: Boolean, suffix: String) {
    Text(
        reading.status.label(),
        color = if (warning) AccentColor else MintColor,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.testTag("dashboard_status_$suffix"),
    )
}

@Composable
internal fun ReadingAge(reading: DashboardReading, stopped: Boolean, suffix: String) {
    Text(
        reading.ageMillis?.let {
            String.format(Locale.ROOT, if (stopped) "Age at stop: %.1f s" else "Age: %.1f s", it / 1_000.0)
        } ?: if (reading.status == null) "No reading received" else "Age unknown",
        color = MutedColor,
        fontSize = 11.sp,
        modifier = Modifier.testTag("dashboard_age_$suffix"),
    )
}

private fun formatValue(metric: ObdMetric, value: Double): String = String.format(Locale.ROOT, "%.${metric.displayDecimals}f", value)

private fun ObdReadingStatus?.label(): String = when (this) {
    ObdReadingStatus.VALID -> "Current"
    ObdReadingStatus.STALE -> "Stale · last value"
    ObdReadingStatus.NO_DATA -> "No data"
    ObdReadingStatus.TIMEOUT -> "Timed out"
    ObdReadingStatus.PARSE_ERROR -> "Unreadable response"
    ObdReadingStatus.ADAPTER_ERROR -> "Adapter error"
    ObdReadingStatus.UNSUPPORTED -> "Unsupported"
    null -> "Awaiting reading"
}

private fun sessionLabel(phase: ObdPhase, simulated: Boolean): String = when (phase) {
    ObdPhase.IDLE -> "Readings not started · ECU not checked"
    ObdPhase.INITIALIZING -> "Preparing adapter · ECU not checked"
    ObdPhase.DISCOVERING -> "Checking vehicle capabilities"
    ObdPhase.LIVE -> if (simulated) "Simulation running" else "Vehicle communication active"
    ObdPhase.STOPPED -> if (simulated) "Simulation stopped" else "Readings stopped"
    ObdPhase.ERROR -> "Reading failed"
}
