package com.gonibix.rx8.connection

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothConnectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permissionScreenProvidesPromptAndAppSettingsRecovery() {
        var permissionRequests = 0
        var settingsRequests = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = BluetoothConnectionUiState(favoritePreferenceLoading = false, availability = BluetoothAvailability.PERMISSION_REQUIRED),
                    onRefresh = {},
                    onRequestPermission = { permissionRequests++ },
                    onOpenBluetoothSettings = {},
                    onOpenAppSettings = { settingsRequests++ },
                    onSelectDevice = {},
                    onConnect = {},
                    onDisconnect = {},
                    onScan = {},
                    onStopScan = {},
                    onOpenLocationSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth permission needed")
        compose.onNodeWithTag("request_bluetooth_permission").performScrollTo().performClick()
        compose.onNodeWithTag("open_app_settings").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(1, settingsRequests)
        }
        compose.onNodeWithTag("connect_adapter").assertDoesNotExist()
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
    }

    @Test
    fun offAndUnsupportedStatesAreExplicit() {
        val state = mutableStateOf(BluetoothConnectionUiState(favoritePreferenceLoading = false, availability = BluetoothAvailability.OFF))
        var settingsRequests = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = state.value,
                    onRefresh = {},
                    onRequestPermission = {},
                    onOpenBluetoothSettings = { settingsRequests++ },
                    onOpenAppSettings = {},
                    onSelectDevice = {},
                    onConnect = {},
                    onDisconnect = {},
                    onScan = {},
                    onStopScan = {},
                    onOpenLocationSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth is off")
        compose.onNodeWithTag("open_bluetooth_settings").performClick()
        compose.runOnIdle {
            assertEquals(1, settingsRequests)
            state.value = state.value.copy(availability = BluetoothAvailability.UNSUPPORTED)
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth unavailable")
        compose.onNodeWithTag("connect_adapter").assertDoesNotExist()
    }

    @Test
    fun emptyPairingListOffersSystemSettingsAndDisablesConnect() {
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = BluetoothConnectionUiState(favoritePreferenceLoading = false,
                        availability = BluetoothAvailability.READY,
                        isForeground = true,
                    ),
                    onRefresh = {},
                    onRequestPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenAppSettings = {},
                    onSelectDevice = {},
                    onConnect = {},
                    onDisconnect = {},
                    onScan = {},
                    onStopScan = {},
                    onOpenLocationSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("no_paired_devices").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connect_adapter").assertIsNotEnabled()
        compose.onNodeWithTag("open_bluetooth_settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectedDeviceEnablesConnectAndAdapterSuccessDoesNotClaimEcuSuccess() {
        val device = AdapterDevice("02:00:00:00:00:01", "Test OBD adapter")
        val state = mutableStateOf(
            BluetoothConnectionUiState(favoritePreferenceLoading = false,
                availability = BluetoothAvailability.READY,
                devices = listOf(device),
                isForeground = true,
            ),
        )
        var disconnects = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = state.value,
                    onRefresh = {},
                    onRequestPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenAppSettings = {},
                    onSelectDevice = { state.value = state.value.copy(selectedDevice = it) },
                    onConnect = { state.value = state.value.copy(phase = BluetoothConnectionPhase.CONNECTING) },
                    onDisconnect = {
                        disconnects++
                        state.value = state.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
                    },
                    onScan = {},
                    onStopScan = {},
                    onOpenLocationSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("connect_adapter").assertIsNotEnabled()
        compose.onNodeWithText("Test OBD adapter").performScrollTo().performClick()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Connecting to adapter")
        compose.onNodeWithTag("disconnect_adapter").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, disconnects)
            state.value = state.value.copy(phase = BluetoothConnectionPhase.CONNECTED)
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth connected")
        compose.onNodeWithTag("ecu_status").assertDoesNotExist()
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
        compose.onNodeWithText("SIMULATOR").assertDoesNotExist()
        compose.onNodeWithTag("disconnect_adapter").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, disconnects) }
    }
    @Test
    fun scanControlsShowProgressAndKeepNoResultsGuidance() {
        val state = mutableStateOf(
            BluetoothConnectionUiState(favoritePreferenceLoading = false,
                availability = BluetoothAvailability.READY,
                isForeground = true,
            ),
        )
        var stops = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = state.value,
                    onRefresh = {},
                    onRequestPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenAppSettings = {},
                    onSelectDevice = {},
                    onConnect = {},
                    onDisconnect = {},
                    onScan = { state.value = state.value.copy(isScanning = true, hasScanned = true) },
                    onStopScan = {
                        stops++
                        state.value = state.value.copy(isScanning = false, discoveryMessage = "Scan stopped.")
                    },
                    onOpenLocationSettings = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("start_discovery").performScrollTo().performClick()
        compose.onNodeWithTag("discovery_status").assertTextEquals("Scanning nearby Bluetooth devices…")
        compose.onNodeWithTag("stop_discovery").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            state.value = state.value.copy(
                hasScanned = true,
                discoveryMessage = "No nearby devices found in this scan. Power the adapter and try again.",
            )
        }
        compose.onNodeWithTag("discovery_message")
            .assertTextEquals("No nearby devices found in this scan. Power the adapter and try again.")
        compose.onNodeWithTag("start_discovery").assertIsEnabled()
    }

    @Test
    fun discoveredAdapterShowsFullAddressAndPairingState() {
        val device = AdapterDevice("02:00:00:AB:CD:EF", "OBDII", isPaired = false)
        compose.setContent {
            Rx8Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column(Modifier.safeDrawingPadding()) {
                        Text("UI TEST FIXTURE · SYNTHETIC DISCOVERY", style = MaterialTheme.typography.labelMedium)
                        BluetoothConnectionScreen(
                            state = BluetoothConnectionUiState(favoritePreferenceLoading = false,
                                availability = BluetoothAvailability.READY,
                                devices = listOf(device),
                                selectedDevice = device,
                                isForeground = true,
                                hasScanned = true,
                            ),
                            onRefresh = {},
                            onRequestPermission = {},
                            onOpenBluetoothSettings = {},
                            onOpenAppSettings = {},
                            onSelectDevice = {},
                            onConnect = {},
                            onDisconnect = {},
                            onScan = {},
                            onStopScan = {},
                            onOpenLocationSettings = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("OBDII").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("02:00:00:AB:CD:EF").assertIsDisplayed()
        compose.onNodeWithText("Nearby · pairing may be requested").assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC DISCOVERY").assertIsDisplayed()
        captureDiscoveryScreen()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled()
    }


    @Test
    fun scanPermissionAndLocationRecoveryLeavePairedConnectEnabled() {
        val device = AdapterDevice("02:00:00:00:00:01", "Test adapter")
        val state = mutableStateOf(
            BluetoothConnectionUiState(favoritePreferenceLoading = false,
                availability = BluetoothAvailability.READY,
                devices = listOf(device),
                selectedDevice = device,
                isForeground = true,
                discoveryAvailability = BluetoothAvailability.PERMISSION_REQUIRED,
            ),
        )
        var requests = 0
        var locationSettings = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = state.value,
                    onRefresh = {},
                    onRequestPermission = {},
                    onOpenBluetoothSettings = {},
                    onOpenAppSettings = {},
                    onSelectDevice = {},
                    onConnect = {},
                    onDisconnect = {},
                    onScan = { requests++ },
                    onStopScan = {},
                    onOpenLocationSettings = { locationSettings++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("request_scan_permission").performScrollTo().performClick()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, requests)
            state.value = state.value.copy(discoveryAvailability = BluetoothAvailability.LOCATION_DISABLED)
        }
        compose.onNodeWithTag("open_location_settings").performScrollTo().performClick()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, locationSettings) }
    }


    @Test fun adapterDoesNotExposeVehicleDiagnosticsAndFavoriteIsExplicit() {
        val adapter = AdapterDevice("02:00:00:00:00:42", "Synthetic favorite")
        val state = mutableStateOf(BluetoothConnectionUiState(
            favoritePreferenceLoading = false, availability = BluetoothAvailability.READY,
            selectedDevice = adapter, devices = listOf(adapter), isForeground = true,
            phase = BluetoothConnectionPhase.CONNECTED, isAwaitingVehicle = true,
            automaticMessage = "Choose a vehicle to record.", obd = ObdSessionSnapshot(phase = ObdPhase.LIVE),
        ))
        var favorites = 0
        var resumes = 0
        var pauses = 0
        var clears = 0
        compose.setContent {
            Rx8Theme {
                BluetoothConnectionScreen(
                    state = state.value, onRefresh = {}, onRequestPermission = {}, onOpenBluetoothSettings = {},
                    onOpenAppSettings = {}, onSelectDevice = {}, onConnect = {}, onDisconnect = {}, onScan = {},
                    onStopScan = {}, onOpenLocationSettings = {}, modifier = Modifier.fillMaxSize(),
                    onSetFavorite = { favorites++; state.value = state.value.copy(favoriteAdapter = adapter) },
                    onPauseAutomatic = { pauses++; state.value = state.value.copy(favoriteAutoConnectPaused = true) },
                    onResumeAutomatic = { resumes++; state.value = state.value.copy(favoriteAutoConnectPaused = false) },
                    onClearFavorite = { clears++; state.value = state.value.copy(favoriteAdapter = null) },
                )
            }
        }
        compose.onNodeWithTag("ecu_status").assertDoesNotExist()
        compose.onNodeWithTag("automatic_choose_vehicle").assertDoesNotExist()
        compose.onNodeWithTag("open_capabilities").assertDoesNotExist()
        compose.onNodeWithTag("set_favorite_adapter").performScrollTo().performClick()
        compose.onNodeWithTag("toggle_favorite_automatic").performScrollTo().performClick()
        compose.onNodeWithText("Resume automatic connections").assertIsDisplayed().performClick()
        compose.onNodeWithTag("clear_favorite_adapter").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(favorites, pauses, resumes, clears)) }
    }

    @Test fun liveDiagnosticsOfferVehicleSelectionAndEcuStateWithoutSensorCards() {
        val state = mutableStateOf(BluetoothConnectionUiState(favoritePreferenceLoading = false,
            phase = BluetoothConnectionPhase.CONNECTED, isAwaitingVehicle = true,
            automaticMessage = "Choose a vehicle to begin recording.",
            obd = ObdSessionSnapshot(phase = ObdPhase.DISCOVERING),
        ))
        var vehicles = 0
        compose.setContent { Rx8Theme {
            VehicleDiagnosticsPanel(state.value, { vehicles++ }, {}, {}, {}, Modifier.fillMaxSize())
        } }
        compose.onNodeWithTag("ecu_status").assertTextEquals("ECU check in progress")
        compose.onNodeWithTag("automatic_choose_vehicle").performClick()
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, vehicles) }
    }

    @Test fun adapterDiagnosticsRetainTheSpecificReadingFailureAndReportedMetadata() {
        val message = "The adapter did not respond. Reconnect to retry."
        compose.setContent { Rx8Theme {
            BluetoothConnectionScreen(
                state = BluetoothConnectionUiState(favoritePreferenceLoading = false,
                    availability = BluetoothAvailability.READY,
                    obd = ObdSessionSnapshot(phase = ObdPhase.ERROR, statusMessage = message,
                        adapterIdentity = "Synthetic ELM fixture", protocolLabel = "Synthetic protocol fixture")),
                onRefresh = {}, onRequestPermission = {}, onOpenBluetoothSettings = {}, onOpenAppSettings = {},
                onSelectDevice = {}, onConnect = {}, onDisconnect = {}, onScan = {}, onStopScan = {}, onOpenLocationSettings = {},
            )
        } }
        compose.onNodeWithTag("adapter_session_diagnostic").assertTextEquals(message)
        compose.onNodeWithTag("adapter_identification").assertTextEquals("Adapter identification: Synthetic ELM fixture")
        compose.onNodeWithTag("adapter_protocol").assertTextEquals("Protocol reported by adapter: Synthetic protocol fixture")
        compose.onNodeWithTag("dashboard_value_7E8_speed").assertDoesNotExist()
    }

    @Test fun syntheticConnectionScreenshotHasNoManualReadAction() {
        compose.setContent {
            Rx8Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.safeDrawingPadding()) {
                        Text("UI TEST FIXTURE · SYNTHETIC CONNECTION", style = MaterialTheme.typography.labelMedium)
                        BluetoothConnectionScreen(
                            state = BluetoothConnectionUiState(favoritePreferenceLoading = false,
                                availability = BluetoothAvailability.READY, phase = BluetoothConnectionPhase.CONNECTED,
                                isForeground = true, isAwaitingVehicle = true,
                                automaticMessage = "Choose a saved vehicle to begin the automatic journey.",
                                obd = ObdSessionSnapshot(phase = ObdPhase.INITIALIZING, statusMessage = "Preparing synthetic fixture."),
                            ),
                            onRefresh = {}, onRequestPermission = {}, onOpenBluetoothSettings = {}, onOpenAppSettings = {},
                            onSelectDevice = {}, onConnect = {}, onDisconnect = {}, onScan = {}, onStopScan = {}, onOpenLocationSettings = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth connected")
        compose.onNodeWithTag("ecu_status").assertDoesNotExist()
        compose.onNodeWithTag("start_obd").assertDoesNotExist()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC CONNECTION").assertIsDisplayed()
        captureDiscoveryScreen("connection")
    }

    private fun captureDiscoveryScreen(name: String = "discovery") {
        require(name == "discovery" || name == "connection")
        compose.waitForIdle()
        // Capture Compose content, not the emulator window before its frame is presented.
        var rendered: Bitmap? = null
        compose.waitUntil(5_000L) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (hasDiscoveryContent(bitmap)) {
                rendered = bitmap
                true
            } else {
                bitmap.recycle()
                false
            }
        }
        val captured = requireNotNull(rendered)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        try {
            assertTrue(directory.isDirectory || directory.mkdirs())
            val source = File(directory, "$name.png")
            source.outputStream().use { output ->
                assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/$name.png " +
                        "/data/local/tmp/rx8-$name.png",
                ),
            ).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-$name.png"),
            ).use { it.readBytes() }
            assertArrayEquals("The discovery screenshot must survive test teardown", source.readBytes(), copied)
        } finally {
            captured.recycle()
        }
    }

    private fun hasDiscoveryContent(bitmap: Bitmap): Boolean {
        var dark = 0
        var light = 0
        var samples = 0
        for (y in 0 until bitmap.height step (bitmap.height / 96).coerceAtLeast(1)) {
            for (x in 0 until bitmap.width step (bitmap.width / 96).coerceAtLeast(1)) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
                samples++
                if ((pixel ushr 24 and 255) > 127 && brightness < 96) dark++
                if ((pixel ushr 24 and 255) > 127 && brightness > 160) light++
            }
        }
        return samples > 0 && dark > samples / 10 && light >= 8
    }


}
