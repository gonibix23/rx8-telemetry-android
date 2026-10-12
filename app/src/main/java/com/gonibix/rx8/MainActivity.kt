package com.gonibix.rx8

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gonibix.rx8.connection.BluetoothConnectionScreen
import com.gonibix.rx8.connection.BluetoothConnectionPhase
import com.gonibix.rx8.connection.BluetoothSessionService
import com.gonibix.rx8.connection.VehicleDiagnosticsPanel
import com.gonibix.rx8.capabilities.CapabilityReportViewModel
import com.gonibix.rx8.capabilities.CapabilityReportScreen
import com.gonibix.rx8.capabilities.CapabilityReports
import com.gonibix.rx8.capabilities.rememberCapabilityReportExportAction
import com.gonibix.rx8.dashboard.DashboardPreferencesViewModel
import com.gonibix.rx8.dashboard.LiveDashboard
import com.gonibix.rx8.ui.KeepScreenAwake
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.obd.ObdVinStatus
import com.gonibix.rx8.vehicle.RoomVehicleRepository
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.vehicle.VehicleProfileScreen
import com.gonibix.rx8.vehicle.VehicleProfileViewModel
import com.gonibix.rx8.sync.SyncRuntime
import com.gonibix.rx8.sync.SyncSettingsViewModel
import com.gonibix.rx8.sync.UploadFeedbackBanner
import com.gonibix.rx8.trips.TripRuntime
import com.gonibix.rx8.trips.TripsViewModel
import com.gonibix.rx8.trips.TripsScreen
import com.gonibix.rx8.trips.rememberTripExportActions
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.Rx8Theme

