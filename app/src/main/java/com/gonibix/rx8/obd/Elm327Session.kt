package com.gonibix.rx8.obd

import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.telemetry.obd.ElmError
import com.gonibix.rx8.telemetry.obd.ElmResponseParser
import com.gonibix.rx8.telemetry.obd.ElmVinParser
import com.gonibix.rx8.telemetry.obd.ObdFailure
import com.gonibix.rx8.telemetry.obd.ObdParseException
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import com.gonibix.rx8.telemetry.obd.ObdResponse
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One explicitly started, service-owned session. It never reconnects or writes to the ECU. */
class Elm327Session(
    connection: AdapterConnection,
    private val clock: ObdClock,
) : ObdSession {
    constructor(connection: AdapterConnection) : this(connection, SystemObdClock)

    private val exchange = ElmCommandExchange(connection)
    private val started = AtomicBoolean(false)
    private val stateLock = Any()
    private var snapshot = ObdSessionSnapshot()
    private var startedAt = 0L
    private var liveAt: Long? = null
    private var successfulResponses = 0L
    private var activeMetric: ObdMetric? = null
    private val vinRequested = AtomicBoolean(false)
    private val vinBusy = AtomicBoolean(false)

    override fun requestVin(): Boolean = synchronized(stateLock) {
        if (snapshot.phase != ObdPhase.LIVE || !vinBusy.compareAndSet(false, true)) return@synchronized false
        vinRequested.set(true)
        snapshot = snapshot.copy(vin = ObdVinSnapshot(ObdVinStatus.READING, "VIN request queued after the current sensor reading."))
        true
    }

    override suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit): Unit = coroutineScope {
        check(started.compareAndSet(false, true)) { "An OBD session can run only once." }
        startedAt = clock.monotonicMillis()
        publish(onSnapshot) { it.copy(phase = ObdPhase.INITIALIZING, statusMessage = "Preparing the adapter.") }
        val ticker = launch {
            while (isActive) {
                delay(1_000L)
                publish(onSnapshot) { it }
            }
        }
        try {
            initialize(onSnapshot)
            publish(onSnapshot) { it.copy(phase = ObdPhase.DISCOVERING, statusMessage = "Finding the vehicle protocol and supported parameters.") }
            // Auto detection must see a vehicle request before ATDPN can identify its result.
            val firstPage = request("0100", 20_000L) { body ->
                checkAdapterReply("0100", body)
                body
            }
            val protocol = ElmResponseParser.parseProtocol(exchange.execute("ATDPN"))
            publish(onSnapshot) { it.copy(protocolLabel = protocol.description) }
            val initialCapabilities = ElmResponseParser.parseMode01(firstPage, protocol, 0)
            val capabilities = discoverCapabilities(protocol, initialCapabilities)
            val now = elapsed()
            val timestamp = clock.epochMillis()
            val readings = capabilities.flatMap { ecu ->
                ObdMetric.entries.map { metric ->
                    ObdReading(
                        metric = metric,
                        value = null,
                        status = if (metric.pid in ecu.supportedPids) ObdReadingStatus.NO_DATA else ObdReadingStatus.UNSUPPORTED,
                        ecuId = ecu.ecuId,
                        observedAtEpochMillis = timestamp,
                        elapsedMillis = now,
                        monotonicMillis = startedAt + now,
                    )
                }
            }
            publish(onSnapshot) {
                liveAt = clock.monotonicMillis()
                it.copy(
                    phase = ObdPhase.LIVE,
                    statusMessage = "Vehicle communication verified. Reading available parameters.",
                    protocolLabel = protocol.description,
                    ecus = capabilities,
                    readings = readings,
                    supportedPidCount = capabilities.flatMap { ecu -> ecu.supportedPids }.toSet().size,
                )
            }
            poll(protocol, capabilities, onSnapshot)
        } catch (cancelled: CancellationException) {
            ticker.cancel()
            exchange.close()
            publish(onSnapshot) {
                if (it.phase == ObdPhase.ERROR) it else it.copy(
                    phase = ObdPhase.STOPPED,
                    statusMessage = "Readings stopped. Reconnect the adapter to start again.",
                    readings = it.readings.map(::markStale),
                    vin = ObdVinSnapshot(),
                )
            }
            throw cancelled
        } catch (failure: Exception) {
            ticker.cancel()
            exchange.close()
            val status = if (failure is ObdExchangeException) failure.status else statusFor(failure)
            val message = terminalMessage(failure)
            publish(onSnapshot) {
                it.copy(
                    phase = ObdPhase.ERROR,
                    statusMessage = message,
                    vin = ObdVinSnapshot(),
                    readings = it.readings.map { reading ->
                        if (reading.metric == activeMetric && reading.status != ObdReadingStatus.UNSUPPORTED) {
                            val now = elapsed()
                            reading.copy(value = null, status = status, observedAtEpochMillis = clock.epochMillis(), elapsedMillis = now, monotonicMillis = startedAt + now)
                        } else markStale(reading)
                    },
                )
            }
            throw ObdSessionException(message, linkClosed = true, cause = failure)
        } finally {
            vinRequested.set(false)
            vinBusy.set(false)
            ticker.cancel()
            exchange.close()
        }
    }

    private suspend fun initialize(onSnapshot: (ObdSessionSnapshot) -> Unit) {
        val reset = ElmResponseParser.parse("ATZ", exchange.execute("ATZ"))
        val identity = reset.dataLines.singleOrNull()?.trim()
        if (reset.errors.isNotEmpty() || reset.isOk || identity == null || !ADAPTER_BANNER.matches(identity)) {
            throw ObdSessionException("The adapter did not identify itself as an ELM-compatible device.")
        }
        publish(onSnapshot) { it.copy(adapterIdentity = identity) }
        for (command in INITIALIZATION_COMMANDS) {
            val reply = ElmResponseParser.parse(command, exchange.execute(command))
            if (!reply.isOk || reply.errors.isNotEmpty() || reply.dataLines.isNotEmpty()) {
                throw ObdSessionException("The adapter could not apply the required connection settings.")
            }
        }
    }

    private suspend fun discoverCapabilities(
        protocol: ObdProtocol,
        firstPage: List<ObdResponse>,
    ): List<ObdEcuCapabilities> {
        if (firstPage.isEmpty()) throw ObdSessionException("No vehicle ECU answered capability discovery.")
        val supported = linkedMapOf<String, MutableSet<Int>>()
        for (response in firstPage) supported.getOrPut(response.ecu) { linkedSetOf() }.addAll(ElmResponseParser.supportedPids(response))
        for (page in 0x20..0xe0 step 0x20) {
            val expected = supported.filterValues { page in it }.keys
            if (expected.isEmpty()) break
            val command = "01%02X".format(Locale.ROOT, page)
            val responses = request(command) { ElmResponseParser.parseMode01(it, protocol, page) }
            if (!responses.map { it.ecu }.containsAll(expected)) {
                throw ObdSessionException("Some ECUs did not complete capability discovery. Reconnect to try again.")
            }
            for (response in responses) {
                if (response.ecu in expected) supported.getValue(response.ecu).addAll(ElmResponseParser.supportedPids(response))
            }
        }
        // Page-continuation bits are metadata, not sensor capabilities.
        return supported.map { (ecu, pids) -> ObdEcuCapabilities(ecu, pids.filter { it % 0x20 != 0 }.toSet()) }
            .sortedBy { it.ecuId }
    }

    private suspend fun poll(
        protocol: ObdProtocol,
        capabilities: List<ObdEcuCapabilities>,
        onSnapshot: (ObdSessionSnapshot) -> Unit,
    ) {
        val schedule = POLLING_ORDER.filter { metric -> capabilities.any { metric.pid in it.supportedPids } }
        if (schedule.isEmpty()) {
            publish(onSnapshot) { it.copy(statusMessage = "Vehicle communication verified. None of the six dashboard parameters is supported.") }
            while (currentCoroutineContext().isActive) {
                readPendingVin(protocol, onSnapshot)
                delay(250L)
            }
            return
        }
        var consecutiveErrors = 0
        while (currentCoroutineContext().isActive) {
            for (metric in schedule) {
                currentCoroutineContext().ensureActive()
                readPendingVin(protocol, onSnapshot)
                activeMetric = metric
                val began = clock.monotonicMillis()
                try {
                    val responses = request(metric.parameter.command) { ElmResponseParser.parseMode01(it, protocol, metric.pid) }
                    val decoded = responses.associate { it.ecu to metric.parameter.decode(it.data) }
                    val expected = capabilities.filter { metric.pid in it.supportedPids }.map { it.ecuId }.toSet()
                    val received = decoded.filterKeys { it in expected }
                    val now = elapsed()
                    val timestamp = clock.epochMillis()
                    consecutiveErrors = if (received.isEmpty()) (consecutiveErrors + 1).coerceAtMost(5) else 0
                    publish(onSnapshot) {
                        successfulResponses += received.size
                        it.copy(
                            statusMessage = if (received.size == expected.size) "Reading available vehicle parameters." else "Some ECUs did not return this reading.",
                            readings = it.readings.map { reading ->
                                if (reading.metric != metric || reading.ecuId !in expected) reading else reading.copy(
                                    value = received[reading.ecuId],
                                    status = if (reading.ecuId in received) ObdReadingStatus.VALID else ObdReadingStatus.NO_DATA,
                                    observedAtEpochMillis = timestamp,
                                    elapsedMillis = now,
                                    monotonicMillis = startedAt + now,
                                )
                            },
                        )
                    }
                } catch (failure: ObdParseException) {
                    consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(5)
                    val status = statusFor(failure)
                    val now = elapsed()
                    val timestamp = clock.epochMillis()
                    publish(onSnapshot) {
                        it.copy(
                            statusMessage = readingFailureMessage(status),
                            readings = it.readings.map { reading ->
                                if (reading.metric != metric || reading.status == ObdReadingStatus.UNSUPPORTED) reading else reading.copy(
                                    value = null, status = status, observedAtEpochMillis = timestamp, elapsedMillis = now,
                                    monotonicMillis = startedAt + now,
                                )
                            },
                        )
                    }
                }
                activeMetric = null
                val latency = (clock.monotonicMillis() - began).coerceAtLeast(0L)
                // Deliberately serial and conservative. Slower replies and errors increase the pause.
                delay((250L + latency / 4L + consecutiveErrors * 350L).coerceAtMost(2_500L))
            }
        }
    }

    private suspend fun readPendingVin(protocol: ObdProtocol, onSnapshot: (ObdSessionSnapshot) -> Unit) {
        if (!vinRequested.getAndSet(false)) return
        publish(onSnapshot) { it.copy(vin = ObdVinSnapshot(ObdVinStatus.READING, "Reading the VIN. Live sensors resume afterward.")) }
        try {
            if (protocol.canIdBits != null) {
                val flowControl = ElmResponseParser.parse("ATCFC1", exchange.execute("ATCFC1"))
                if (!flowControl.isOk) throw ObdParseException(ObdFailure.ADAPTER_ERROR, "VIN flow control could not be enabled.")
            }
            val candidates = request("0902") { ElmVinParser.parse(it, protocol) }
            publish(onSnapshot) {
                it.copy(vin = ObdVinSnapshot(
                    ObdVinStatus.AVAILABLE,
                    "VIN response received. Review a candidate before saving it to a vehicle profile.",
                    candidates,
                ))
            }
        } catch (failure: ObdParseException) {
            val noData = failure.reason == ObdFailure.NO_DATA || failure.reason == ObdFailure.NEGATIVE_RESPONSE
            publish(onSnapshot) {
                it.copy(vin = ObdVinSnapshot(
                    if (noData) ObdVinStatus.NO_DATA else ObdVinStatus.ERROR,
                    if (noData) "No VIN was returned. You can continue using a saved vehicle ID." else
                        "The VIN response could not be read. Live sensors continue; you can use a saved vehicle ID.",
                ))
            }
        } finally {
            vinBusy.set(false)
        }
    }

    private suspend fun <T> request(
        command: String,
        timeoutMillis: Long = 5_000L,
        decode: (String) -> T,
    ): T {
        repeat(2) { attempt ->
            val body = exchange.execute(command, timeoutMillis)
            try {
                checkAdapterReply(command, body)
                return decode(body)
            } catch (failure: ObdParseException) {
                val retryable = failure.reason == ObdFailure.NO_DATA || failure.reason == ObdFailure.ADAPTER_ERROR
                if (attempt != 0 || !retryable) throw failure
                // Only complete framed errors are retryable; transport/framing failures close the link.
                delay(500L)
            }
        }
        error("The bounded request did not produce a result.")
    }

    private fun checkAdapterReply(command: String, body: String) {
        val reply = ElmResponseParser.parse(command, body)
        if (reply.errors.isNotEmpty()) {
            val reason = if (reply.errors.all { it == ElmError.NO_DATA }) ObdFailure.NO_DATA else ObdFailure.ADAPTER_ERROR
            throw ObdParseException(reason, "The adapter did not return a vehicle response.")
        }
    }

    private fun publish(onSnapshot: (ObdSessionSnapshot) -> Unit, update: (ObdSessionSnapshot) -> ObdSessionSnapshot) {
        synchronized(stateLock) {
            val now = elapsed()
            val updated = update(snapshot)
            val liveDuration = (clock.monotonicMillis() - (liveAt ?: clock.monotonicMillis())).coerceAtLeast(1L)
            snapshot = updated.copy(
                elapsedMillis = now,
                effectiveResponsesPerSecond = if (liveAt == null || successfulResponses == 0L) 0.0 else successfulResponses * 1_000.0 / liveDuration,
                readings = updated.readings.map { reading ->
                    if (reading.status == ObdReadingStatus.VALID && now - reading.elapsedMillis >= 5_000L) markStale(reading) else reading
                },
            )
            onSnapshot(snapshot)
        }
    }

    private fun elapsed(): Long = (clock.monotonicMillis() - startedAt).coerceAtLeast(0L)

    private fun markStale(reading: ObdReading): ObdReading =
        if (reading.status == ObdReadingStatus.VALID) reading.copy(status = ObdReadingStatus.STALE) else reading

    private fun statusFor(failure: Exception): ObdReadingStatus = when ((failure as? ObdParseException)?.reason) {
        ObdFailure.NO_DATA -> ObdReadingStatus.NO_DATA
        ObdFailure.ADAPTER_ERROR, ObdFailure.NEGATIVE_RESPONSE -> ObdReadingStatus.ADAPTER_ERROR
        else -> ObdReadingStatus.PARSE_ERROR
    }

    private fun terminalMessage(failure: Exception): String = when (failure) {
        is ObdSessionException -> failure.message ?: "The OBD session could not start. Reconnect to try again."
        is ObdExchangeException -> failure.message ?: "The adapter connection was interrupted."
        is ObdParseException -> when (failure.reason) {
            ObdFailure.NO_DATA -> "No vehicle data was returned. Check the ignition and reconnect to try again."
            ObdFailure.UNSUPPORTED_PROTOCOL -> "The adapter selected a protocol that this app cannot decode yet."
            ObdFailure.ADAPTER_ERROR, ObdFailure.NEGATIVE_RESPONSE -> "The adapter could not establish vehicle communication. Check the ignition and reconnect."
            ObdFailure.MALFORMED -> "The vehicle response could not be decoded. Reconnect to try again."
        }
        else -> "The OBD session was interrupted. Reconnect to try again."
    }

    private fun readingFailureMessage(status: ObdReadingStatus): String = when (status) {
        ObdReadingStatus.NO_DATA -> "The vehicle did not return this reading."
        ObdReadingStatus.ADAPTER_ERROR -> "The adapter reported a problem with this reading."
        else -> "This vehicle response could not be decoded."
    }

    private companion object {
        val ADAPTER_BANNER = Regex("(?:ELM327|STN[0-9]+)(?:[ \\t]+[A-Za-z0-9._ -]+)?", RegexOption.IGNORE_CASE)
        val INITIALIZATION_COMMANDS = listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATCAF1", "ATSP0")
        val POLLING_ORDER = listOf(
            ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE,
            ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.INTAKE_TEMPERATURE,
            ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.THROTTLE_POSITION, ObdMetric.ENGINE_LOAD,
        )
    }
}
