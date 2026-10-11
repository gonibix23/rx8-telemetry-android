package com.gonibix.rx8.trips

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Keep this holder above tab navigation so a document result retains its intended trip. */
@Stable
class TripExportActions internal constructor(
    private val pendingTripId: State<String?>,
    private val pickerError: State<String?>,
    private val launchAction: (TripSession, TripExportFormat) -> Unit,
    private val clearErrorAction: () -> Unit,
) {
    val isPending: Boolean get() = pendingTripId.value != null
    val error: String? get() = pickerError.value
    fun launch(session: TripSession, format: TripExportFormat) = launchAction(session, format)
    fun clearError() = clearErrorAction()
}

/** Register once in the Activity composition, outside conditionally displayed tabs. */
@Composable
fun rememberTripExportActions(viewModel: TripsViewModel): TripExportActions {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val pendingTripId = rememberSaveable { mutableStateOf<String?>(null) }
    val pendingFormat = rememberSaveable { mutableStateOf<String?>(null) }
    val pickerError = rememberSaveable { mutableStateOf<String?>(null) }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingTripId.value
        val expected = pendingFormat.value == TripExportFormat.JSON.name
        pendingTripId.value = null
        pendingFormat.value = null
        if (uri != null) {
            if (id != null && expected) viewModel.exportTrip(id, TripExportFormat.JSON) {
                resolver.openOutputStream(uri, "wt") ?: throw IOException("The chosen document could not be opened.")
            } else pickerError.value = "The export request could not be restored. Choose Export again."
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val id = pendingTripId.value
        val expected = pendingFormat.value == TripExportFormat.CSV.name
        pendingTripId.value = null
        pendingFormat.value = null
        if (uri != null) {
            if (id != null && expected) viewModel.exportTrip(id, TripExportFormat.CSV) {
                resolver.openOutputStream(uri, "wt") ?: throw IOException("The chosen document could not be opened.")
            } else pickerError.value = "The export request could not be restored. Choose Export again."
        }
    }
    return remember(viewModel, jsonPicker, csvPicker) {
        TripExportActions(
            pendingTripId = pendingTripId,
            pickerError = pickerError,
            launchAction = { session, format ->
                if (pendingTripId.value == null && !viewModel.uiState.value.isExporting && !viewModel.uiState.value.isDeleting) {
                    pendingTripId.value = session.id
                    pendingFormat.value = format.name
                    pickerError.value = null
                    try {
                        val name = "RX8-trip-${session.id}.${format.extension}"
                        when (format) {
                            TripExportFormat.JSON -> jsonPicker.launch(name)
                            TripExportFormat.CSV -> csvPicker.launch(name)
                        }
                    } catch (_: Exception) {
                        pendingTripId.value = null
                        pendingFormat.value = null
                        pickerError.value = "The document picker could not be opened. Try again or choose another document provider."
                    }
                }
            },
            clearErrorAction = { pickerError.value = null },
        )
    }
}

@Composable
fun TripsScreen(
    viewModel: TripsViewModel,
    exportActions: TripExportActions,
    selectedVehicle: VehicleProfile?,
    recording: TripRecordingState,
    canStart: Boolean,
    onStart: (VehicleProfile) -> Unit,
    onStop: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenVehicles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose { }
    }
    LaunchedEffect(recording.session?.id, recording.revision) { viewModel.refresh() }
    TripsContent(
        state = state,
        selectedVehicle = selectedVehicle,
        recording = recording,
        canStart = canStart,
        onStart = onStart,
        onStop = onStop,
        onOpenAdapter = onOpenAdapter,
        onOpenVehicles = onOpenVehicles,
        onRefresh = { exportActions.clearError(); viewModel.refresh() },
        onSelectTrip = viewModel::selectTrip,
        onCloseDetails = viewModel::closeDetails,
        onDelete = viewModel::deleteTrip,
        onExport = exportActions::launch,
        modifier = modifier,
        exportPending = exportActions.isPending,
        pickerError = exportActions.error,
    )
}