class MainActivity : ComponentActivity() {
    private val vehicleViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == VehicleProfileViewModel::class.java)
                return VehicleProfileViewModel(RoomVehicleRepository(VehicleDatabase.open(applicationContext))) as T
            }
        })[VehicleProfileViewModel::class.java]
    }
    private val dashboardPreferences by lazy {
        ViewModelProvider(this)[DashboardPreferencesViewModel::class.java]
    }
    private val tripRuntime by lazy { TripRuntime.get(applicationContext) }
    private val syncSettingsViewModel by lazy {
        val runtime = SyncRuntime.get(applicationContext)
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SyncSettingsViewModel::class.java)
                return requireNotNull(modelClass.cast(SyncSettingsViewModel(runtime.settingsRepository, runtime.status, runtime::retry)))
            }
        })[SyncSettingsViewModel::class.java]
    }
    private val capabilityReportViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == CapabilityReportViewModel::class.java)
                return CapabilityReportViewModel(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT) as T
            }
        })[CapabilityReportViewModel::class.java]
    }
    private val tripsViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == TripsViewModel::class.java)
                return TripsViewModel(tripRuntime.repository, queueSummaryLoader = tripRuntime.syncStore::summary) as T
            }
        })[TripsViewModel::class.java]
    }
    private var sessionBinder by mutableStateOf<BluetoothSessionService.LocalBinder?>(null)
    private var serviceBound = false
    private var selectedTab by mutableStateOf("adapter")
    private var showCapabilityReport by mutableStateOf(false)
    private var pendingConnection by mutableStateOf<String?>(null)
    private var notificationPermissionPending by mutableStateOf(false)
    private var appForeground by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var enableOverlayAfterPermission by mutableStateOf(false)
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        sessionBinder?.refreshOverlayPermission()
        // Binding can complete after this callback; the visible composition consumes the action.
        if (!Settings.canDrawOverlays(this)) enableOverlayAfterPermission = false
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            sessionBinder = (service as BluetoothSessionService.LocalBinder).also { it.setAppForeground(appForeground) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            sessionBinder = null
        }
    }
    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { sessionBinder?.controller?.refresh() }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        notificationsAllowed = allowed
        // Denial hides the drawer notification, but does not prevent a foreground service.
        notificationPermissionPending = false
    }
    private var scanAfterPermission by mutableStateOf(false)
    private val discoveryPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        sessionBinder?.controller?.refresh()
        if (grants.isNotEmpty() && grants.values.all { it }) {
            // ActivityResult can deliver at ON_START before the screen's lifecycle effect.
            scanAfterPermission = true
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanAfterPermission = savedInstanceState?.getBoolean("scan_after_permission") ?: false
        pendingConnection = savedInstanceState?.getString("pending_connection")?.takeIf { it == "manual" || it == "automatic" }
        notificationPermissionPending = savedInstanceState?.getBoolean("notification_permission_pending") ?: false
        enableOverlayAfterPermission = savedInstanceState?.getBoolean("enable_overlay_after_permission") ?: false
        selectedTab = restoredMainDestination(savedInstanceState?.getString("selected_tab"))
        showCapabilityReport = savedInstanceState?.getBoolean("show_capability_report") ?: false
        if (savedInstanceState == null && intent.action == BluetoothSessionService.ACTION_OPEN_SESSION) {
            selectedTab = "adapter"
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            Rx8Theme {
                val showBluetooth = selectedTab == "adapter"
                val vehicles by vehicleViewModel.uiState.collectAsStateWithLifecycle()
                val favorites by dashboardPreferences.favorites.collectAsStateWithLifecycle()
                val cards by dashboardPreferences.cards.collectAsStateWithLifecycle()
                val favoritesError by dashboardPreferences.error.collectAsStateWithLifecycle()
                val favoritesLoading by dashboardPreferences.isLoading.collectAsStateWithLifecycle()
                val favoritesSaving by dashboardPreferences.isSaving.collectAsStateWithLifecycle()
                val binder = sessionBinder
                val controller = binder?.controller
                val sessionState = controller?.uiState?.collectAsStateWithLifecycle()?.value
                val overlayState = binder?.overlayState?.collectAsStateWithLifecycle()?.value
                LaunchedEffect(binder, appForeground, enableOverlayAfterPermission, overlayState?.permissionGranted) {
                    if (binder != null && appForeground && enableOverlayAfterPermission && Settings.canDrawOverlays(this@MainActivity)) {
                        enableOverlayAfterPermission = false
                        binder.setOverlayEnabled(true)
                    }
                }
                val recording by tripRuntime.recorder.state.collectAsStateWithLifecycle()
                val uploadSettings by syncSettingsViewModel.uiState.collectAsStateWithLifecycle()
                val uploadDelivery by SyncRuntime.get(applicationContext).delivery.collectAsStateWithLifecycle()
                // Document results must survive navigation to Adapter from the session notification.
                val tripExportActions = rememberTripExportActions(tripsViewModel)
                val tripPresentation = rememberSaveableStateHolder()
                val capabilityState by capabilityReportViewModel.uiState.collectAsStateWithLifecycle()
                // Keep document registration stable while tabs change or the Activity is recreated.
                val capabilityExport = rememberCapabilityReportExportAction(capabilityReportViewModel)
                LaunchedEffect(binder, appForeground, sessionState?.automaticConnectionReady,
                    sessionState?.automaticConnectionRequestId, pendingConnection, notificationPermissionPending) {
                    if (binder != null && appForeground) {
                        if (pendingConnection == null && sessionState?.automaticConnectionReady == true) {
                            requestSessionConnection(automatic = true)
                        }
                        if (!notificationPermissionPending) {
                            val request = pendingConnection
                            pendingConnection = null
                            when (request) {
                                "automatic" -> binder.tryAutomaticConnection()
                                "manual" -> binder.connect()
                            }
                        }
                    }
                }
                LaunchedEffect(binder, vehicles.isLoading, vehicles.vehicles.activeVehicleId) {
                    if (!vehicles.isLoading) binder?.refreshSelectedVehicle()
                }
                BackHandler(enabled = selectedTab == "dashboard" && showCapabilityReport) { showCapabilityReport = false }
                // One owner for the shared View flag avoids competing effects when tabs change.
                KeepScreenAwake(
                    (showBluetooth || selectedTab == "dashboard" && !showCapabilityReport) && sessionState?.obd?.phase in setOf(
                        ObdPhase.INITIALIZING, ObdPhase.DISCOVERING, ObdPhase.LIVE,
                    ),
                )
                LaunchedEffect(showBluetooth, showCapabilityReport, scanAfterPermission) {
                    if (!showBluetooth || showCapabilityReport) {
                        scanAfterPermission = false
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        MainNavigationTabs(selectedTab, onSelect = { destination ->
                            selectedTab = destination
                            if (destination != "dashboard") showCapabilityReport = false
                        })
                        UploadFeedbackBanner(uploadSettings.settings, uploadSettings.runtime, uploadDelivery)
                        if (selectedTab == "dashboard") {
                            if (favoritesError != null) {
                                Text(requireNotNull(favoritesError), color = MaterialTheme.colorScheme.error)
                            } else if (favoritesLoading || favoritesSaving) {
                                Text(
                                    if (favoritesLoading) "Loading favorites…" else "Saving favorites…",
                                    color = MutedColor,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            if (selectedTab == "dashboard" && showCapabilityReport) {
                                val currentSnapshot = sessionState?.obd ?: ObdSessionSnapshot()
                                CapabilityReportScreen(
                                    state = capabilityState,
                                    canCaptureLatest = CapabilityReports.canCapture(currentSnapshot),
                                    onCaptureLatest = { capabilityReportViewModel.capture(currentSnapshot) },
                                    onExport = capabilityExport,
                                    onBack = { showCapabilityReport = false },
                                )
                            } else if (showBluetooth) {
                                if (controller == null || sessionState == null) {
                                    Text("Preparing Bluetooth controls…")
                                } else {
                                    val state = sessionState
                                    LifecycleStartEffect(controller) {
                                        controller.setForeground(true)
                                        onStopOrDispose { controller.setForeground(false) }
                                    }
                                    LaunchedEffect(state.isForeground, scanAfterPermission) {
                                        if (state.isForeground && scanAfterPermission) {
                                            scanAfterPermission = false
                                            controller.startDiscovery()
                                        }
                                    }
                                    BluetoothConnectionScreen(
                                        state = state,
                                        onRefresh = controller::refresh,
                                        onRequestPermission = ::requestBluetoothPermission,
                                        onOpenBluetoothSettings = {
                                            openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                        },
                                        onOpenAppSettings = {
                                            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", packageName, null)
                                            })
                                        },
                                        onSelectDevice = controller::selectDevice,
                                        onConnect = { requestSessionConnection() },
                                        onDisconnect = { binder?.disconnect() },
                                        notificationsAllowed = notificationsAllowed,
                                        onSetFavorite = { binder?.setFavoriteAdapter() },
                                        onClearFavorite = { binder?.clearFavoriteAdapter() },
                                        onPauseAutomatic = { binder?.pauseAutomaticConnection() },
                                        onResumeAutomatic = { binder?.resumeAutomaticConnection() },
                                        onScan = ::requestDiscovery,
                                        onStopScan = controller::stopDiscovery,
                                        onOpenLocationSettings = {
                                            openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                        },
                                    )
                                }
                            } else if (selectedTab == "dashboard") {
                                val state = sessionState
                                val snapshot = state?.obd ?: ObdSessionSnapshot(
                                    statusMessage = "Open Adapter to connect. Live readings start automatically.",
                                )
                                LiveDashboard(
                                    snapshot = snapshot,
                                    favorites = favorites,
                                    onToggleFavorite = dashboardPreferences::toggleFavorite,
                                    onOpenAdapter = { selectedTab = "adapter"; showCapabilityReport = false },
                                    cards = cards,
                                    onUpdateCard = dashboardPreferences::updateCard,
                                    onMoveCard = dashboardPreferences::moveCard,
                                    overlayState = overlayState,
                                    onToggleOverlay = ::toggleOverlay,
                                    additionalContent = {
                                        if (state != null) VehicleDiagnosticsPanel(
                                            state = state,
                                            onOpenVehicles = { selectedTab = "vehicles" },
                                            onReadVin = { binder?.readVin() },
                                            canReviewVin = !vehicles.isLoading && !vehicles.isSaving,
                                            onReviewVin = { candidate ->
                                                val current = controller?.uiState?.value?.obd
                                                if (current?.phase == ObdPhase.LIVE && current.vin.status == ObdVinStatus.AVAILABLE &&
                                                    candidate in current.vin.candidates && vehicleViewModel.offerObdVin(candidate)
                                                ) selectedTab = "vehicles"
                                            },
                                            onOpenCapabilities = {
                                                capabilityReportViewModel.capture(controller?.uiState?.value?.obd ?: snapshot)
                                                showCapabilityReport = true
                                            },
                                        )
                                    },
                                    connectionStatus = when (state?.phase) {
                                        BluetoothConnectionPhase.CONNECTED -> "Bluetooth connected"
                                        BluetoothConnectionPhase.CONNECTING -> "Connecting Bluetooth"
                                        BluetoothConnectionPhase.DISCONNECTED -> "Bluetooth disconnected"
                                        null -> "Preparing Bluetooth controls"
                                    },
                                )
                            } else if (selectedTab == "trips") {
                                tripPresentation.SaveableStateProvider("trips") {
                                    TripsScreen(
                                        viewModel = tripsViewModel,
                                        syncSettingsViewModel = syncSettingsViewModel,
                                        exportActions = tripExportActions,
                                        selectedVehicle = vehicles.vehicles.profiles.firstOrNull { it.id == vehicles.vehicles.activeVehicleId },
                                        recording = recording,
                                        canStart = !vehicles.isLoading && !vehicles.isSaving && sessionState?.phase == BluetoothConnectionPhase.CONNECTED && sessionState.obd.phase == ObdPhase.LIVE,
                                        onStart = { vehicle ->
                                            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) binder?.startTrip(vehicle)
                                        },
                                        onStop = { binder?.stopTrip() },
                                        onOpenAdapter = { selectedTab = "adapter"; showCapabilityReport = false },
                                        onOpenVehicles = { selectedTab = "vehicles" },
                                        historyVehicles = vehicles.vehicles.profiles,
                                        automaticMessage = sessionState?.automaticMessage,
                                        canStop = binder != null,
                                    )
                                }
                            } else if (selectedTab == "vehicles") {
                                LaunchedEffect(Unit) { vehicleViewModel.reload() }
                                VehicleProfileScreen(
                                    state = vehicles.vehicles,
                                    onCreate = vehicleViewModel::create,
                                    onSelect = vehicleViewModel::select,
                                    onRename = vehicleViewModel::rename,
                                    onAttachManualVin = vehicleViewModel::attachManualVin,
                                    isLoading = vehicles.isLoading,
                                    isSaving = vehicles.isSaving,
                                    message = vehicles.message,
                                    matchingVehicleId = vehicles.matchingVehicleId,
                                    pendingVin = vehicles.pendingVin,
                                    onAttachDetectedVin = vehicleViewModel::attachDetectedVin,
                                    onCreateDetectedVehicle = vehicleViewModel::createDetectedVehicle,
                                    onDismissDetectedVin = vehicleViewModel::dismissDetectedVin,
                                )

                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appForeground = true
        sessionBinder?.setAppForeground(true)
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        sessionBinder?.refreshOverlayPermission()
        if (!serviceBound) {
            serviceBound = bindService(
                Intent(this, BluetoothSessionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == BluetoothSessionService.ACTION_OPEN_SESSION) {
            selectedTab = "adapter"
            showCapabilityReport = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scan_after_permission", scanAfterPermission)
        outState.putString("pending_connection", pendingConnection)
        outState.putBoolean("notification_permission_pending", notificationPermissionPending)
        outState.putString("selected_tab", selectedTab)
        outState.putBoolean("show_capability_report", showCapabilityReport)
        outState.putBoolean("enable_overlay_after_permission", enableOverlayAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        appForeground = false
        sessionBinder?.setAppForeground(false)
        sessionBinder?.controller?.setForeground(false)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        sessionBinder = null
        super.onStop()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun toggleOverlay() {
        val binder = sessionBinder ?: return
        if (binder.overlayState.value.enabled) {
            enableOverlayAfterPermission = false
            binder.setOverlayEnabled(false)
        } else if (Settings.canDrawOverlays(this)) {
            binder.setOverlayEnabled(true)
        } else {
            enableOverlayAfterPermission = true
            try {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
            } catch (_: android.content.ActivityNotFoundException) {
                enableOverlayAfterPermission = false
                Toast.makeText(this, "Allow display over other apps in your phone's app settings, then enable the overlay.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            sessionBinder?.controller?.refresh()
        }
    }

    private fun requestSessionConnection(automatic: Boolean = false) {
        if (pendingConnection != null || notificationPermissionPending) return
        pendingConnection = if (automatic) "automatic" else "manual"
        val preferences = getSharedPreferences("session_ui", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed &&
            !preferences.getBoolean("notifications_requested", false)
        ) {
            preferences.edit { putBoolean("notifications_requested", true) }
            notificationPermissionPending = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Open Bluetooth, location, or app permissions in your phone settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDiscovery() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            sessionBinder?.controller?.startDiscovery()
        } else {
            discoveryPermissions.launch(permissions)
        }
    }
}

/** Older saved navigation must never reopen a removed demonstration destination. */
internal fun restoredMainDestination(saved: String?): String = when (saved) {
    null -> "adapter"
    "adapter", "dashboard", "trips", "vehicles" -> saved
    else -> "dashboard"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainNavigationTabs(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf("adapter" to "Adapter", "dashboard" to "Live", "trips" to "Trips", "vehicles" to "Vehicles")
    PrimaryScrollableTabRow(
        selectedTabIndex = destinations.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        modifier = modifier.fillMaxWidth().testTag("main_tabs"),
        edgePadding = 0.dp,
        minTabWidth = 72.dp,
    ) {
        destinations.forEach { (id, label) ->
            Tab(
                selected = selected == id,
                selectedContentColor = AccentColor,
                unselectedContentColor = MutedColor,
                onClick = { onSelect(id) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("${id}_tab"),
            ) {
                // The generic Tab content reports its padded text height to the scrollable row.
                // The text-slot baseline layout can constrain scaled text to its intrinsic slot.
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
