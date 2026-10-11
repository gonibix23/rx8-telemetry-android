package com.gonibix.rx8.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.vehicle.VinDetectionPanel
import com.gonibix.rx8.ui.LiveObdReadings
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CanvasColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.util.Locale

@Composable
fun BluetoothConnectionScreen(
    state: BluetoothConnectionUiState,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectDevice: (AdapterDevice) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStartObd: () -> Unit = {},
    onReadVin: () -> Unit = {},
    onReviewVin: (VinResponse) -> Unit = {},
    canReviewVin: Boolean = true,
    notificationsAllowed: Boolean = true,
) {
    Column(
        modifier = modifier
            .background(CanvasColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "OBD adapter",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "RX-8 · 231 PS · 2004 EU",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_bluetooth")) {
                    Text("Refresh")
                }
            }
            ConnectionStatus(state)
            if (!notificationsAllowed && state.phase != BluetoothConnectionPhase.DISCONNECTED) {
                Text(
                    "Session notifications are disabled. You can still read data and disconnect here. Enable notifications to see the Stop action outside the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedColor,
                    modifier = Modifier.testTag("session_notifications_disabled"),
                )
                TextButton(onClick = onOpenAppSettings) { Text("Open notification settings") }
            }
            if (state.phase == BluetoothConnectionPhase.CONNECTED) {
                ConnectionAction(state, onConnect, onDisconnect)
                if (state.obd.phase in setOf(ObdPhase.IDLE, ObdPhase.ERROR, ObdPhase.STOPPED)) {
                    Button(
                        onClick = onStartObd,
                        enabled = state.isForeground,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("start_obd"),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Read live data", fontWeight = FontWeight.Bold) }
                    Text(
                        "Start a read-only ECU check, then view the sensors this vehicle supports.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.obd.phase != ObdPhase.IDLE || state.obd.readings.isNotEmpty()) {
                LiveObdReadings(state.obd, Modifier.fillMaxWidth())
            }
            if (state.obd.phase == ObdPhase.LIVE) {
                VinDetectionPanel(state.obd.vin, onReadVin, onReviewVin, canReview = canReviewVin)
            }
            when (state.availability) {
                BluetoothAvailability.PERMISSION_REQUIRED -> {
                    Text(
                        "Allow Nearby devices access to list and connect to Bluetooth devices.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("request_bluetooth_permission"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Allow Bluetooth access")
                    }
                    Text(
                        "If the permission prompt no longer appears, enable Nearby devices in the app's settings.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth().testTag("open_app_settings"),
                    ) {
                        Text("Open app settings")
                    }
                }
                BluetoothAvailability.OFF -> {
                    Text(
                        "Turn on Bluetooth in your phone's settings, then return here and refresh.",
                        color = MutedColor,
                    )
                    SettingsButton(onOpenBluetoothSettings)
                }
                BluetoothAvailability.UNSUPPORTED -> {
                    Text(
                        "This device does not provide a supported Bluetooth adapter.",
                        color = MutedColor,
                    )
                }
                BluetoothAvailability.LOCATION_DISABLED -> {
                    Text("Location services are off. Enable them to discover nearby devices on this Android version.", color = MutedColor)
                    OutlinedButton(onClick = onOpenLocationSettings) { Text("Open location settings") }
                }
                BluetoothAvailability.READY -> {
                    if (state.phase != BluetoothConnectionPhase.CONNECTED) {
                        DiscoveryControls(state, onScan, onStopScan, onOpenLocationSettings, onOpenAppSettings)
                        Text(
                            "Available Bluetooth devices",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.devices.isEmpty()) {
                            Text(
                                "No devices listed yet. Scan nearby with your adapter powered on, or pair it in Bluetooth settings.",
                                color = MutedColor,
                                modifier = Modifier.testTag("no_paired_devices"),
                            )
                        } else {
                            Text(
                                "Look for OBDII and match its MAC address with Car Scanner.",
                                color = MutedColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            state.devices.forEachIndexed { index, device ->
                                PairedDeviceRow(
                                    device = device,
                                    selected = device.address.equals(state.selectedDevice?.address, ignoreCase = true),
                                    enabled = state.phase == BluetoothConnectionPhase.DISCONNECTED,
                                    onSelected = { onSelectDevice(device) },
                                    modifier = Modifier.testTag("paired_device_$index"),
                                )
                            }
                        }
                        if (state.phase == BluetoothConnectionPhase.DISCONNECTED && state.selectedDevice != null) {
                            Text(
                                "Disconnect Car Scanner before connecting here. Android may show a pairing prompt for a nearby device.",
                                color = MutedColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        ConnectionAction(state, onConnect, onDisconnect)
                        SettingsButton(onOpenBluetoothSettings)
                    }
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    color = AccentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("connection_message"),
                )
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardColor,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Read-only vehicle data",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Choose Read live data to request supported sensor readings. The app does not clear faults or control vehicle systems.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "The connection and active readings continue when you change tabs, lock the phone, or use another app. Choose Disconnect here or Stop in the session notification to end them. The live-data screen stays awake while visible.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Trip recording and API sync are not available yet. Readings are not saved or uploaded.",
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryControls(
    state: BluetoothConnectionUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state.isScanning -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Scanning nearby Bluetooth devices…",
                        color = MutedColor,
                        modifier = Modifier.testTag("discovery_status"),
                    )
                }
                OutlinedButton(
                    onClick = onStopScan,
                    modifier = Modifier.fillMaxWidth().testTag("stop_discovery"),
                ) { Text("Stop scan") }
            }
            state.discoveryAvailability == BluetoothAvailability.READY -> Button(
                onClick = onScan,
                enabled = state.isForeground && state.phase == BluetoothConnectionPhase.DISCONNECTED,
                modifier = Modifier.fillMaxWidth().testTag("start_discovery"),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Scan nearby") }
            state.discoveryAvailability == BluetoothAvailability.PERMISSION_REQUIRED -> {
                Text(
                    "Scanning needs additional access. Already paired devices can still connect. On older Android versions, allow location access to discover devices.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onScan,
                    enabled = state.phase == BluetoothConnectionPhase.DISCONNECTED,
                    modifier = Modifier.fillMaxWidth().testTag("request_scan_permission"),
                ) { Text("Allow scan access") }
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.testTag("scan_app_settings"),
                ) { Text("Open app settings") }
            }
            state.discoveryAvailability == BluetoothAvailability.LOCATION_DISABLED -> {
                Text(
                    "This Android version needs location services enabled for discovery. Paired devices can still connect.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onOpenLocationSettings,
                    modifier = Modifier.fillMaxWidth().testTag("open_location_settings"),
                ) { Text("Open location settings") }
            }
            else -> Text(
                "Discovery is unavailable. Check Bluetooth settings and refresh.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.discoveryMessage?.let {
            Text(
                it,
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("discovery_message"),
            )
        }
    }
}


@Composable
private fun ConnectionStatus(state: BluetoothConnectionUiState) {
    val connected = state.availability == BluetoothAvailability.READY &&
        state.phase == BluetoothConnectionPhase.CONNECTED
    val status = when (state.availability) {
        BluetoothAvailability.PERMISSION_REQUIRED -> "Bluetooth permission needed"
        BluetoothAvailability.OFF -> "Bluetooth is off"
        BluetoothAvailability.LOCATION_DISABLED -> "Location services are off"
        BluetoothAvailability.UNSUPPORTED -> "Bluetooth unavailable"
        BluetoothAvailability.READY -> when (state.phase) {
            BluetoothConnectionPhase.DISCONNECTED -> "Bluetooth disconnected"
            BluetoothConnectionPhase.CONNECTING -> "Connecting to adapter"
            BluetoothConnectionPhase.CONNECTED -> "Bluetooth connected"
        }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardColor,
        border = BorderStroke(1.dp, if (connected) MintColor.copy(alpha = 0.4f) else BorderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "ADAPTER LINK",
                color = MutedColor,
                fontSize = 10.sp,
                letterSpacing = 1.3.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.phase == BluetoothConnectionPhase.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    status,
                    color = if (connected) MintColor else WarmWhite,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("bluetooth_connection_status"),
                )
            }
            if (state.phase == BluetoothConnectionPhase.CONNECTING && state.selectedDevice?.isPaired == false) {
                Text(
                    "Complete the Android pairing prompt if it appears. Keep the adapter powered on.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                when (state.obd.phase) {
                    ObdPhase.IDLE -> "ECU not checked"
                    ObdPhase.INITIALIZING, ObdPhase.DISCOVERING -> "ECU check in progress"
                    ObdPhase.LIVE -> "ECU readings active"
                    ObdPhase.ERROR -> "ECU reading error"
                    ObdPhase.STOPPED -> "ECU readings stopped"
                },
                color = MutedColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("ecu_status"),
            )
        }
    }
}

@Composable
private fun PairedDeviceRow(
    device: AdapterDevice,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) AccentColor.copy(alpha = 0.10f) else CardColor,
        border = BorderStroke(1.dp, if (selected) AccentColor else BorderColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelected)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    device.name.ifBlank { "Unnamed device" },
                    color = WarmWhite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    device.address.uppercase(Locale.ROOT),
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (device.isPaired) "Paired" else "Nearby · pairing may be requested",
                    color = if (device.isPaired) MintColor else MutedColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ConnectionAction(
    state: BluetoothConnectionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    when (state.phase) {
        BluetoothConnectionPhase.DISCONNECTED -> Button(
            onClick = onConnect,
            enabled = state.selectedDevice != null && state.isForeground,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("connect_adapter"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Connect to adapter", fontWeight = FontWeight.Bold)
        }
        BluetoothConnectionPhase.CONNECTING, BluetoothConnectionPhase.CONNECTED -> OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("disconnect_adapter"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (state.phase == BluetoothConnectionPhase.CONNECTING) "Cancel connection" else "Disconnect")
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("open_bluetooth_settings"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("Open Bluetooth settings")
    }
}
