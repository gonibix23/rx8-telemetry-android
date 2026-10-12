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
enum class ApiMetric(val metric: com.gonibix.rx8.obd.ObdMetric) {
    ENGINE_RPM(com.gonibix.rx8.obd.ObdMetric.ENGINE_RPM),
    SPEED(com.gonibix.rx8.obd.ObdMetric.SPEED),
    COOLANT_TEMPERATURE(com.gonibix.rx8.obd.ObdMetric.COOLANT_TEMPERATURE),
    INTAKE_TEMPERATURE(com.gonibix.rx8.obd.ObdMetric.INTAKE_TEMPERATURE),
    THROTTLE_POSITION(com.gonibix.rx8.obd.ObdMetric.THROTTLE_POSITION),
    ENGINE_LOAD(com.gonibix.rx8.obd.ObdMetric.ENGINE_LOAD),
    SHORT_TERM_FUEL_TRIM_BANK_1(com.gonibix.rx8.obd.ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_1),
    SHORT_TERM_FUEL_TRIM_BANK_3(com.gonibix.rx8.obd.ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_3),
    LONG_TERM_FUEL_TRIM_BANK_1(com.gonibix.rx8.obd.ObdMetric.LONG_TERM_FUEL_TRIM_BANK_1),
    LONG_TERM_FUEL_TRIM_BANK_3(com.gonibix.rx8.obd.ObdMetric.LONG_TERM_FUEL_TRIM_BANK_3),
    SHORT_TERM_FUEL_TRIM_BANK_2(com.gonibix.rx8.obd.ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_2),
    SHORT_TERM_FUEL_TRIM_BANK_4(com.gonibix.rx8.obd.ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_4),
    LONG_TERM_FUEL_TRIM_BANK_2(com.gonibix.rx8.obd.ObdMetric.LONG_TERM_FUEL_TRIM_BANK_2),
    LONG_TERM_FUEL_TRIM_BANK_4(com.gonibix.rx8.obd.ObdMetric.LONG_TERM_FUEL_TRIM_BANK_4),
    FUEL_PRESSURE(com.gonibix.rx8.obd.ObdMetric.FUEL_PRESSURE),
    MANIFOLD_PRESSURE(com.gonibix.rx8.obd.ObdMetric.MANIFOLD_PRESSURE),
    IGNITION_TIMING(com.gonibix.rx8.obd.ObdMetric.IGNITION_TIMING),
    MASS_AIR_FLOW(com.gonibix.rx8.obd.ObdMetric.MASS_AIR_FLOW),
    ENGINE_RUN_TIME(com.gonibix.rx8.obd.ObdMetric.ENGINE_RUN_TIME),
    DISTANCE_WITH_MIL(com.gonibix.rx8.obd.ObdMetric.DISTANCE_WITH_MIL),
    FUEL_RAIL_PRESSURE_RELATIVE(com.gonibix.rx8.obd.ObdMetric.FUEL_RAIL_PRESSURE_RELATIVE),
    FUEL_RAIL_PRESSURE_GAUGE(com.gonibix.rx8.obd.ObdMetric.FUEL_RAIL_PRESSURE_GAUGE),
    COMMANDED_EGR(com.gonibix.rx8.obd.ObdMetric.COMMANDED_EGR),
    EGR_ERROR(com.gonibix.rx8.obd.ObdMetric.EGR_ERROR),
    COMMANDED_EVAP_PURGE(com.gonibix.rx8.obd.ObdMetric.COMMANDED_EVAP_PURGE),
    FUEL_LEVEL(com.gonibix.rx8.obd.ObdMetric.FUEL_LEVEL),
    WARM_UPS_SINCE_CLEAR(com.gonibix.rx8.obd.ObdMetric.WARM_UPS_SINCE_CLEAR),
    DISTANCE_SINCE_CLEAR(com.gonibix.rx8.obd.ObdMetric.DISTANCE_SINCE_CLEAR),
    EVAP_VAPOR_PRESSURE(com.gonibix.rx8.obd.ObdMetric.EVAP_VAPOR_PRESSURE),
    BAROMETRIC_PRESSURE(com.gonibix.rx8.obd.ObdMetric.BAROMETRIC_PRESSURE),
    CONTROL_MODULE_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.CONTROL_MODULE_VOLTAGE),
    ABSOLUTE_ENGINE_LOAD(com.gonibix.rx8.obd.ObdMetric.ABSOLUTE_ENGINE_LOAD),
    COMMANDED_EQUIVALENCE_RATIO(com.gonibix.rx8.obd.ObdMetric.COMMANDED_EQUIVALENCE_RATIO),
    RELATIVE_THROTTLE_POSITION(com.gonibix.rx8.obd.ObdMetric.RELATIVE_THROTTLE_POSITION),
    AMBIENT_TEMPERATURE(com.gonibix.rx8.obd.ObdMetric.AMBIENT_TEMPERATURE),
    THROTTLE_POSITION_B(com.gonibix.rx8.obd.ObdMetric.THROTTLE_POSITION_B),
    THROTTLE_POSITION_C(com.gonibix.rx8.obd.ObdMetric.THROTTLE_POSITION_C),
    ACCELERATOR_POSITION_D(com.gonibix.rx8.obd.ObdMetric.ACCELERATOR_POSITION_D),
    ACCELERATOR_POSITION_E(com.gonibix.rx8.obd.ObdMetric.ACCELERATOR_POSITION_E),
    ACCELERATOR_POSITION_F(com.gonibix.rx8.obd.ObdMetric.ACCELERATOR_POSITION_F),
    COMMANDED_THROTTLE(com.gonibix.rx8.obd.ObdMetric.COMMANDED_THROTTLE),
    TIME_WITH_MIL(com.gonibix.rx8.obd.ObdMetric.TIME_WITH_MIL),
    TIME_SINCE_CLEAR(com.gonibix.rx8.obd.ObdMetric.TIME_SINCE_CLEAR),
    ALCOHOL_PERCENTAGE(com.gonibix.rx8.obd.ObdMetric.ALCOHOL_PERCENTAGE),
    EVAP_VAPOR_PRESSURE_ABSOLUTE(com.gonibix.rx8.obd.ObdMetric.EVAP_VAPOR_PRESSURE_ABSOLUTE),
    EVAP_VAPOR_PRESSURE_WIDE(com.gonibix.rx8.obd.ObdMetric.EVAP_VAPOR_PRESSURE_WIDE),
    FUEL_RAIL_PRESSURE_ABSOLUTE(com.gonibix.rx8.obd.ObdMetric.FUEL_RAIL_PRESSURE_ABSOLUTE),
    RELATIVE_ACCELERATOR_POSITION(com.gonibix.rx8.obd.ObdMetric.RELATIVE_ACCELERATOR_POSITION),
    HYBRID_BATTERY_REMAINING(com.gonibix.rx8.obd.ObdMetric.HYBRID_BATTERY_REMAINING),
    ENGINE_OIL_TEMPERATURE(com.gonibix.rx8.obd.ObdMetric.ENGINE_OIL_TEMPERATURE),
    FUEL_INJECTION_TIMING(com.gonibix.rx8.obd.ObdMetric.FUEL_INJECTION_TIMING),
    ENGINE_FUEL_RATE(com.gonibix.rx8.obd.ObdMetric.ENGINE_FUEL_RATE),
    DRIVER_DEMAND_TORQUE(com.gonibix.rx8.obd.ObdMetric.DRIVER_DEMAND_TORQUE),
    ACTUAL_ENGINE_TORQUE(com.gonibix.rx8.obd.ObdMetric.ACTUAL_ENGINE_TORQUE),
    ENGINE_REFERENCE_TORQUE(com.gonibix.rx8.obd.ObdMetric.ENGINE_REFERENCE_TORQUE),
    OXYGEN_SENSOR_1_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_1_VOLTAGE),
    OXYGEN_SENSOR_1_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_1_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_1_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO),
    WIDE_OXYGEN_SENSOR_1_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_1_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_1_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_1_RATIO),
    CURRENT_OXYGEN_SENSOR_1_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_1_CURRENT),
    OXYGEN_SENSOR_2_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_2_VOLTAGE),
    OXYGEN_SENSOR_2_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_2_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_2_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_2_RATIO),
    WIDE_OXYGEN_SENSOR_2_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_2_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_2_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_2_RATIO),
    CURRENT_OXYGEN_SENSOR_2_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_2_CURRENT),
    OXYGEN_SENSOR_3_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_3_VOLTAGE),
    OXYGEN_SENSOR_3_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_3_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_3_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_3_RATIO),
    WIDE_OXYGEN_SENSOR_3_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_3_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_3_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_3_RATIO),
    CURRENT_OXYGEN_SENSOR_3_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_3_CURRENT),
    OXYGEN_SENSOR_4_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_4_VOLTAGE),
    OXYGEN_SENSOR_4_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_4_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_4_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_4_RATIO),
    WIDE_OXYGEN_SENSOR_4_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_4_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_4_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_4_RATIO),
    CURRENT_OXYGEN_SENSOR_4_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_4_CURRENT),
    OXYGEN_SENSOR_5_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_5_VOLTAGE),
    OXYGEN_SENSOR_5_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_5_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_5_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_5_RATIO),
    WIDE_OXYGEN_SENSOR_5_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_5_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_5_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_5_RATIO),
    CURRENT_OXYGEN_SENSOR_5_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_5_CURRENT),
    OXYGEN_SENSOR_6_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_6_VOLTAGE),
    OXYGEN_SENSOR_6_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_6_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_6_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_6_RATIO),
    WIDE_OXYGEN_SENSOR_6_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_6_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_6_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_6_RATIO),
    CURRENT_OXYGEN_SENSOR_6_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_6_CURRENT),
    OXYGEN_SENSOR_7_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_7_VOLTAGE),
    OXYGEN_SENSOR_7_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_7_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_7_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_7_RATIO),
    WIDE_OXYGEN_SENSOR_7_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_7_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_7_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_7_RATIO),
    CURRENT_OXYGEN_SENSOR_7_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_7_CURRENT),
    OXYGEN_SENSOR_8_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_8_VOLTAGE),
    OXYGEN_SENSOR_8_FUEL_TRIM(com.gonibix.rx8.obd.ObdMetric.OXYGEN_SENSOR_8_FUEL_TRIM),
    WIDE_OXYGEN_SENSOR_8_RATIO(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_8_RATIO),
    WIDE_OXYGEN_SENSOR_8_VOLTAGE(com.gonibix.rx8.obd.ObdMetric.WIDE_OXYGEN_SENSOR_8_VOLTAGE),
    CURRENT_OXYGEN_SENSOR_8_RATIO(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_8_RATIO),
    CURRENT_OXYGEN_SENSOR_8_CURRENT(com.gonibix.rx8.obd.ObdMetric.CURRENT_OXYGEN_SENSOR_8_CURRENT),
    CATALYST_TEMPERATURE_B1_S1(com.gonibix.rx8.obd.ObdMetric.CATALYST_TEMPERATURE_B1_S1),
    CATALYST_TEMPERATURE_B2_S1(com.gonibix.rx8.obd.ObdMetric.CATALYST_TEMPERATURE_B2_S1),
    CATALYST_TEMPERATURE_B1_S2(com.gonibix.rx8.obd.ObdMetric.CATALYST_TEMPERATURE_B1_S2),
    CATALYST_TEMPERATURE_B2_S2(com.gonibix.rx8.obd.ObdMetric.CATALYST_TEMPERATURE_B2_S2),
    SECONDARY_SHORT_TERM_TRIM_BANK_1(com.gonibix.rx8.obd.ObdMetric.SECONDARY_SHORT_TERM_TRIM_BANK_1),
    SECONDARY_SHORT_TERM_TRIM_BANK_3(com.gonibix.rx8.obd.ObdMetric.SECONDARY_SHORT_TERM_TRIM_BANK_3),
    SECONDARY_LONG_TERM_TRIM_BANK_1(com.gonibix.rx8.obd.ObdMetric.SECONDARY_LONG_TERM_TRIM_BANK_1),
    SECONDARY_LONG_TERM_TRIM_BANK_3(com.gonibix.rx8.obd.ObdMetric.SECONDARY_LONG_TERM_TRIM_BANK_3),
    SECONDARY_SHORT_TERM_TRIM_BANK_2(com.gonibix.rx8.obd.ObdMetric.SECONDARY_SHORT_TERM_TRIM_BANK_2),
    SECONDARY_SHORT_TERM_TRIM_BANK_4(com.gonibix.rx8.obd.ObdMetric.SECONDARY_SHORT_TERM_TRIM_BANK_4),
    SECONDARY_LONG_TERM_TRIM_BANK_2(com.gonibix.rx8.obd.ObdMetric.SECONDARY_LONG_TERM_TRIM_BANK_2),
    SECONDARY_LONG_TERM_TRIM_BANK_4(com.gonibix.rx8.obd.ObdMetric.SECONDARY_LONG_TERM_TRIM_BANK_4),
    ENGINE_TORQUE_POINT_1(com.gonibix.rx8.obd.ObdMetric.ENGINE_TORQUE_POINT_1),
    ENGINE_TORQUE_POINT_2(com.gonibix.rx8.obd.ObdMetric.ENGINE_TORQUE_POINT_2),
    ENGINE_TORQUE_POINT_3(com.gonibix.rx8.obd.ObdMetric.ENGINE_TORQUE_POINT_3),
    ENGINE_TORQUE_POINT_4(com.gonibix.rx8.obd.ObdMetric.ENGINE_TORQUE_POINT_4),
    ENGINE_TORQUE_POINT_5(com.gonibix.rx8.obd.ObdMetric.ENGINE_TORQUE_POINT_5);

    val wire: String get() = name.lowercase(Locale.ROOT)
    val unit: String get() = when (metric.unit) {
        "°C" -> "Cel"
        "N·m" -> "N.m"
        "lambda", "count" -> "1"
        else -> metric.unit
    }
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
        require(if (status == ApiSampleStatus.OK) value?.let(metric.metric::isValueInRange) == true else value == null)
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
