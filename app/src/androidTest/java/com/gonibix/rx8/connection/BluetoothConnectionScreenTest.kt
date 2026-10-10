package com.gonibix.rx8.connection

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                    state = BluetoothConnectionUiState(availability = BluetoothAvailability.PERMISSION_REQUIRED),
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
        compose.onNodeWithTag("request_bluetooth_permission").performClick()
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
        val state = mutableStateOf(BluetoothConnectionUiState(availability = BluetoothAvailability.OFF))
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
                    state = BluetoothConnectionUiState(
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
        compose.onNodeWithTag("no_paired_devices").assertIsDisplayed()
        compose.onNodeWithTag("connect_adapter").assertIsNotEnabled()
        compose.onNodeWithTag("open_bluetooth_settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectedDeviceEnablesConnectAndAdapterSuccessDoesNotClaimEcuSuccess() {
        val device = AdapterDevice("02:00:00:00:00:01", "Test OBD adapter")
        val state = mutableStateOf(
            BluetoothConnectionUiState(
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
        compose.onNodeWithText("Test OBD adapter").performClick()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Connecting to adapter")
        compose.onNodeWithTag("disconnect_adapter").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, disconnects)
            state.value = state.value.copy(phase = BluetoothConnectionPhase.CONNECTED)
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth connected")
        compose.onNodeWithTag("ecu_status").assertTextEquals("ECU not checked")
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
        compose.onNodeWithText("SIMULATOR").assertDoesNotExist()
        compose.onNodeWithTag("disconnect_adapter").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, disconnects) }
    }
    @Test
    fun scanControlsShowProgressAndKeepNoResultsGuidance() {
        val state = mutableStateOf(
            BluetoothConnectionUiState(
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
                    BluetoothConnectionScreen(
                        state = BluetoothConnectionUiState(
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
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                }
            }
        }
        compose.onNodeWithText("OBDII").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("02:00:00:AB:CD:EF").assertIsDisplayed()
        compose.onNodeWithText("Nearby · pairing may be requested").assertIsDisplayed()
        captureDiscoveryScreen()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled()
    }


    @Test
    fun scanPermissionAndLocationRecoveryLeavePairedConnectEnabled() {
        val device = AdapterDevice("02:00:00:00:00:01", "Test adapter")
        val state = mutableStateOf(
            BluetoothConnectionUiState(
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


    private fun captureDiscoveryScreen() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("The emulator must capture the discovered adapter screen", screenshot)
        val captured = requireNotNull(screenshot)
        try {
            val source = File(directory, "discovery.png")
            source.outputStream().use { output ->
                assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/discovery.png " +
                        "/data/local/tmp/rx8-discovery.png",
                ),
            ).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-discovery.png"),
            ).use { it.readBytes() }
            assertArrayEquals("The discovery screenshot must survive test teardown", source.readBytes(), copied)
        } finally {
            captured.recycle()
        }
    }


}
