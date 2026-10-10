package com.gonibix.rx8.connection

import androidx.compose.foundation.layout.fillMaxSize
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
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertEquals
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("no_paired_devices").assertIsDisplayed()
        compose.onNodeWithTag("connect_adapter").assertIsNotEnabled()
        compose.onNodeWithTag("open_bluetooth_settings").assertIsDisplayed()
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("connect_adapter").assertIsNotEnabled()
        compose.onNodeWithText("Test OBD adapter").performClick()
        compose.onNodeWithTag("connect_adapter").assertIsEnabled().performClick()
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Connecting to adapter")
        compose.onNodeWithTag("disconnect_adapter").performClick()
        compose.runOnIdle {
            assertEquals(1, disconnects)
            state.value = state.value.copy(phase = BluetoothConnectionPhase.CONNECTED)
        }
        compose.onNodeWithTag("bluetooth_connection_status").assertTextEquals("Bluetooth connected")
        compose.onNodeWithTag("ecu_status").assertTextEquals("ECU not checked")
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
        compose.onNodeWithText("SIMULATOR").assertDoesNotExist()
        compose.onNodeWithTag("disconnect_adapter").performClick()
        compose.runOnIdle { assertEquals(2, disconnects) }
    }
}
