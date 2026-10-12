package com.gonibix.rx8.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.gonibix.rx8.MainActivity
import com.gonibix.rx8.R
import com.gonibix.rx8.bluetooth.AndroidBluetoothGateway
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.dashboard.DashboardPreferences
import com.gonibix.rx8.overlay.LiveOverlayManager
import com.gonibix.rx8.overlay.OverlayUiState
import com.gonibix.rx8.obd.Elm327Session
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.trips.TripRecorder
import com.gonibix.rx8.trips.TripRuntime
import com.gonibix.rx8.vehicle.VehicleProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Started ownership protects a user-started link when its observing Activity unbinds. */
class BluetoothSessionService : Service() {
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var gateway: BluetoothGateway
    private lateinit var controller: BluetoothSessionController
    private lateinit var recorder: TripRecorder
    private lateinit var automaticTrips: AutomaticTripCoordinator
    private lateinit var lastAdapterStore: LastAdapterStore
    private lateinit var automaticPolicy: AutomaticConnectionPolicy
    private lateinit var favoriteStore: FavoriteAdapterStore
    private lateinit var favoriteRetry: VisibleFavoriteRetry
    private lateinit var overlay: LiveOverlayManager
    private var favorite = FavoriteAdapterPreference()
    private var desiredFavorite = FavoriteAdapterPreference()
    private var favoriteLoaded = false
    private var favoriteSaving = false
    private var favoriteWriteGeneration = 0L
    private var favoriteReady = false
    private var stoppedBeforeFavoriteLoaded = false
    private var lastAdapterAddress: String? = null
    private var lastAdapterLoaded = false
    private var appForeground = false
    private var finishingJob: Job? = null
    private lateinit var powerLock: PowerManager.WakeLock
    private lateinit var acquisitionLock: AcquisitionWakeLock
    private val binder = LocalBinder()
    private var sessionToken: String? = null
    private var foregroundRunning = false
    private var foregroundNotification: Notification? = null
    private var receiverRegistered = false
    private var destroyed = false

    inner class LocalBinder : Binder() {
        val controller: BluetoothSessionController get() = this@BluetoothSessionService.controller

        /** The Activity's STARTED visibility, independently of which tab is being displayed. */
        fun setAppForeground(visible: Boolean) {
            appForeground = visible
            overlay.setAppVisible(visible)
            if (visible) controller.refresh()
            updateAutomaticReadiness()
        }

        /** Every attempt rechecks visibility/readiness after the notification permission flow. */
        fun tryAutomaticConnection() {
            updateAutomaticReadiness()
            if (!controller.uiState.value.automaticConnectionReady) return
            val selectedFavorite = favorite.adapter
            val address = if (selectedFavorite != null) {
                if (!favoriteRetry.consume()) return
                selectedFavorite.address
            } else {
                if (!automaticPolicy.consume()) return
                lastAdapterAddress ?: return
            }
            controller.setAutomaticConnectionReady(false)
            if (!controller.selectRememberedDevice(address, selectedFavorite?.name?.ifBlank { "Favorite adapter" } ?: "Last connected adapter")) {
                updateAutomaticReadiness()
                return
            }
            startConnection()
            updateAutomaticReadiness()
        }

        fun connect() {
            automaticPolicy.consume()
            favoriteRetry.manualAttempt()
            controller.setAutomaticConnectionReady(false)
            startConnection()
            updateAutomaticReadiness()
        }

        fun disconnect() {
            pauseAutomaticConnection()
            controller.disconnect()
        }

        fun setFavoriteAdapter() {
            if (!appForeground || !favoriteLoaded || favoriteSaving) return
            val device = controller.uiState.value.selectedDevice ?: return
            val address = normalizedAdapterAddress(device.address) ?: return
            automaticPolicy.consume()
            saveFavorite(FavoriteAdapterPreference(device.copy(address = address), paused = favorite.adapter != null && favorite.paused))
        }

        fun clearFavoriteAdapter() {
            if (!appForeground || !favoriteLoaded || favoriteSaving) return
            automaticPolicy.consume()
            saveFavorite(FavoriteAdapterPreference())
        }

        fun pauseAutomaticConnection() {
            automaticPolicy.consume()
            AutomaticConnectionRuntime.pauseFavoriteUntilSaved(favoriteStore)
            if (!favoriteLoaded) stoppedBeforeFavoriteLoaded = true
            // Stop immediately, including while a preference write is still in flight.
            favorite = favorite.copy(paused = true)
            favoriteRetry.update(null, false)
            controller.setAutomaticConnectionReady(false)
            if (desiredFavorite.adapter != null) saveFavorite(desiredFavorite.copy(paused = true))
            else controller.setFavoriteState(favorite, loading = !favoriteLoaded, saving = favoriteSaving)
        }

        fun resumeAutomaticConnection() {
            if (!appForeground || !favoriteLoaded || favoriteSaving || favorite.adapter == null) return
            saveFavorite(favorite.copy(paused = false))
        }

        fun readVin() { if (!destroyed && appForeground) controller.readVinFromVisibleApp() }

        val overlayState: StateFlow<OverlayUiState> get() = overlay.state
        fun setOverlayEnabled(enabled: Boolean): Boolean =
            if (enabled && (!appForeground || destroyed)) false else overlay.setEnabled(enabled)
        fun refreshOverlayPermission() = overlay.refreshPermission()

        fun refreshSelectedVehicle() = automaticTrips.refreshSelectedVehicle()

        val tripRecorder: TripRecorder get() = recorder

        /** Called only by the visible Trips action; connecting/starting OBD stays in Adapter. */
        fun startTrip(vehicle: VehicleProfile) {
            val state = controller.uiState.value
            if (!destroyed && appForeground && foregroundRunning && state.phase == BluetoothConnectionPhase.CONNECTED && state.obd.phase == ObdPhase.LIVE) {
                automaticTrips.startManually(vehicle, state.obd)
            }
        }

        fun stopTrip() = automaticTrips.stopRecording()

        internal val hasForegroundSession: Boolean get() = foregroundRunning
        internal val sessionNotification: Notification? get() = foregroundNotification
        internal val hasAcquisitionWakeLock: Boolean get() = powerLock.isHeld
    }

