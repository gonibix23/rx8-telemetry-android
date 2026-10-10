package com.gonibix.rx8

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gonibix.rx8.bluetooth.AndroidBluetoothGateway
import com.gonibix.rx8.connection.BluetoothConnectionScreen
import com.gonibix.rx8.connection.BluetoothConnectionViewModel
import com.gonibix.rx8.ui.SimulatorDashboard
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.Rx8Theme

class MainActivity : ComponentActivity() {
    private val simulatorViewModel by lazy {
        ViewModelProvider(this)[SimulatorViewModel::class.java]
    }
    private val bluetoothViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == BluetoothConnectionViewModel::class.java)
                return BluetoothConnectionViewModel(AndroidBluetoothGateway(applicationContext)) as T
            }
        })[BluetoothConnectionViewModel::class.java]
    }
    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { bluetoothViewModel.refresh() }
    private var scanAfterPermission by mutableStateOf(false)
    private val discoveryPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bluetoothViewModel.refresh()
        if (grants.isNotEmpty() && grants.values.all { it }) {
            // ActivityResult can deliver at ON_START before the screen's lifecycle effect.
            scanAfterPermission = true
        }
    }
    private var receiverRegistered = false
    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED,
                LocationManager.MODE_CHANGED_ACTION,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> bluetoothViewModel.refresh()
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (hasBluetoothPermission()) {
                        try {
                            device?.address?.let(bluetoothViewModel::onDeviceDisconnected)
                        } catch (_: SecurityException) {
                            bluetoothViewModel.refresh()
                        }
                    } else {
                        bluetoothViewModel.refresh()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanAfterPermission = savedInstanceState?.getBoolean("scan_after_permission") ?: false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            Rx8Theme {
                var showBluetooth by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(showBluetooth, scanAfterPermission) {
                    if (!showBluetooth) scanAfterPermission = false
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().selectableGroup()) {
                            Tab(
                                selected = showBluetooth,
                                selectedContentColor = AccentColor,
                                unselectedContentColor = MutedColor,
                                onClick = { showBluetooth = true },
                                text = { Text("Adapter") },
                                modifier = Modifier.weight(1f).testTag("adapter_tab"),
                            )
                            Tab(
                                selected = !showBluetooth,
                                selectedContentColor = AccentColor,
                                unselectedContentColor = MutedColor,
                                onClick = { showBluetooth = false },
                                text = { Text("Simulator") },
                                modifier = Modifier.weight(1f).testTag("simulator_tab"),
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            if (showBluetooth) {
                                val state by bluetoothViewModel.uiState.collectAsStateWithLifecycle()
                                LifecycleStartEffect(bluetoothViewModel) {
                                    bluetoothViewModel.setForeground(true)
                                    onStopOrDispose { bluetoothViewModel.setForeground(false) }
                                }
                                LaunchedEffect(state.isForeground, scanAfterPermission) {
                                    if (state.isForeground && scanAfterPermission) {
                                        scanAfterPermission = false
                                        bluetoothViewModel.startDiscovery()
                                    }
                                }
                                BluetoothConnectionScreen(
                                    state = state,
                                    onRefresh = bluetoothViewModel::refresh,
                                    onRequestPermission = ::requestBluetoothPermission,
                                    onOpenBluetoothSettings = {
                                        openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                    },
                                    onOpenAppSettings = {
                                        openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                        })
                                    },
                                    onSelectDevice = bluetoothViewModel::selectDevice,
                                    onConnect = bluetoothViewModel::connect,
                                    onDisconnect = bluetoothViewModel::disconnect,
                                    onStartObd = bluetoothViewModel::startObd,
                                    onScan = ::requestDiscovery,
                                    onStopScan = bluetoothViewModel::stopDiscovery,
                                    onOpenLocationSettings = {
                                        openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    },
                                )
                            } else {
                                val state by simulatorViewModel.uiState.collectAsStateWithLifecycle()
                                LifecycleStartEffect(simulatorViewModel) {
                                    simulatorViewModel.setActive(true)
                                    onStopOrDispose { simulatorViewModel.setActive(false) }
                                }
                                SimulatorDashboard(
                                    snapshot = state,
                                    onToggleSimulation = simulatorViewModel::toggleSimulation,
                                    onReset = simulatorViewModel::resetSimulation,
                                    onScenarioSelected = simulatorViewModel::selectScenario,
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
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        // Bluetooth stack broadcasts can originate outside the system UID. These actions
        // are protected system broadcasts, so a third-party app cannot send them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothEvents, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothEvents, filter)
        }
        receiverRegistered = true
        bluetoothViewModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scan_after_permission", scanAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        bluetoothViewModel.setForeground(false)
        if (receiverRegistered) {
            unregisterReceiver(bluetoothEvents)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            bluetoothViewModel.refresh()
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
            bluetoothViewModel.startDiscovery()
        } else {
            discoveryPermissions.launch(permissions)
        }
    }
}
