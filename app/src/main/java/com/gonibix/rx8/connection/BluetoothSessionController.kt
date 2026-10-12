package com.gonibix.rx8.connection

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.Elm327Session
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSession
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.obd.ObdVinSnapshot
import com.gonibix.rx8.obd.ObdVinStatus
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val discoveryAvailability: BluetoothAvailability = availability,
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val discoveryMessage: String? = null,
    val obd: ObdSessionSnapshot = ObdSessionSnapshot(),
    val automaticConnectionReady: Boolean = false,
    val isAwaitingVehicle: Boolean = false,
    val automaticMessage: String? = null,
    val favoriteAdapter: AdapterDevice? = null,
    val favoriteAutoConnectPaused: Boolean = false,
    val favoritePreferenceLoading: Boolean = true,
    val favoritePreferenceSaving: Boolean = false,
    val favoriteMessage: String? = null,
)

/** Service-owned link and acquisition state. Call actions on the owner dispatcher (Main in the app). */
class BluetoothSessionController(
    private val gateway: BluetoothGateway,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val obdSessionFactory: ObdSessionFactory = ObdSessionFactory { Elm327Session(it) },
    private val onObdSnapshot: (ObdSessionSnapshot) -> Unit = {},
    private val onSessionEnded: (completed: Boolean) -> Unit = {},
    private val autoStartObd: Boolean = false,
    private val onConnected: suspend (AdapterDevice) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(BluetoothConnectionUiState())
    val uiState: StateFlow<BluetoothConnectionUiState> = mutableState.asStateFlow()

    private var attempt: Job? = null
    private var connection: AdapterConnection? = null
    private var sessionAddress: String? = null
    private var generation = 0L
    private var scanJob: Job? = null
    private var scanGeneration = 0L
    private var obdJob: Job? = null
    private var obdSession: ObdSession? = null
    private var paired = emptyList<AdapterDevice>()
    private val discovered = linkedMapOf<String, AdapterDevice>()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
        refresh()
    }

    fun refresh() {
        if (closed.get()) return
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
        val discoveryAvailability = try {
            gateway.discoveryAvailability()
        } catch (_: SecurityException) {
            BluetoothAvailability.PERMISSION_REQUIRED
        } catch (_: Exception) {
            BluetoothAvailability.UNSUPPORTED
        }
        if (availability != BluetoothAvailability.READY) {
            endSession()
            cancelScan()
            paired = emptyList()
            discovered.clear()
            mutableState.value = mutableState.value.copy(
                availability = availability,
                discoveryAvailability = discoveryAvailability,
                devices = emptyList(),
                selectedDevice = null,
                phase = BluetoothConnectionPhase.DISCONNECTED,
                message = null,
            )
            return
        }
        if (discoveryAvailability != BluetoothAvailability.READY) cancelScan()
        paired = try {
            gateway.pairedDevices()
                .distinctBy { it.key() }
                .map { it.copy(isPaired = true) }
        } catch (_: SecurityException) {
            permissionRevoked()
            return
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                availability = availability,
                discoveryAvailability = discoveryAvailability,
                message = "Paired devices could not be read. Check Bluetooth settings and refresh.",
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            availability = availability,
            discoveryAvailability = discoveryAvailability,
        )
        publishDevices()
    }

    /** Screen visibility gates user actions and discovery, never an already started session. */
    fun setForeground(isForeground: Boolean) {
        if (closed.get() || mutableState.value.isForeground == isForeground) return
        mutableState.value = mutableState.value.copy(isForeground = isForeground)
        if (!isForeground) cancelScan() else refresh()
    }

    fun startDiscovery() {
        if (closed.get()) return
        if (!mutableState.value.isForeground ||
            mutableState.value.isScanning ||
            mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED
        ) return
        refresh()
        if (mutableState.value.availability != BluetoothAvailability.READY ||
            mutableState.value.discoveryAvailability != BluetoothAvailability.READY
        ) return
        val previousScan = scanJob
        val expectedGeneration = ++scanGeneration
        mutableState.value = mutableState.value.copy(
            isScanning = true,
            hasScanned = true,
            discoveryMessage = null,
        )
        scanJob = scope.launch(dispatcher) {
            val found = mutableSetOf<String>()
            try {
                // Wait for the previous scanner to release its receiver and discovery session.
                previousScan?.cancelAndJoin()
                if (!isActive || expectedGeneration != scanGeneration) return@launch
                gateway.discover().collect { device ->
                    if (expectedGeneration == scanGeneration &&
                        mutableState.value.isForeground &&
                        mutableState.value.phase == BluetoothConnectionPhase.DISCONNECTED
                    ) {
                        found += device.key()
                        val previous = discovered[device.key()]
                        discovered[device.key()] = if (device.name.isBlank() && previous != null) {
                            device.copy(name = previous.name, isPaired = false)
                        } else {
                            device.copy(isPaired = false)
                        }
                        publishDevices()
                    }
                }
                if (expectedGeneration == scanGeneration) {
                    mutableState.value = mutableState.value.copy(
                        discoveryMessage = if (found.isEmpty()) {
                            "No nearby devices found in this scan. Disconnect Car Scanner, power the adapter, keep it within range, and try again."
                        } else {
                            "Scan complete. Found devices remain available for selection."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                if (expectedGeneration == scanGeneration) {
                    mutableState.value = mutableState.value.copy(
                        discoveryAvailability = BluetoothAvailability.PERMISSION_REQUIRED,
                        discoveryMessage = "Scan access was denied. Allow access or open app settings to scan again.",
                    )
                }
            } catch (_: Exception) {
                if (expectedGeneration == scanGeneration) {
                    mutableState.value = mutableState.value.copy(
                        discoveryMessage = "Scan failed. Any devices already found are kept. Check Bluetooth and try again.",
                    )
                }
            } finally {
                if (expectedGeneration == scanGeneration) {
                    mutableState.value = mutableState.value.copy(isScanning = false)
                    scanJob = null
                }
            }
        }
    }

    fun stopDiscovery() {
        if (closed.get()) return
        cancelScan()
    }

    fun selectDevice(device: AdapterDevice) {
        if (closed.get()) return
        if (mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED) return
        val listed = mutableState.value.devices.firstOrNull { it.key() == device.key() } ?: return
        val old = mutableState.value
        mutableState.value = old.copy(
            selectedDevice = listed,
            message = null,
            obd = if (listed.address.sameAddress(old.selectedDevice?.address)) old.obd else ObdSessionSnapshot(),
        )
    }

    /** Only the private last-success preference can enter here; UI selection remains list-based. */
    internal fun selectRememberedDevice(address: String, fallbackName: String = "Last connected adapter"): Boolean {
        if (closed.get() || mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED) return false
        val normalized = normalizedAdapterAddress(address) ?: return false
        refresh()
        if (mutableState.value.availability != BluetoothAvailability.READY) return false
        val listed = mutableState.value.devices.firstOrNull { it.address.sameAddress(normalized) }
        if (listed == null) {
            discovered[normalized] = AdapterDevice(normalized, fallbackName, isPaired = false)
            publishDevices()
        }
        selectDevice(listed ?: AdapterDevice(normalized, fallbackName, isPaired = false))
        return mutableState.value.selectedDevice?.address?.equals(normalized, ignoreCase = true) == true
    }

    fun connect() {
        if (!mutableState.value.isForeground) return
        connectFromVisibleApp()
    }

    /** The service verifies Activity visibility before authorizing this independent of the selected tab. */
    internal fun connectFromVisibleApp() {
        if (closed.get()) return
        if (mutableState.value.phase != BluetoothConnectionPhase.DISCONNECTED) return
        refresh()
        val device = mutableState.value.selectedDevice ?: return
        if (mutableState.value.availability != BluetoothAvailability.READY) return
        val previousScan = scanJob
        cancelScan()
        val sessionGeneration = ++generation
        sessionAddress = device.address
        mutableState.value = mutableState.value.copy(
            phase = BluetoothConnectionPhase.CONNECTING,
            message = null,
        )
        attempt = scope.launch(dispatcher) {
            try {
                previousScan?.cancelAndJoin()
                if (!isActive || sessionGeneration != generation) {
                    return@launch
                }
                val opened = gateway.connect(device)
                if (!isActive || sessionGeneration != generation) {
                    opened.closeSafely()
                    return@launch
                }
                connection = opened
                mutableState.value = mutableState.value.copy(
                    phase = BluetoothConnectionPhase.CONNECTED,
                    message = null,
                    obd = if (autoStartObd) ObdSessionSnapshot(
                        phase = ObdPhase.INITIALIZING, statusMessage = "Preparing automatic vehicle readings…",
                    ) else ObdSessionSnapshot(),
                )
                // Profile lookup/recording preparation precedes the first ECU request. A tab change
                // or screen lock must not revoke this continuation of an already authorized link.
                if (!isActive || sessionGeneration != generation || connection !== opened) return@launch
                onConnected(device)
                if (autoStartObd && isActive && sessionGeneration == generation && connection === opened) {
                    startOwnedObd()
                }
            } catch (_: TimeoutCancellationException) {
                failCurrentSession(sessionGeneration, "Connection timed out. Disconnect Car Scanner, check the adapter, and reconnect manually.")
            } catch (cancelled: CancellationException) {
                if (sessionGeneration == generation) {
                    endSession()
                    mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
                }
                throw cancelled
            } catch (_: SecurityException) {
                if (sessionGeneration == generation) permissionRevoked()
            } catch (_: Exception) {
                failCurrentSession(sessionGeneration, "Connection failed. Disconnect Car Scanner, check the adapter, and reconnect manually.")
            } finally {
                if (sessionGeneration == generation) attempt = null
            }
        }
    }

    fun disconnect() {
        if (closed.get()) return
        endSession(completed = true)
        mutableState.value = mutableState.value.copy(
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = null,
        )
    }

    fun startObd() {
        if (!mutableState.value.isForeground) return
        startOwnedObd()
    }

    private fun startOwnedObd() {
        if (closed.get()) return
        val current = mutableState.value
        val ownedConnection = connection ?: return
        if (current.phase != BluetoothConnectionPhase.CONNECTED || obdJob?.isActive == true) return
        val sessionGeneration = generation
        mutableState.value = current.copy(
            obd = ObdSessionSnapshot(phase = ObdPhase.INITIALIZING, statusMessage = "Preparing the adapter…"),
        )
        obdJob = scope.launch(dispatcher) {
            try {
                val session = obdSessionFactory.create(ownedConnection)
                obdSession = session
                session.run { snapshot ->
                    if (sessionGeneration == generation && connection === ownedConnection) {
                        onObdSnapshot(snapshot)
                        mutableState.value = mutableState.value.copy(obd = snapshot)
                    }
                }
                if (sessionGeneration == generation) {
                    endSession()
                    mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
                }
            } catch (cancelled: CancellationException) {
                if (sessionGeneration == generation) {
                    endSession()
                    mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
                }
                throw cancelled
            } catch (_: SecurityException) {
                if (sessionGeneration == generation) permissionRevoked()
            } catch (_: Exception) {
                if (sessionGeneration == generation) {
                    val last = mutableState.value.obd
                    endSession()
                    mutableState.value = mutableState.value.copy(
                        phase = BluetoothConnectionPhase.DISCONNECTED,
                        obd = last.copy(
                            phase = ObdPhase.ERROR,
                            vin = ObdVinSnapshot(),
                            statusMessage = if (last.phase == ObdPhase.ERROR) last.statusMessage else
                                "Readings stopped. Check the adapter and ignition, then reconnect.",
                            readings = last.readings.map {
                                if (it.status == ObdReadingStatus.VALID) it.copy(status = ObdReadingStatus.STALE) else it
                            },
                        ),
                    )
                }
            } finally {
                if (sessionGeneration == generation) obdJob = null
            }
        }
    }

    fun readVin() {
        if (!mutableState.value.isForeground) return
        readVinFromVisibleApp()
    }

    /** Activity visibility is verified by the service; discovery remains Adapter-tab scoped. */
    internal fun readVinFromVisibleApp() {
        if (closed.get()) return
        val current = mutableState.value
        if (current.phase != BluetoothConnectionPhase.CONNECTED ||
            current.obd.phase != ObdPhase.LIVE || current.obd.vin.status == ObdVinStatus.READING
        ) return
        if (obdSession?.requestVin() == true) {
            mutableState.value = current.copy(obd = current.obd.copy(
                vin = ObdVinSnapshot(ObdVinStatus.READING, "VIN request queued after the current sensor reading."),
            ))
        }
    }

    fun onDeviceDisconnected(address: String) {
        if (closed.get()) return
        // Old ACL broadcasts must not cancel a pending replacement link to the same adapter.
        if (mutableState.value.phase != BluetoothConnectionPhase.CONNECTED ||
            !address.sameAddress(sessionAddress)
        ) return
        endSession()
        mutableState.value = mutableState.value.copy(
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = "The Bluetooth connection was lost. Reconnect manually when ready.",
        )
    }

    private fun publishDevices() {
        val devices = linkedMapOf<String, AdapterDevice>()
        discovered.forEach { (key, device) -> devices[key] = device.copy(isPaired = false) }
        paired.forEach { device ->
            val name = device.name.ifBlank { devices[device.key()]?.name.orEmpty() }
            devices[device.key()] = device.copy(name = name, isPaired = true)
        }
        val sorted = devices.values.sortedWith(
            compareByDescending<AdapterDevice> { it.isPaired }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.key() },
        )
        val old = mutableState.value
        val selected = sorted.firstOrNull { it.address.sameAddress(old.selectedDevice?.address) }
        val removed = old.selectedDevice != null && selected == null
        if (removed) endSession()
        mutableState.value = mutableState.value.copy(
            devices = sorted,
            selectedDevice = selected,
            phase = if (removed) BluetoothConnectionPhase.DISCONNECTED else old.phase,
            message = if (removed) "The selected device is no longer available. Scan or refresh again." else old.message,
        )
    }

    private fun cancelScan() {
        scanGeneration++
        scanJob?.cancel()
        val old = mutableState.value
        mutableState.value = old.copy(
            isScanning = false,
            discoveryMessage = if (old.isScanning) {
                "Scan stopped. Any devices already found remain available."
            } else {
                old.discoveryMessage
            },
        )
        // Keep the cancelled job until its cleanup finishes so connect/new scans can join it.
    }

    private fun failCurrentSession(expectedGeneration: Long, message: String) {
        if (expectedGeneration != generation) return
        endSession()
        mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED)
        refresh()
        if (mutableState.value.availability == BluetoothAvailability.READY) {
            mutableState.value = mutableState.value.copy(message = message)
        }
    }

    private fun permissionRevoked() {
        endSession()
        cancelScan()
        paired = emptyList()
        discovered.clear()
        mutableState.value = mutableState.value.copy(
            availability = BluetoothAvailability.PERMISSION_REQUIRED,
            discoveryAvailability = BluetoothAvailability.PERMISSION_REQUIRED,
            devices = emptyList(),
            selectedDevice = null,
            phase = BluetoothConnectionPhase.DISCONNECTED,
            message = null,
        )
    }

    private fun endSession(completed: Boolean = false) {
        onSessionEnded(completed)
        generation++
        obdJob?.cancel()
        obdJob = null
        obdSession = null
        attempt?.cancel()
        attempt = null
        connection?.closeSafely()
        connection = null
        sessionAddress = null
        val last = mutableState.value.obd
        if (last.phase != ObdPhase.IDLE) {
            mutableState.value = mutableState.value.copy(
                obd = last.copy(
                    vin = ObdVinSnapshot(),
                    phase = if (last.phase == ObdPhase.ERROR) ObdPhase.ERROR else ObdPhase.STOPPED,
                    statusMessage = if (last.phase == ObdPhase.ERROR) last.statusMessage else
                        "Readings stopped. Reconnect to read from the car again.",
                    readings = last.readings.map {
                        if (it.status == ObdReadingStatus.VALID) it.copy(status = ObdReadingStatus.STALE) else it
                    },
                ),
            )
        }
    }

    fun reportServiceFailure(message: String) {
        if (closed.get()) return
        cancelScan()
        endSession()
        mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED, message = message)
    }

    internal fun setAutomaticConnectionReady(ready: Boolean) {
        if (!closed.get()) mutableState.value = mutableState.value.copy(automaticConnectionReady = ready)
    }

    internal fun setFavoriteState(preference: FavoriteAdapterPreference, loading: Boolean, saving: Boolean, message: String? = null) {
        if (!closed.get()) mutableState.value = mutableState.value.copy(
            favoriteAdapter = preference.adapter, favoriteAutoConnectPaused = preference.paused,
            favoritePreferenceLoading = loading, favoritePreferenceSaving = saving, favoriteMessage = message,
        )
    }

    internal fun setConnectionMessage(message: String) {
        if (!closed.get()) mutableState.value = mutableState.value.copy(message = message)
    }

    internal fun setAutomaticGuidance(awaitingVehicle: Boolean, message: String?) {
        if (!closed.get()) mutableState.value = mutableState.value.copy(
            isAwaitingVehicle = awaitingVehicle, automaticMessage = message,
        )
    }

    /** Terminal, idempotent owner cleanup, also invoked when the injected scope completes. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelScan()
        endSession()
        mutableState.value = mutableState.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED, isForeground = false)
    }
}

private fun AdapterDevice.key(): String = address.uppercase(Locale.ROOT)

private fun String.sameAddress(other: String?): Boolean =
    other != null && equals(other, ignoreCase = true)

private fun AdapterConnection.closeSafely() {
    runCatching { close() }
}
