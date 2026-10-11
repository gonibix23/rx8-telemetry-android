package com.gonibix.rx8.connection

import com.gonibix.rx8.obd.ObdClock
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.obd.SystemObdClock
import com.gonibix.rx8.trips.TripRecorder
import com.gonibix.rx8.vehicle.VehicleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Service-dispatcher policy: a connection gets at most one automatic recording start. */
internal class AutomaticTripCoordinator(
    private val recorder: TripRecorder,
    private val scope: CoroutineScope,
    private val selectedVehicle: suspend () -> VehicleProfile?,
    private val snapshot: () -> ObdSessionSnapshot,
    private val guidance: (awaitingVehicle: Boolean, message: String?) -> Unit,
    private val clock: ObdClock = SystemObdClock,
) {
    private var generation = 0L
    private var connected = false
    private var suppressed = true
    private var preparing = false
    private var awaitingVehicle = false
    private var refreshRequested = false
    private var selectionJob: Job? = null

    /** Called exactly when the socket opens, before acquisition can emit its first fresh response. */
    suspend fun connected() {
        val expected = ++generation
        val epoch = clock.epochMillis()
        val monotonic = clock.monotonicMillis()
        connected = true
        suppressed = false
        preparing = true
        awaitingVehicle = true
        refreshRequested = false
        guidance(false, "Preparing automatic trip recording…")
        try {
            val profile = loadSelection(expected) ?: return
            if (!eligible(expected)) return
            // A preceding trip can still be committing its final samples. Do not overlap writers.
            recorder.state.first { it.session == null && !it.isStarting && !it.isStopping }
            currentCoroutineContext().ensureActive()
            if (!eligible(expected)) return
            suppressed = true // A failed write must never trigger another automatic start.
            awaitingVehicle = false
            if (recorder.startConnected(profile, epoch, monotonic)) guidance(false, null)
            else guidance(false, "Automatic recording could not start. Start recording from Trips when ready.")
        } finally {
            if (expected == generation) {
                preparing = false
                if (refreshRequested && awaitingVehicle && !suppressed) refreshSelectedVehicle()
            }
        }
    }

    /** UI calls after a successful persisted selection, including the initial Room load. */
    fun refreshSelectedVehicle() {
        if (!connected || suppressed || !awaitingVehicle) return
        if (preparing) { refreshRequested = true; return }
        selectionJob?.cancel()
        val expected = generation
        selectionJob = scope.launch {
            val profile = loadSelection(expected) ?: return@launch
            if (!eligible(expected)) return@launch
            recorder.state.first { it.session == null && !it.isStarting && !it.isStopping }
            currentCoroutineContext().ensureActive()
            if (!eligible(expected)) return@launch
            suppressed = true
            awaitingVehicle = false
            // Selecting a vehicle later cannot attribute earlier readings/time to that vehicle.
            val current = snapshot()
            if (current.phase == ObdPhase.LIVE) recorder.start(profile, current)
            else recorder.startConnected(profile, clock.epochMillis(), clock.monotonicMillis())
            guidance(false, null)
        }
    }

    fun startManually(vehicle: VehicleProfile, current: ObdSessionSnapshot) {
        if (!connected || current.phase != ObdPhase.LIVE) return
        suppress()
        recorder.start(vehicle, current)
    }

    fun stopRecording() {
        suppress()
        recorder.stop()
    }

    fun ended(completed: Boolean) {
        generation++
        connected = false
        preparing = false
        suppress()
        recorder.stop(completed)
    }

    private fun suppress() {
        suppressed = true
        awaitingVehicle = false
        refreshRequested = false
        selectionJob?.cancel()
        selectionJob = null
        guidance(false, null)
    }

    private suspend fun loadSelection(expected: Long): VehicleProfile? {
        return try {
            // Bound a failed database/selection lookup without blocking real readings indefinitely.
            val loaded = withTimeoutOrNull(5_000L) { Selection(selectedVehicle()) }
            currentCoroutineContext().ensureActive()
            if (!eligible(expected)) null else if (loaded == null) {
                guidance(true, "Vehicle selection could not be loaded. Open Vehicles to choose a profile; live readings will continue.")
                null
            } else {
                if (loaded.profile == null) {
                    guidance(true, "Select a vehicle once in Vehicles to record trips automatically. Live readings continue without recording.")
                }
                loaded.profile
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (eligible(expected)) guidance(true, "Vehicle selection could not be loaded. Open Vehicles to choose a profile; live readings will continue.")
            null
        }
    }

    private fun eligible(expected: Long) = expected == generation && connected && !suppressed
    private data class Selection(val profile: VehicleProfile?)
}