/** Stateless content keeps recording ownership and document-provider side effects outside presentation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripsContent(
    state: TripsUiState,
    selectedVehicle: VehicleProfile?,
    recording: TripRecordingState,
    canStart: Boolean,
    onStart: (VehicleProfile) -> Unit,
    onStop: () -> Unit,
    onOpenAdapter: () -> Unit,
    onOpenVehicles: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTrip: (String) -> Unit,
    onCloseDetails: () -> Unit,
    onDelete: (String) -> Unit,
    onExport: (TripSession, TripExportFormat) -> Unit,
    modifier: Modifier = Modifier,
    exportPending: Boolean = false,
    pickerError: String? = null,
) {
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deletion = state.trips.firstOrNull { it.id == deleteId } ?: state.detail?.session?.takeIf { it.id == deleteId }
    val busy = state.isDeleting || state.isExporting || exportPending
    if (deletion != null) {
        val deletable = deletion.status != TripStatus.RECORDING && recording.session?.id != deletion.id && !busy
        AlertDialog(
            onDismissRequest = { if (!state.isDeleting) deleteId = null },
            title = { Text("Delete this trip?") },
            text = { Text("Delete ${deletion.vehicleAlias}'s trip from ${tripDate(deletion.startedAtEpochMillis)} and all its saved samples? This cannot be undone. Exported files are not deleted.") },
            confirmButton = {
                TextButton(
                    enabled = deletable,
                    onClick = { deleteId = null; onDelete(deletion.id) },
                    modifier = Modifier.testTag("confirm_delete_trip"),
                ) { Text("Delete trip") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep trip") } },
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("trips_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("LOCAL · OFFLINE", color = MintColor, style = MaterialTheme.typography.labelMedium)
                Text("Trips", style = MaterialTheme.typography.headlineMedium)
                Text("Save a telemetry session for the selected vehicle. No GPS or uploads.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Surface(color = CardColor, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val active = recording.session
                    if (active != null) {
                        Text("Recording for ${active.vehicleAlias}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recording_vehicle"))
                        Text("${recording.savedSamples} samples saved · vehicle fixed at start", color = MutedColor)
                        if (selectedVehicle?.id != active.vehicleId) Text("Changing the selected vehicle does not change this trip.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onStop, enabled = !recording.isStopping, modifier = Modifier.testTag("stop_recording")) {
                            Text(if (recording.isStopping) "Finishing recording…" else "Stop recording")
                        }
                    } else {
                        Text(selectedVehicle?.let { "Ready for ${it.alias}" } ?: "Choose a vehicle first", style = MaterialTheme.typography.titleMedium)
                        Text("Start only after real live readings are running in Adapter. The simulator cannot be recorded.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { selectedVehicle?.let(onStart) },
                            enabled = selectedVehicle != null && canStart && !recording.isStarting && !recording.isStopping,
                            modifier = Modifier.testTag("start_recording"),
                        ) { Text(if (recording.isStarting) "Starting recording…" else "Start recording") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenAdapter) { Text("Adapter") }
                        TextButton(onClick = onOpenVehicles) { Text("Vehicles") }
                    }
                    recording.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recording_error")) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("App database: ${state.storageBytes?.let(::storageSize) ?: "Not available"}", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trip_storage"))
                Text("Includes profiles and trips. Deleting rows may leave database space available for reuse.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                (pickerError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trips_error")) }
                state.message?.let { Text(it, color = MintColor, modifier = Modifier.testTag("trips_message")) }
                if (state.isExporting) Text("Writing export…", color = MutedColor)
                if (state.isDeleting) Text("Deleting trip…", color = MutedColor)
                if (state.isExporting || state.isDeleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.syncStatus?.let { status ->
            item { TripSyncStatusCard(status, isRecording = recording.session != null) }
        }
        if (state.selectedTripId != null) {
            item {
                TextButton(onClick = onCloseDetails, modifier = Modifier.testTag("back_to_trips")) { Text("Back to history") }
                if (state.isLoadingDetail) Text("Loading trip summary…", color = MutedColor)
            }
            state.detail?.let { detail ->
                item { TripSummary(detail) }
                item { TripSpeedChart(detail) }
                item {
                    TripActions(
                        session = detail.session,
                        enabled = !busy && detail.session.status != TripStatus.RECORDING && recording.session?.id != detail.session.id,
                        onExport = onExport,
                        onDelete = { deleteId = detail.session.id },
                    )
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Latest 100 trips", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_trips")) { Text("Refresh") }
                }
                if (state.isLoading) Text("Loading saved trips…", color = MutedColor)
                else if (state.trips.isEmpty() && state.error == null) Text("No saved trips yet. Start recording explicitly when you are ready.", color = MutedColor, modifier = Modifier.testTag("empty_trips"))
            }
            items(state.trips, key = { it.id }) { session ->
                val active = session.status == TripStatus.RECORDING || recording.session?.id == session.id
                Surface(color = CardColor, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(session.vehicleAlias, style = MaterialTheme.typography.titleMedium)
                        Text("${tripDate(session.startedAtEpochMillis)} · local time", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        Text("${statusLabel(session.status)} · ${durationLabel(session.durationMillis)}", color = if (session.status == TripStatus.INTERRUPTED) AccentColor else MutedColor)
                        if (active) Text("Stop recording to view the complete summary or export.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onSelectTrip(session.id) }, enabled = !active, modifier = Modifier.testTag("trip_details_${session.id}")) { Text("View trip") }
                            TextButton(onClick = { deleteId = session.id }, enabled = !active && !busy, modifier = Modifier.testTag("trip_delete_${session.id}")) { Text("Delete") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Exports contain the vehicle ID, recorded name, timestamps, readings, ECU sources, and quality. They do not contain the VIN or Bluetooth address. Share only with people you choose. Android backup is disabled; exported trips cannot currently be imported to restore the app.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trip_export_privacy"),
            )
        }
    }
}

@Composable
private fun TripSummary(detail: TripAnalysis) {
    val session = detail.session
    Surface(color = CardColor, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.vehicleAlias, style = MaterialTheme.typography.titleLarge)
            Text("${tripDate(session.startedAtEpochMillis)} · local time", color = MutedColor)
            Text("${statusLabel(session.status)} · ${durationLabel(session.durationMillis)}", color = AccentColor)
            session.endReason?.let { Text("End reason: $it", color = MutedColor, style = MaterialTheme.typography.bodySmall) }
            Text("${detail.sampleCount} saved samples · ${detail.validSampleCount} valid", modifier = Modifier.testTag("trip_sample_count"))
            Text("Estimated distance: ${detail.estimatedDistanceKm?.let { String.format(Locale.ROOT, "%.3f km", it) } ?: "Not available"}", modifier = Modifier.testTag("trip_estimated_distance"))
            Text("Calculated from valid speed intervals; this is not the odometer.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            Text("Speed coverage: ${durationLabel(detail.coveredMillis)} · uncovered: ${durationLabel((session.durationMillis - detail.coveredMillis).coerceAtLeast(0))}", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            Text("Peak recorded RPM: ${detail.maxRpm?.let { String.format(Locale.ROOT, "%.0f rpm", it) } ?: "Not available"}")
            Text("Peak speed: ${detail.maxSpeedKph?.let { String.format(Locale.ROOT, "%.0f km/h", it) } ?: "Not available"}")
            detail.speedEcuId?.let { Text("Speed summary source: ECU $it", color = MutedColor, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripActions(session: TripSession, enabled: Boolean, onExport: (TripSession, TripExportFormat) -> Unit, onDelete: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onExport(session, TripExportFormat.JSON) }, enabled = enabled, modifier = Modifier.testTag("export_trip_json")) { Text("Export JSON") }
        OutlinedButton(onClick = { onExport(session, TripExportFormat.CSV) }, enabled = enabled, modifier = Modifier.testTag("export_trip_csv")) { Text("Export CSV") }
        TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("delete_trip_details")) { Text("Delete trip") }
    }
}

@Composable
private fun TripSpeedChart(detail: TripAnalysis) {
    val points = detail.chart
    val values = points.mapNotNull { it.value?.takeIf(Double::isFinite) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recorded speed", style = MaterialTheme.typography.titleMedium)
        if (values.isEmpty()) {
            Text("No valid speed readings available for a chart.", color = MutedColor, modifier = Modifier.testTag("empty_trip_chart"))
        } else {
            val maximum = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            val end = maxOf(detail.session.durationMillis, points.maxOfOrNull { it.elapsedMillis } ?: 0L, 1L)
            Text("0–${String.format(Locale.ROOT, "%.0f", maximum)} km/h · 0–${durationLabel(end)}", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            Canvas(
                Modifier.fillMaxWidth().height(160.dp).testTag("trip_speed_chart").semantics {
                    contentDescription = "Recorded speed chart from ECU ${detail.speedEcuId ?: "unknown"}. Gaps are not joined. Peak ${detail.maxSpeedKph ?: 0.0} kilometers per hour."
                },
            ) {
                val inset = 5.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(1f)
                val height = (size.height - 2 * inset).coerceAtLeast(1f)
                drawLine(MutedColor, Offset(inset, height + inset), Offset(width + inset, height + inset), 1.dp.toPx())
                var previous: Pair<TripChartPoint, Offset>? = null
                points.forEach { point ->
                    val value = point.value?.takeIf { it.isFinite() && it >= 0.0 }
                    if (value == null) previous = null else {
                        val current = Offset(inset + width * (point.elapsedMillis.toDouble() / end).toFloat().coerceIn(0f, 1f), inset + height * (1 - value / maximum).toFloat().coerceIn(0f, 1f))
                        previous?.takeIf { it.first.ecuId == point.ecuId }?.let { drawLine(AccentColor, it.second, current, 2.dp.toPx()) }
                        drawCircle(AccentColor, 2.dp.toPx(), current)
                        previous = point to current
                    }
                }
            }
            Text("A bounded view of the saved speed data. Blank gaps contain no valid coverage; values are not interpolated across them.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusLabel(status: TripStatus): String = when (status) {
    TripStatus.RECORDING -> "Recording"
    TripStatus.COMPLETED -> "Completed"
    TripStatus.INTERRUPTED -> "Interrupted"
}

private fun tripDate(epochMillis: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
private fun durationLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d h %02d min", seconds / 3_600, seconds / 60 % 60)
    else String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60)
}
private fun storageSize(bytes: Long): String = if (bytes < 1_048_576) String.format(Locale.ROOT, "%.1f KiB", bytes / 1_024.0) else String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