    private val bluetoothEvents = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // A revocation race is handled by refreshing controller readiness.
        override fun onReceive(context: Context, intent: Intent) {
            if (destroyed) return
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                try {
                    val device = intent.bluetoothDevice()
                    if (device != null) controller.onDeviceDisconnected(device.address)
                } catch (_: SecurityException) {
                    controller.refresh()
                }
            } else {
                controller.refresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val dependencies = dependenciesFactory(applicationContext)
        gateway = dependencies.gateway
        recorder = dependencies.recorder ?: TripRuntime.get(applicationContext).recorder
        lastAdapterStore = dependencies.lastAdapterStore ?: AutomaticConnectionRuntime.lastAdapter(applicationContext)
        automaticPolicy = dependencies.automaticPolicy ?: AutomaticConnectionRuntime.policy
        favoriteStore = dependencies.favoriteAdapterStore ?: AutomaticConnectionRuntime.favoriteAdapter(applicationContext)
        controller = BluetoothSessionController(
            gateway, ownerScope, obdSessionFactory = dependencies.obdSessionFactory,
            onObdSnapshot = recorder::onSnapshot,
            onSessionEnded = { completed ->
                if (::automaticTrips.isInitialized) automaticTrips.ended(completed) else recorder.stop(completed)
            },
            autoStartObd = true,
            onConnected = ::prepareConnectedSession,
        )
        favoriteRetry = VisibleFavoriteRetry(ownerScope, { ready ->
            favoriteReady = ready
            publishAutomaticReadiness()
        }, dependencies.favoriteRetryMillis)
        overlay = LiveOverlayManager(applicationContext, ownerScope)
        automaticTrips = AutomaticTripCoordinator(
            recorder, ownerScope,
            selectedVehicle = dependencies.selectedVehicleLoader ?: { TripRuntime.get(applicationContext).selectedVehicle() },
            snapshot = { controller.uiState.value.obd },
            guidance = controller::setAutomaticGuidance,
        )
        powerLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ObdAcquisition",
        ).apply { setReferenceCounted(false) }
        acquisitionLock = AcquisitionWakeLock(ownerScope, object : SessionWakeLock {
            @SuppressLint("WakelockTimeout") // Every acquisition has an explicit bounded timeout and owner cleanup.
            override fun acquire(timeoutMillis: Long) = powerLock.acquire(timeoutMillis)
            override fun release() {
                if (powerLock.isHeld) powerLock.release()
            }
        }) {
            controller.reportServiceFailure(getString(R.string.session_power_failure))
            finishSession()
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        // Bluetooth broadcasts can originate from a privileged process outside the system UID.
        ContextCompat.registerReceiver(this, bluetoothEvents, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        ownerScope.launch {
            controller.uiState.map { listOf(it.phase, it.obd.phase, it.availability, it.devices, it.isScanning) }.distinctUntilChanged().collect {
                synchronizeResources(controller.uiState.value)
                updateAutomaticReadiness()
            }
        }
        ownerScope.launch {
            combine(controller.uiState, DashboardPreferences.openCards(applicationContext)) { state, cards -> state to cards }
                .collect { (state, cards) -> overlay.update(state.obd, state.phase == BluetoothConnectionPhase.CONNECTED, cards) }
        }
        ownerScope.launch {
            try {
                val loaded = AutomaticConnectionRuntime.favoriteOperations.withLock {
                    favoriteStore.load().let {
                        if (AutomaticConnectionRuntime.isFavoritePausePending(favoriteStore)) it.copy(paused = true) else it
                    }
                }
                if (favoriteWriteGeneration == 0L) {
                    favorite = loaded
                    desiredFavorite = loaded
                }
                favoriteLoaded = true
                if (stoppedBeforeFavoriteLoaded && favorite.adapter != null) {
                    favorite = favorite.copy(paused = true)
                    saveFavorite(favorite)
                }
                controller.setFavoriteState(favorite, loading = false, saving = favoriteSaving)
                updateAutomaticReadiness()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.setFavoriteState(favorite, loading = false, saving = false,
                    message = "Favorite adapter could not be loaded. Automatic connections are disabled for this session.")
            }
        }
        ownerScope.launch {
            try {
                lastAdapterAddress = normalizedAdapterAddress(lastAdapterStore.loadAddress())
                lastAdapterLoaded = true
                if (lastAdapterAddress == null) automaticPolicy.consume()
                updateAutomaticReadiness()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (automaticPolicy.consume()) {
                    controller.setAutomaticGuidance(false, "The remembered adapter could not be loaded. Select an adapter to connect.")
                }
            }
        }
        ownerScope.launch {
            recorder.state.map { Triple(it.session != null, it.isStarting, it.isStopping) }.distinctUntilChanged().collect {
                if (sessionToken != null) synchronizeResources(controller.uiState.value)
                updateAutomaticReadiness()
            }
        }
        ownerScope.launch {
            // Permission checks remain with the connection owner while no Activity is observing it.
            while (isActive) {
                delay(1_000L)
                if (appForeground || controller.uiState.value.phase != BluetoothConnectionPhase.DISCONNECTED) {
                    val available = runCatching { gateway.availability() }.getOrNull()
                    if (available != controller.uiState.value.availability) controller.refresh()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        appForeground = false
        overlay.setAppVisible(false)
        updateAutomaticReadiness()
        controller.setForeground(false)
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                if (sessionToken == null && finishingJob?.isActive != true) stopSelfResult(startId)
                // No saved command or Intent can initiate a connection after process recreation.
            }
            ACTION_STOP_SESSION -> {
                val token = intent.getStringExtra(EXTRA_SESSION_TOKEN)
                if (token != null && token == sessionToken) {
                    binder.pauseAutomaticConnection()
                    controller.disconnect()
                    finishSession()
                } else if (sessionToken == null && finishingJob?.isActive != true) {
                    stopSelfResult(startId)
                }
            }
            else -> if (sessionToken == null && finishingJob?.isActive != true) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun startConnection() {
        if (destroyed) return
        val before = controller.uiState.value
        if (!appForeground || before.phase != BluetoothConnectionPhase.DISCONNECTED || before.selectedDevice == null) return
        controller.refresh()
        val ready = controller.uiState.value
        if (ready.availability != BluetoothAvailability.READY || ready.selectedDevice == null) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            controller.reportServiceFailure(getString(R.string.session_permission_failure))
            return
        }
        val token = UUID.randomUUID().toString()
        finishingJob?.cancel()
        sessionToken = token
        try {
            ContextCompat.startForegroundService(this, Intent(this, BluetoothSessionService::class.java).setAction(ACTION_START_SESSION))
            showForegroundNotification(ready.copy(phase = BluetoothConnectionPhase.CONNECTING))
            controller.connectFromVisibleApp()
            synchronizeResources(controller.uiState.value)
        } catch (_: Exception) {
            controller.reportServiceFailure(getString(R.string.session_start_failure))
            finishSession()
        }
    }

    private suspend fun prepareConnectedSession(device: AdapterDevice) {
        val expectedToken = sessionToken
        AutomaticConnectionRuntime.preferenceScope.launch {
            try {
                lastAdapterStore.saveAddress(device.address)
                if (!destroyed && expectedToken == sessionToken) lastAdapterAddress = device.address
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedToken == sessionToken) {
                    controller.setConnectionMessage("Connected, but the adapter could not be remembered. Select it manually next time.")
                }
            }
        }
        automaticTrips.connected()
    }

    private fun saveFavorite(next: FavoriteAdapterPreference) {
        desiredFavorite = next
        favoriteSaving = true
        val generation = ++favoriteWriteGeneration
        controller.setFavoriteState(favorite, loading = !favoriteLoaded, saving = true)
        updateAutomaticReadiness()
        // A notification Stop must survive the service losing its last binding.
        AutomaticConnectionRuntime.preferenceScope.launch {
            try {
                AutomaticConnectionRuntime.favoriteOperations.withLock {
                    try {
                        favoriteStore.save(next)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        // Mark the fail-closed override before another service's load can acquire the gate.
                        AutomaticConnectionRuntime.pauseFavoriteUntilSaved(favoriteStore)
                        throw failure
                    }
                    if (generation == favoriteWriteGeneration) AutomaticConnectionRuntime.favoritePreferenceSaved(favoriteStore)
                }
                if (!destroyed && generation == favoriteWriteGeneration) {
                    favorite = next
                    favoriteLoaded = true
                    favoriteSaving = false
                    controller.setFavoriteState(favorite, loading = false, saving = false)
                    updateAutomaticReadiness()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!destroyed && generation == favoriteWriteGeneration) {
                    favoriteSaving = false
                    // Remain stopped after a failed pause save; never convert a storage error into a retry.
                    favorite = favorite.copy(paused = true)
                    controller.setFavoriteState(favorite, loading = !favoriteLoaded, saving = false,
                        message = "Favorite adapter could not be saved. Automatic connections are paused; try saving or resuming again.")
                    updateAutomaticReadiness()
                }
            }
        }
    }

    private fun updateAutomaticReadiness() {
        if (destroyed || !::favoriteRetry.isInitialized) return
        val state = controller.uiState.value
        val eligible = appForeground && favoriteLoaded && !favoriteSaving && !favorite.paused &&
            state.phase == BluetoothConnectionPhase.DISCONNECTED && state.availability == BluetoothAvailability.READY && !state.isScanning && recorderIsIdle()
        favoriteRetry.update(favorite.adapter?.address, eligible)
        publishAutomaticReadiness()
    }

    private fun publishAutomaticReadiness() {
        if (destroyed || !::controller.isInitialized) return
        val state = controller.uiState.value
        val visibleAndReady = appForeground && favoriteLoaded && !favoriteSaving && !favorite.paused &&
            state.phase == BluetoothConnectionPhase.DISCONNECTED && state.availability == BluetoothAvailability.READY && !state.isScanning && recorderIsIdle()
        val ready = visibleAndReady && if (favorite.adapter != null) favoriteReady else
            lastAdapterLoaded && lastAdapterAddress != null && automaticPolicy.isAvailable()
        controller.setAutomaticConnectionReady(ready)
    }

    private fun recorderIsIdle(): Boolean = recorder.state.value.let { it.session == null && !it.isStarting && !it.isStopping }

    private fun synchronizeResources(state: BluetoothConnectionUiState) {
        if (destroyed) return
        if (state.phase == BluetoothConnectionPhase.DISCONNECTED) {
            if (sessionToken != null || foregroundRunning) finishSession() else acquisitionLock.stop()
        } else if (sessionToken != null) {
            acquisitionLock.update(state)
            try {
                showForegroundNotification(state)
            } catch (_: Exception) {
                controller.reportServiceFailure(getString(R.string.session_start_failure))
                finishSession()
            }
        }
    }

    private fun showForegroundNotification(state: BluetoothConnectionUiState) {
        val token = sessionToken ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Token is part of PendingIntent identity: an old Stop action cannot stop a replacement link.
        val stop = Intent(this, BluetoothSessionService::class.java).setAction(ACTION_STOP_SESSION)
            .setData("rx8-session://stop/$token".toUri()).putExtra(EXTRA_SESSION_TOKEN, token)
        val stopIntent = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            state.phase == BluetoothConnectionPhase.CONNECTING -> R.string.session_connecting
            state.obd.phase == ObdPhase.INITIALIZING || state.obd.phase == ObdPhase.DISCOVERING -> R.string.session_initializing
            state.obd.phase == ObdPhase.LIVE -> R.string.session_reading
            else -> R.string.session_connected
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(getString(R.string.session_title))
            .setContentText(when {
                recorder.state.value.isStopping -> "Saving trip…"
                recorder.state.value.isStarting -> "Starting trip recording…"
                recorder.state.value.session != null -> if (state.obd.phase == ObdPhase.LIVE) {
                    "Recording trip · reading vehicle data"
                } else {
                    "Recording trip · preparing vehicle readings"
                }
                recorder.state.value.error != null -> "Trip recording stopped · check Trips"
                else -> getString(text)
            })
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.session_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foregroundNotification = notification
        foregroundRunning = true
    }

    private fun finishSession() {
        sessionToken = null
        acquisitionLock.stop()
        if (finishingJob?.isActive == true) return
        finishingJob = ownerScope.launch {
            // Keep started ownership until ordered pending writes and the terminal marker commit.
            recorder.state.first { it.session == null && !it.isStarting && !it.isStopping }
            if (sessionToken == null) removeForegroundSession()
        }
    }

    private fun removeForegroundSession() {
        foregroundNotification = null
        if (foregroundRunning) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundRunning = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        foregroundNotification = null
        favoriteRetry.close()
        overlay.close()
        controller.close()
        acquisitionLock.close()
        ownerScope.cancel()
        if (receiverRegistered) unregisterReceiver(bluetoothEvents)
        receiverRegistered = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundRunning = false
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        const val ACTION_OPEN_SESSION = "com.gonibix.rx8.action.OPEN_SESSION"
        private const val ACTION_START_SESSION = "com.gonibix.rx8.action.START_SESSION"
        private const val ACTION_STOP_SESSION = "com.gonibix.rx8.action.STOP_SESSION"
        private const val EXTRA_SESSION_TOKEN = "session_token"
        private const val CHANNEL_ID = "adapter_session"
        internal const val NOTIFICATION_ID = 1001

        // In-process injection for Android lifecycle tests; never selected by an Intent or external app.
        internal var dependenciesFactory: (Context) -> BluetoothServiceDependencies = { context ->
            BluetoothServiceDependencies(AndroidBluetoothGateway(context))
        }
    }
}

internal data class BluetoothServiceDependencies(
    val gateway: BluetoothGateway,
    val obdSessionFactory: ObdSessionFactory = ObdSessionFactory { Elm327Session(it) },
    val recorder: TripRecorder? = null,
    val selectedVehicleLoader: (suspend () -> VehicleProfile?)? = null,
    val lastAdapterStore: LastAdapterStore? = null,
    val automaticPolicy: AutomaticConnectionPolicy? = null,
    val favoriteAdapterStore: FavoriteAdapterStore? = null,
    val favoriteRetryMillis: Long = 30_000L,
)
