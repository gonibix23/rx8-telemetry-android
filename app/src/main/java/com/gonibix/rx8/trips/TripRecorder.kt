package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdClock
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.obd.SystemObdClock
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ordered durable writes independent of Activity/service binding. Never samples a conflated UI flow. */
class TripRecorder(
    private val repository: TripRepository,
    scope: CoroutineScope,
    private val clock: ObdClock = SystemObdClock,
    private val availableBytes: () -> Long = { Long.MAX_VALUE },
    private val maxQueuedSamples: Int = 512,
    private val appVersion: String? = null,
) {
    private sealed interface Command {
        data class Start(val session: TripSession) : Command
        data class Protocol(val tripId: String, val label: String) : Command
        data class Samples(val tripId: String, val values: List<TripSample>) : Command
        data class Stop(val tripId: String, val timestamp: Long, val elapsed: Long, val status: TripStatus, val reason: String) : Command
    }
    private data class Active(
        val session: TripSession,
        val monotonicStart: Long,
        val seen: MutableMap<Pair<String, String>, Pair<Long, Long>>,
        var nextSequence: Long = 0,
        var accepting: Boolean = true,
        var protocolQueued: Boolean = session.protocolLabel != null,
    )

    private val lock = Any()
    // Only one Start and Stop can be queued per active trip. Sample count is explicitly bounded.
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var queuedSamples = 0
    private var active: Active? = null
    private val mutableState = MutableStateFlow(TripRecordingState())
    val state = mutableState.asStateFlow()

    init {
        require(maxQueuedSamples > 0)
        scope.launch {
            var recovered = false
            var persistedId: String? = null
            try {
                repository.recoverInterrupted()
                recovered = true
                synchronized(lock) { mutableState.value = mutableState.value.copy(revision = mutableState.value.revision + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(error = "Trip storage could not be opened. Try again after freeing storage.") }
            }
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Start -> {
                            if (!recovered) {
                                repository.recoverInterrupted()
                                recovered = true
                                synchronized(lock) { mutableState.value = mutableState.value.copy(revision = mutableState.value.revision + 1) }
                            }
                            checkSpace()
                            repository.create(command.session)
                            persistedId = command.session.id
                            synchronized(lock) {
                                mutableState.value = mutableState.value.copy(session = command.session, isStarting = false)
                            }
                        }
                        is Command.Protocol -> {
                            if (persistedId == command.tripId) {
                                repository.updateProtocol(command.tripId, command.label)
                                synchronized(lock) {
                                    mutableState.value = mutableState.value.copy(
                                        session = mutableState.value.session?.copy(protocolLabel = command.label),
                                    )
                                }
                            }
                        }
                        is Command.Samples -> {
                            synchronized(lock) { queuedSamples -= command.values.size }
                            if (persistedId == command.tripId) {
                                checkSpace()
                                repository.append(command.tripId, command.values)
                                synchronized(lock) {
                                    mutableState.value = mutableState.value.copy(savedSamples = mutableState.value.savedSamples + command.values.size)
                                }
                            }
                        }
                        is Command.Stop -> {
                            if (persistedId == command.tripId) {
                                repository.finish(command.tripId, command.timestamp, command.elapsed, command.status, command.reason)
                                persistedId = null
                                synchronized(lock) {
                                    active = null
                                    mutableState.value = TripRecordingState(
                                        error = if (command.status == TripStatus.INTERRUPTED) command.reason else null,
                                        revision = mutableState.value.revision + 1,
                                    )
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val failed = synchronized(lock) {
                        active?.also { it.accepting = false }
                    }
                    // A full disk may also prevent this marker. Recovery handles any remaining RECORDING row.
                    if (persistedId != null && failed != null) {
                        try {
                            repository.finish(requireNotNull(persistedId), clock.epochMillis(),
                                (clock.monotonicMillis() - failed.monotonicStart).coerceAtLeast(0),
                                TripStatus.INTERRUPTED, "Storage failure; some readings were not saved")
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Recovery on next process start. */ }
                    }
                    persistedId = null
                    recovered = false
                    synchronized(lock) {
                        active = null
                        mutableState.value = TripRecordingState(
                            error = "Recording stopped: trip storage failed. Some readings were not saved. Free storage before trying again.",
                            revision = mutableState.value.revision + 1,
                        )
                    }
                }
            }
        }
    }

    fun start(vehicle: VehicleProfile, snapshot: ObdSessionSnapshot) = synchronized(lock) {
        if (active != null || snapshot.phase != ObdPhase.LIVE) return@synchronized
        startLocked(vehicle, clock.epochMillis(), clock.monotonicMillis(), snapshot.protocolLabel,
            snapshot.readings.associate { it.key() to it.fingerprint() }.toMutableMap())
    }

    /** Connection time anchors the journey even while ELM discovery has not produced readings. */
    fun startConnected(vehicle: VehicleProfile, connectedAtEpochMillis: Long, connectedAtMonotonicMillis: Long): Boolean = synchronized(lock) {
        if (active != null) return@synchronized false
        require(connectedAtEpochMillis >= 0 && connectedAtMonotonicMillis >= 0)
        startLocked(vehicle, connectedAtEpochMillis, connectedAtMonotonicMillis, null, mutableMapOf())
        true
    }

    private fun startLocked(
        vehicle: VehicleProfile,
        epochMillis: Long,
        monotonicMillis: Long,
        protocolLabel: String?,
        seen: MutableMap<Pair<String, String>, Pair<Long, Long>>,
    ) {
        val session = TripSession(
            UUID.randomUUID().toString(), vehicle.id, vehicle.alias, epochMillis,
            protocolLabel = protocolLabel, appVersion = appVersion,
        )
        // Explicit resume supplies the dashboard baseline; connection starts have no cached samples.
        active = Active(session, monotonicMillis, seen)
        mutableState.value = TripRecordingState(isStarting = true, revision = mutableState.value.revision)
        commands.trySend(Command.Start(session))
    }

    fun onSnapshot(snapshot: ObdSessionSnapshot) = synchronized(lock) {
        val current = active?.takeIf { it.accepting } ?: return@synchronized
        if (!current.protocolQueued && !snapshot.protocolLabel.isNullOrBlank()) {
            current.protocolQueued = true
            commands.trySend(Command.Protocol(current.session.id, snapshot.protocolLabel))
        }
        val fresh = snapshot.readings.filter { reading ->
            if (!reading.isRequestResult) return@filter false
            val old = current.seen.put(reading.key(), reading.fingerprint())
            old != reading.fingerprint() && reading.status != ObdReadingStatus.STALE && reading.status != ObdReadingStatus.UNSUPPORTED
        }
        if (fresh.isNotEmpty()) {
            if (queuedSamples + fresh.size > maxQueuedSamples) {
                stopLocked(current, TripStatus.INTERRUPTED, "Recording interrupted: storage could not keep up. Some readings were not saved.")
                return@synchronized
            }
            val receivedAt = clock.monotonicMillis()
            val values = fresh.map { reading ->
                TripSample(
                    id = UUID.randomUUID().toString(), tripId = current.session.id, sequence = current.nextSequence++,
                    observedAtEpochMillis = reading.observedAtEpochMillis,
                    elapsedMillis = ((reading.monotonicMillis ?: receivedAt) - current.monotonicStart).coerceAtLeast(0),
                    metric = reading.metric,
                    value = reading.value?.takeIf { reading.status == ObdReadingStatus.VALID && it.isFinite() },
                    unit = reading.metric.unit, ecuId = reading.ecuId, quality = reading.status,
                )
            }
            queuedSamples += values.size
            commands.trySend(Command.Samples(current.session.id, values))
        }
        if (snapshot.phase == ObdPhase.ERROR || snapshot.phase == ObdPhase.STOPPED) {
            stopLocked(current, TripStatus.INTERRUPTED, "Vehicle readings interrupted; reconnect and start a new trip.")
        }
    }

    fun stop(completed: Boolean = true, reason: String = if (completed) "Stopped by user" else "Connection lost; readings ended") = synchronized(lock) {
        active?.takeIf { it.accepting }?.let { stopLocked(it, if (completed) TripStatus.COMPLETED else TripStatus.INTERRUPTED, reason) }
    }

    private fun stopLocked(current: Active, status: TripStatus, reason: String) {
        current.accepting = false
        mutableState.value = mutableState.value.copy(isStopping = true)
        commands.trySend(Command.Stop(current.session.id, clock.epochMillis(),
            (clock.monotonicMillis() - current.monotonicStart).coerceAtLeast(0), status, reason))
    }

    private fun checkSpace() {
        if (availableBytes() < MIN_FREE_BYTES) throw IOException("Insufficient free storage")
    }
    private fun ObdReading.key() = ecuId to metric.name
    private fun ObdReading.fingerprint() = elapsedMillis to observedAtEpochMillis

    companion object { const val MIN_FREE_BYTES = 10L * 1024 * 1024 }
}
