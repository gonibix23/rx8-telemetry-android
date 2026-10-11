package com.gonibix.rx8.sync

import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.telemetry.vehicle.Vin
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleProfile
import com.gonibix.rx8.vehicle.VinSource
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID

enum class ApiSampleStatus(val wire: String) {
    OK("ok"), TIMEOUT("timeout"), UNSUPPORTED("unsupported"), PARSE_ERROR("parse_error"),
    NO_DATA("no_data"), ADAPTER_ERROR("adapter_error"),
}
enum class ApiSessionStatus(val wire: String) { RECORDING("recording"), COMPLETED("completed"), INTERRUPTED("interrupted") }
enum class ApiDataOrigin(val wire: String) { REAL("real"), SIMULATED("simulated") }
enum class ApiVinSource(val wire: String) { MANUAL("manual"), OBD("obd") }
enum class ApiSampleSource(val wire: String) {
    OBD_STANDARD("obd_standard"), OBD_VENDOR("obd_vendor"), ADAPTER("adapter"), DERIVED("derived"),
}
enum class ApiMetric(val wire: String, val unit: String) {
    ENGINE_RPM("engine_rpm", "rpm"), SPEED("speed", "km/h"), COOLANT_TEMPERATURE("coolant_temperature", "Cel"),
    INTAKE_TEMPERATURE("intake_temperature", "Cel"), THROTTLE_POSITION("throttle_position", "%"), ENGINE_LOAD("engine_load", "%"),
}

data class VehicleDtoV1(
    val vehicleId: String, val revision: Long, val vin: String?, val vinSource: ApiVinSource?, val alias: String,
    val make: String? = null, val model: String? = null, val modelYear: Int? = null,
    val market: String? = null, val powerCv: Int? = null,
) {
    init {
        apiUuid(vehicleId); require(revision > 0); boundedText(alias, 60)
        require((vin == null) == (vinSource == null))
        require(vin == null || Vin.normalize(vin) == vin)
        listOf(make, model, market).forEach { it?.let { text -> boundedText(text, 64) } }
        require(modelYear == null || modelYear in 1886..9999)
        require(powerCv == null || powerCv > 0)
    }
}

data class SessionDtoV1(
    val sessionId: String, val vehicleId: String, val revision: Long,
    val startedAt: Instant, val endedAt: Instant?, val durationMs: Long?,
    val status: ApiSessionStatus, val appVersion: String?, val obdProtocol: String?, val dataOrigin: ApiDataOrigin,
) {
    init {
        apiUuid(sessionId); apiUuid(vehicleId); require(revision > 0)
        require(durationMs == null || durationMs >= 0)
        if (status == ApiSessionStatus.RECORDING) require(endedAt == null && durationMs == null)
        appVersion?.let { boundedText(it, 64) }; obdProtocol?.let { boundedText(it, 256) }
    }
}

data class SampleDtoV1(
    val sampleId: String, val sequence: Long, val observedAt: Instant, val elapsedMs: Long,
    val metric: ApiMetric, val value: Double?, val status: ApiSampleStatus,
    val source: ApiSampleSource = ApiSampleSource.OBD_STANDARD,
    val service: String? = "01", val pid: String? = null, val ecuAddress: String? = null,
) {
    val unit: String get() = metric.unit
    init {
        apiUuid(sampleId); require(sequence >= 0 && elapsedMs >= 0)
        require(if (status == ApiSampleStatus.OK) value?.isFinite() == true else value == null)
        require(service == null || service.matches(Regex("[0-9A-F]{2}")))
        require(pid == null || pid.matches(Regex("[0-9A-F]{2}")))
        ecuAddress?.let { boundedText(it, 64) }
    }
}

class TelemetryBatchDtoV1(val batchId: String, val vehicleId: String, val sessionId: String, samples: List<SampleDtoV1>) {
    val samples: List<SampleDtoV1> = Collections.unmodifiableList(samples.toList())
    init {
        apiUuid(batchId); apiUuid(vehicleId); apiUuid(sessionId)
        require(this.samples.size in 1..500)
        require(this.samples.map { it.sampleId.lowercase(Locale.ROOT) }.distinct().size == this.samples.size)
        require(this.samples.zipWithNext().all { (first, second) -> second.sequence > first.sequence })
    }
}

object ApiV1Mapper {
    fun vehicle(profile: VehicleProfile, revision: Long): VehicleDtoV1 = VehicleDtoV1(
        profile.id, revision, profile.vin, when (profile.vinSource) {
            VinSource.MANUAL -> ApiVinSource.MANUAL
            VinSource.OBD -> ApiVinSource.OBD
            null -> null
        }, profile.alias,
    )

    fun session(session: TripSession, revision: Long): SessionDtoV1 = SessionDtoV1(
        session.id, session.vehicleId, revision, instant(session.startedAtEpochMillis),
        session.endedAtEpochMillis?.let(::instant),
        session.durationMillis.takeUnless { session.status == TripStatus.RECORDING },
        ApiSessionStatus.valueOf(session.status.name), session.appVersion, session.protocolLabel,
        when (session.origin) {
            "REAL_OBD" -> ApiDataOrigin.REAL
            "SIMULATED" -> ApiDataOrigin.SIMULATED
            else -> throw IllegalArgumentException("Unknown recorded data origin.")
        },
    )

    fun sample(sample: TripSample): SampleDtoV1 {
        require(sample.unit == sample.metric.unit) { "The sample unit does not match its metric." }
        val status = when (sample.quality) {
            ObdReadingStatus.VALID -> ApiSampleStatus.OK
            ObdReadingStatus.TIMEOUT -> ApiSampleStatus.TIMEOUT
            ObdReadingStatus.UNSUPPORTED -> ApiSampleStatus.UNSUPPORTED
            ObdReadingStatus.PARSE_ERROR -> ApiSampleStatus.PARSE_ERROR
            ObdReadingStatus.NO_DATA -> ApiSampleStatus.NO_DATA
            ObdReadingStatus.ADAPTER_ERROR -> ApiSampleStatus.ADAPTER_ERROR
            ObdReadingStatus.STALE -> throw IllegalArgumentException("Stale display values are not new samples.")
        }
        return SampleDtoV1(
            sample.id, sample.sequence, instant(sample.observedAtEpochMillis), sample.elapsedMillis,
            ApiMetric.valueOf(sample.metric.name), sample.value.takeIf { status == ApiSampleStatus.OK }, status,
            pid = String.format(Locale.ROOT, "%02X", sample.metric.pid), ecuAddress = sample.ecuId,
        )
    }

    fun batch(batchId: String, vehicleId: String, sessionId: String, samples: List<TripSample>): TelemetryBatchDtoV1 {
        require(samples.size in 1..500)
        require(samples.all { it.tripId == sessionId }) { "Samples must belong to the batch session." }
        return TelemetryBatchDtoV1(batchId, vehicleId, sessionId, samples.map(::sample))
    }

    private fun instant(epochMillis: Long): Instant { require(epochMillis >= 0); return Instant.ofEpochMilli(epochMillis) }
}

private fun apiUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)) {
        "A canonical UUID is required."
    }
}

private fun boundedText(value: String, limit: Int) {
    require(value.isNotBlank() && value.length <= limit && value.none(Char::isISOControl)) { "Invalid API text field." }
}
