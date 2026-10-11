package com.gonibix.rx8.capabilities

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import java.util.Collections
import java.util.UUID

/** A frozen, allowlisted display snapshot. It contains no acquisition history or request counts. */
class CapabilityReport(
    val reportId: String,
    val capturedAtEpochMillis: Long,
    val appVersion: String,
    val androidApiLevel: Int,
    val phase: ObdPhase,
    val protocolLabel: String?,
    val elapsedMillis: Long,
    val effectiveResponsesPerSecond: Double?,
    ecus: List<CapabilityEcu>,
) {
    val ecus: List<CapabilityEcu> = immutableCopy(ecus)

    init {
        require(runCatching { UUID.fromString(reportId).toString() == reportId }.getOrDefault(false)) { INVALID_REPORT }
        require(capturedAtEpochMillis >= 0 && elapsedMillis >= 0 && androidApiLevel in 26..100) { INVALID_REPORT }
        require(appVersion.length <= 32 && APP_VERSION.matches(appVersion)) { INVALID_REPORT }
        require(phase in CAPTURE_PHASES && (protocolLabel == null || protocolLabel in PROTOCOL_LABELS)) { INVALID_REPORT }
        require(effectiveResponsesPerSecond == null || effectiveResponsesPerSecond.isFinite() && effectiveResponsesPerSecond >= 0) { INVALID_REPORT }
        require(this.ecus.size in 1..MAX_ECUS && this.ecus.map { it.address }.distinct().size == this.ecus.size) { INVALID_REPORT }
        require(phase == ObdPhase.LIVE || this.ecus.none { ecu -> ecu.metrics.any { it.reading?.status == ObdReadingStatus.VALID } }) { INVALID_REPORT }
    }
}

class CapabilityEcu(address: String, advertisedPids: List<Int>, metrics: List<CapabilityMetric>) {
    val address: String = address
    val advertisedPids: List<Int> = immutableCopy(advertisedPids)
    val metrics: List<CapabilityMetric> = immutableCopy(metrics)

    init {
        require(validEcuAddress(address)) { INVALID_REPORT }
        require(this.advertisedPids.size <= 248 && this.advertisedPids.all { it in 1..255 && it % 32 != 0 }) { INVALID_REPORT }
        require(this.advertisedPids == this.advertisedPids.distinct().sorted()) { INVALID_REPORT }
        require(this.metrics.map { it.metric } == ObdMetric.entries) { INVALID_REPORT }
        require(this.metrics.all { it.advertised == (it.metric.pid in this.advertisedPids) }) { INVALID_REPORT }
    }
}

data class CapabilityMetric(val metric: ObdMetric, val advertised: Boolean, val reading: CapabilityReading?) {
    init { require(advertised || reading == null) { INVALID_REPORT } }
}

data class CapabilityReading(val value: Double?, val status: ObdReadingStatus, val ageMillis: Long?) {
    init {
        require(ageMillis == null || ageMillis >= 0) { INVALID_REPORT }
        require(value == null || value.isFinite()) { INVALID_REPORT }
        require(status != ObdReadingStatus.VALID || value != null && ageMillis != null && ageMillis < 5_000L) { INVALID_REPORT }
        require(status == ObdReadingStatus.VALID || status == ObdReadingStatus.STALE || value == null) { INVALID_REPORT }
    }
}

object CapabilityReports {
    fun canCapture(snapshot: ObdSessionSnapshot): Boolean = runCatching {
        require(snapshot.phase in CAPTURE_PHASES && snapshot.elapsedMillis >= 0)
        captureEcus(snapshot)
    }.isSuccess

    fun capture(
        snapshot: ObdSessionSnapshot,
        appVersion: String,
        androidApiLevel: Int,
        capturedAtEpochMillis: Long,
        reportId: String = UUID.randomUUID().toString(),
    ): CapabilityReport {
        require(snapshot.phase in CAPTURE_PHASES && snapshot.elapsedMillis >= 0) { INVALID_REPORT }
        return CapabilityReport(
            reportId, capturedAtEpochMillis, appVersion, androidApiLevel, snapshot.phase,
            snapshot.protocolLabel?.takeIf { it in PROTOCOL_LABELS }, snapshot.elapsedMillis,
            snapshot.effectiveResponsesPerSecond.takeIf { it.isFinite() && it >= 0 && snapshot.elapsedMillis > 0 },
            captureEcus(snapshot),
        )
    }

    private fun captureEcus(snapshot: ObdSessionSnapshot): List<CapabilityEcu> {
        // Check raw input lengths before copying or grouping untrusted identifiers.
        require(snapshot.ecus.size in 1..MAX_ECUS && snapshot.readings.size <= MAX_ECUS * ObdMetric.entries.size) { INVALID_REPORT }
        val capabilities = snapshot.ecus.filter { validEcuAddress(it.ecuId) }
        require(capabilities.isNotEmpty() && capabilities.map { it.ecuId }.distinct().size == capabilities.size) { INVALID_REPORT }
        return capabilities.sortedBy { it.ecuId }.map { ecu ->
            require(ecu.supportedPids.size <= 255 && ecu.supportedPids.all { it in 1..255 }) { INVALID_REPORT }
            val pids = ecu.supportedPids.filter { it % 32 != 0 }.sorted()
            val metrics = ObdMetric.entries.map { metric ->
                val advertised = metric.pid in pids
                val candidates = if (advertised) snapshot.readings.filter { it.ecuId == ecu.ecuId && it.metric == metric } else emptyList()
                require(candidates.size <= 1) { INVALID_REPORT }
                CapabilityMetric(metric, advertised, candidates.singleOrNull()?.let { captureReading(it, snapshot) })
            }
            CapabilityEcu(ecu.ecuId, pids, metrics)
        }
    }

    private fun captureReading(reading: ObdReading, snapshot: ObdSessionSnapshot): CapabilityReading? {
        val age = if (reading.elapsedMillis in 0..snapshot.elapsedMillis) snapshot.elapsedMillis - reading.elapsedMillis else null
        val status = if (reading.status == ObdReadingStatus.VALID &&
            (snapshot.phase != ObdPhase.LIVE || age == null || age >= 5_000L)) ObdReadingStatus.STALE else reading.status
        val carriesValue = status == ObdReadingStatus.VALID || status == ObdReadingStatus.STALE
        // Invalid numeric input is unavailable, never a fabricated zero or a successful reading.
        if (carriesValue && reading.value?.isFinite() != true) return null
        return CapabilityReading(reading.value.takeIf { carriesValue }, status, age)
    }
}

private fun validEcuAddress(value: String): Boolean = when {
    value.matches(Regex("[0-9A-F]{2}")) -> true
    value.matches(Regex("[0-9A-F]{3}")) -> value.toInt(16) <= 0x7FF
    value.matches(Regex("[0-9A-F]{8}")) -> value.toLong(16) <= 0x1FFFFFFFL
    else -> false
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
private const val MAX_ECUS = 32
private const val INVALID_REPORT = "The capability snapshot is unavailable or invalid."
private val CAPTURE_PHASES = setOf(ObdPhase.LIVE, ObdPhase.STOPPED, ObdPhase.ERROR)
private val PROTOCOL_LABELS = ObdProtocol.entries.map { it.description }.toSet()
private val APP_VERSION = Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-(?:dev|alpha|beta|rc)(?:[.-][0-9]{1,3})?)?")
