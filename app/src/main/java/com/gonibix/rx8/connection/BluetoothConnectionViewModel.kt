package com.gonibix.rx8.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BluetoothConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED }

data class BluetoothConnectionUiState(
    val availability: BluetoothAvailability = BluetoothAvailability.UNSUPPORTED,
    val devices: List<AdapterDevice> = emptyList(),
    val selectedDevice: AdapterDevice? = null,
    val phase: BluetoothConnectionPhase = BluetoothConnectionPhase.DISCONNECTED,
    val message: String? = null,
    val isForeground: Boolean = false,
)

/** Owns one manually initiated adapter link. It never sends an OBD request. */
class BluetoothConnectionViewModel(
    private val gateway: BluetoothGateway,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BluetoothConnectionUiState())
    val uiState: StateFlow<BluetoothConnectionUiState> = mutableState.asStateFlow()

    private var attempt: Job? = null
    private var connection: AdapterConnection? = null
    private var sessionAddress: String? = null
    private var generation = 0L

    init {
        refresh()
    }

    fun refresh() {
        val availability = try {
            gateway.availability()
        } catch (_: SecurityException) {
            BluetoothAvailability.PERMISSION_REQUIRED
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                message = "Bluetooth status could not be checked. Try refreshing.",
            )
            return
        }
        if (availability != BluetoothAvailability.READY) {
            endSession()
            mutableState.value = mutableState.value.copy(
                availability = availability,
                devices = emptyList(),
                selectedDevice = null,
                phase = BluetoothConnectionPhase.DISCONNECTED,
                message = null,
            )
            return
        }
        val devices = try {
            gateway.pairedDevices()
                .distinctBy { it.address.uppercase(Locale.ROOT) }
                .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.address }))
        } catch (_: SecurityException) {
            permissionRevoked()
            return
        } catch (_: Exception) {
            endSession()
            mutableState.value = mutableState.value.copy(
                availability = availability,
                devices = emptyList(),
                selectedDevice = null,
                phase = BluetoothConnectionPhase.DISCONNECTED,
                message = "Paired devices could not be read. Check Bluetooth settings and refresh.",
            )
            return
        }
        val old = mutableState.value
        val selected = devices.firstOrNull { it.address.sameAddress(old.selectedDevice?.address) }
        val selectionRemoved = old.selectedDevice != null && selected == null
        if (selectionRemoved) endSession()
        mutableState.value = old.copy(
            availability = availability,
            devices = devices,
            selectedDevice = selected,
            phase = if (selectionRemoved) BluetoothConnectionPhase.DISCONNECTED else old.phase,
            message = if (selectionRemoved) "The selected device is no longer paired." else null,
        )
    }

    fun setForeground(isForeground: Boolean) {
        if (mutableState.value.isForeground == isForeground) return
        val hadSession = mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED
        mutableState.value = mutableState.value.copy(isForeground = isForeground)
        if (!isForeground) {
            endSession()
            mutableState.value = mutableState.value.copy(
                phase = BluetoothConnectionPhase.DISCONNECTED,
                message = if (hadSession) {
                    "Disconnected after leaving this screen. Reconnect manually when ready."
                } else {
                    mutableState.value.message
                },
            )
        } else {
            refresh()
        }
    }

    fun selectDevice(device: AdapterDevice) {
        if (mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED) return
        val paired = mutableState.value.devices.firstOrNull {
            it.address.sameAddress(device.address)
        } ?: return
        mutableState.value = mutableState.value.copy(selectedDevice = paired, message = null)
    }

    fun connect() {
        if (!mutableState.value.isForeground ||
            mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED
        ) return
        refresh()
        val state = mutableState.value
        val device = state.selectedDevice ?: return
        if (state.availability != BluetoothAvailability.READY) return
        val sessionGeneration = ++generation
        sessionAddress = device.address
        mutableState.value = state.copy(phase = BluetoothConnectionPhase.CONNECTING, message = null)
        attempt = viewModelScope.launch(dispatcher) {
            try {
                val opened = gateway.connect(device)
                if (!isActive || sessionGeneration != generation || !mutableState.value.isForeground) {
                    opened.closeSafely()
                    return@launch
                }
                connection = opened
                mutableState.value = mutableState.value.copy(
                    phase = BluetoothConnectionPhase.CONNECTED,
                    message = null,
                )
            } catch (_: TimeoutCancellationException) {
                failCurrentSession(sessionGeneration, "Connection timed out. Check the adapter and reconnect manually.")
            } catch (cancelled: CancellationException) {
                if (sessionGeneration == generation) {
                    sessionAddress = null
                    mutableState.value = mutableState.value.copy(
                        phase = BluetoothConnectionPhase.DISCONNECTED,
                    )
                }
                throw cancelled
            } catch (_: SecurityException) {
                if (sessionGeneration == generation) permissionRevoked()
            } catch (_: Exception) {
                failCurrentSession(sessionGeneration, "Connection failed. Check the adapter and reconnect manually.")
            } finally {
                if (sessionGeneration == generation) attempt = null
            }
        }
    }

    fun disconnect() {
        endSession()
        mutableState.value = mutableState.value.copy(
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = null,
        )
    }

    fun onDeviceDisconnected(address: String) {
        // A delayed ACL broadcast from the previous link must not cancel its replacement.
        // Pending attempts resolve through their own bounded connect result or timeout.
        if (mutableState.value.phase != BluetoothConnectionPhase.CONNECTED ||
            !address.sameAddress(sessionAddress)
        ) return
        endSession()
        mutableState.value = mutableState.value.copy(
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = "The Bluetooth connection was lost. Reconnect manually when ready.",
        )
    }

    private fun failCurrentSession(expectedGeneration: Long, message: String) {
        if (expectedGeneration != generation) return
        sessionAddress = null
        mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
        refresh()
        if (mutableState.value.availability == BluetoothAvailability.READY) {
            mutableState.value = mutableState.value.copy(message = message)
        }
    }

    private fun permissionRevoked() {
        endSession()
        mutableState.value = mutableState.value.copy(
            availability = BluetoothAvailability.PERMISSION_REQUIRED,
            devices = emptyList(),
            selectedDevice = null,
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = null,
        )
    }

    private fun endSession() {
        generation++
        attempt?.cancel()
        attempt = null
        connection?.closeSafely()
        connection = null
        sessionAddress = null
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }
}

private fun String.sameAddress(other: String?): Boolean =
    other != null && equals(other, ignoreCase = true)

private fun AdapterConnection.closeSafely() {
    runCatching { close() }
}
