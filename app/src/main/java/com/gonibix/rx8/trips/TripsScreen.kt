package com.gonibix.rx8.trips

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    historyVehicles: List<VehicleProfile> = emptyList(),
    automaticMessage: String? = null,
    canStop: Boolean = true,
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
        onSelectSeries = viewModel::selectSeries,
        onRetrySeries = viewModel::retrySeries,
        historyVehicles = historyVehicles,
        onSelectHistoryVehicle = viewModel::selectHistoryVehicle,
        onNewerTrips = viewModel::newerTrips,
        onOlderTrips = viewModel::olderTrips,
        onNewestTrips = viewModel::newestTrips,
        onRetryHistory = viewModel::retryHistory,
        automaticMessage = automaticMessage,
        canStop = canStop,
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
    onSelectSeries: (TripSeriesKey) -> Unit = {},
    onRetrySeries: () -> Unit = {},
    historyVehicles: List<VehicleProfile> = emptyList(),
    onSelectHistoryVehicle: (String?) -> Unit = {},
    onNewerTrips: () -> Unit = {},
    onOlderTrips: () -> Unit = {},
    onNewestTrips: () -> Unit = {},
    onRetryHistory: () -> Unit = {},
    automaticMessage: String? = null,
    canStop: Boolean = true,
) {
    var deleteId by rememberSaveable(state.historyVehicleId, state.historyRevision, state.selectedTripId) { mutableStateOf<String?>(null) }
    val historyScroll = rememberLazyListState()
    val detailScroll = rememberSaveable(state.selectedTripId, saver = LazyListState.Saver) { LazyListState() }
    var shownRevision by rememberSaveable { mutableLongStateOf(state.historyRevision) }
    var shownFilter by rememberSaveable { mutableStateOf(state.historyVehicleId) }
    LaunchedEffect(state.historyRevision, state.historyVehicleId) {
        if (shownRevision != state.historyRevision || shownFilter != state.historyVehicleId) {
            shownRevision = state.historyRevision
            shownFilter = state.historyVehicleId
            // The history header follows recording, storage, and the optional upload status.
            historyScroll.scrollToItem(if (state.syncStatus == null) 3 else 4)
        }
    }
    BackHandler(enabled = state.selectedTripId != null && deleteId == null) { onCloseDetails() }
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
        state = if (state.selectedTripId == null) historyScroll else detailScroll,
        modifier = modifier.fillMaxSize().testTag("trips_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("LOCAL · OFFLINE", color = MintColor, style = MaterialTheme.typography.labelMedium)
                Text("Trips", style = MaterialTheme.typography.headlineMedium)
                Text("Automatic telemetry journeys for the selected vehicle. No GPS or uploads.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
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
                        Button(onClick = onStop, enabled = canStop && !recording.isStopping, modifier = Modifier.testTag("stop_recording")) {
                            Text(if (recording.isStopping) "Finishing recording…" else "Stop recording")
                        }
                    } else {
                        Text(selectedVehicle?.let { "Ready for ${it.alias}" } ?: "Choose a vehicle first", style = MaterialTheme.typography.titleMedium)
                        Text("A journey starts automatically after connection when a vehicle is selected. After Stop recording, use Start recording to begin another in this connection.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { selectedVehicle?.let(onStart) },
                            enabled = selectedVehicle != null && canStart && !recording.isStarting && !recording.isStopping,
                            modifier = Modifier.testTag("start_recording"),
                        ) { Text(if (recording.isStarting) "Starting recording…" else "Start recording") }
                    }
                    automaticMessage?.let { Text(it, color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trip_automatic_message")) }
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
                Text("App database: ${if (state.isLoadingStorage) "Reading size…" else state.storageBytes?.let(::storageSize) ?: "Not available"}", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trip_storage"))
                state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trip_storage_error")) }
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
                item {
                    TripSensorChart(
                        options = detail.seriesOptions,
                        selected = state.selectedSeries,
                        series = state.series,
                        isLoading = state.isLoadingSeries,
                        error = state.seriesError,
                        durationMillis = detail.session.durationMillis,
                        onSelectSeries = onSelectSeries,
                        onRetry = onRetrySeries,
                    )
                }
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
            item(key = "history_controls") {
                TripHistoryControls(
                    state = state,
                    vehicles = historyVehicles,
                    busy = busy,
                    onSelectVehicle = onSelectHistoryVehicle,
                    onNewer = onNewerTrips,
                    onOlder = onOlderTrips,
                    onNewest = onNewestTrips,
                    onRefresh = onRefresh,
                    onRetry = onRetryHistory,
                )
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
            if (state.trips.isNotEmpty()) {
                item(key = "history_footer") {
                    TripHistoryNavigation(state, busy, onNewerTrips, onOlderTrips, onNewestTrips, tagSuffix = "_footer")
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
