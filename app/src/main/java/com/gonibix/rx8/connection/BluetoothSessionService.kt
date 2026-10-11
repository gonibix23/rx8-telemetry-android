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
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.Elm327Session
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.trips.TripRecorder
import com.gonibix.rx8.trips.TripRuntime
import com.gonibix.rx8.vehicle.VehicleProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Started ownership protects a user-started link when its observing Activity unbinds. */
class BluetoothSessionService : Service() {
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var gateway: BluetoothGateway
    private lateinit var controller: BluetoothSessionController
    private lateinit var recorder: TripRecorder
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

        /** Invoke only from the visible Connect action, after Nearby devices permission is granted. */
        fun connect() = startConnection()

        val tripRecorder: TripRecorder get() = recorder

        /** Called only by the visible Trips action; connecting/starting OBD stays in Adapter. */
        fun startTrip(vehicle: VehicleProfile) {
            val state = controller.uiState.value
            if (!destroyed && foregroundRunning && state.phase == BluetoothConnectionPhase.CONNECTED && state.obd.phase == ObdPhase.LIVE) {
                recorder.start(vehicle, state.obd)
            }
        }

        fun stopTrip() = recorder.stop()

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
        controller = BluetoothSessionController(
            gateway, ownerScope, obdSessionFactory = dependencies.obdSessionFactory,
            onObdSnapshot = recorder::onSnapshot,
            onSessionEnded = { completed -> recorder.stop(completed) },
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
            controller.uiState.map { it.phase to it.obd.phase }.distinctUntilChanged().collect {
                synchronizeResources(controller.uiState.value)
            }
        }
        ownerScope.launch {
            recorder.state.map { Triple(it.session != null, it.isStarting, it.isStopping) }.distinctUntilChanged().collect {
                if (sessionToken != null) synchronizeResources(controller.uiState.value)
            }
        }
        ownerScope.launch {
            // Permission checks remain with the connection owner while no Activity is observing it.
            while (isActive) {
                delay(1_000L)
                if (controller.uiState.value.phase != BluetoothConnectionPhase.DISCONNECTED) {
                    val available = runCatching { gateway.availability() }.getOrNull()
                    if (available != BluetoothAvailability.READY) controller.refresh()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
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
        if (!before.isForeground || before.phase != BluetoothConnectionPhase.DISCONNECTED || before.selectedDevice == null) return
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
            controller.connect()
            synchronizeResources(controller.uiState.value)
        } catch (_: Exception) {
            controller.reportServiceFailure(getString(R.string.session_start_failure))
            finishSession()
        }
    }

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
                recorder.state.value.session != null -> "Recording trip · reading vehicle data"
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
)



