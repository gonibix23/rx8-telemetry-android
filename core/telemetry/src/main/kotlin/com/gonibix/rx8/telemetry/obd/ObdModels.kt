package com.gonibix.rx8.telemetry.obd

import java.util.Locale

/** One request may produce several scalar channels. Discovery, not this catalog, proves support. */
enum class ObdPid(val pid: Int, val dataBytes: Int, val minimumDataBytes: Int = dataBytes, val pollingGroup: ObdPollingGroup = ObdPollingGroup.NORMAL) {
    ENGINE_RPM(0x0C, 2, 2, ObdPollingGroup.FAST),
    SPEED(0x0D, 1, 1, ObdPollingGroup.FAST),
    COOLANT_TEMPERATURE(0x05, 1, 1, ObdPollingGroup.PRIMARY),
    INTAKE_TEMPERATURE(0x0F, 1, 1, ObdPollingGroup.PRIMARY),
    THROTTLE_POSITION(0x11, 1, 1, ObdPollingGroup.PRIMARY),
    ENGINE_LOAD(0x04, 1, 1, ObdPollingGroup.PRIMARY),
    SHORT_TERM_FUEL_TRIM_BANK_1(0x06, 2, 1, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_1(0x07, 2, 1, ObdPollingGroup.NORMAL),
    SHORT_TERM_FUEL_TRIM_BANK_2(0x08, 2, 1, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_2(0x09, 2, 1, ObdPollingGroup.NORMAL),
    FUEL_PRESSURE(0x0A, 1, 1, ObdPollingGroup.NORMAL),
    MANIFOLD_PRESSURE(0x0B, 1, 1, ObdPollingGroup.NORMAL),
    IGNITION_TIMING(0x0E, 1, 1, ObdPollingGroup.NORMAL),
    MASS_AIR_FLOW(0x10, 2, 2, ObdPollingGroup.NORMAL),
    ENGINE_RUN_TIME(0x1F, 2, 2, ObdPollingGroup.SLOW),
    DISTANCE_WITH_MIL(0x21, 2, 2, ObdPollingGroup.SLOW),
    FUEL_RAIL_PRESSURE_RELATIVE(0x22, 2, 2, ObdPollingGroup.NORMAL),
    FUEL_RAIL_PRESSURE_GAUGE(0x23, 2, 2, ObdPollingGroup.NORMAL),
    COMMANDED_EGR(0x2C, 1, 1, ObdPollingGroup.NORMAL),
    EGR_ERROR(0x2D, 1, 1, ObdPollingGroup.NORMAL),
    COMMANDED_EVAP_PURGE(0x2E, 1, 1, ObdPollingGroup.NORMAL),
    FUEL_LEVEL(0x2F, 1, 1, ObdPollingGroup.SLOW),
    WARM_UPS_SINCE_CLEAR(0x30, 1, 1, ObdPollingGroup.SLOW),
    DISTANCE_SINCE_CLEAR(0x31, 2, 2, ObdPollingGroup.SLOW),
    EVAP_VAPOR_PRESSURE(0x32, 2, 2, ObdPollingGroup.NORMAL),
    BAROMETRIC_PRESSURE(0x33, 1, 1, ObdPollingGroup.SLOW),
    CONTROL_MODULE_VOLTAGE(0x42, 2, 2, ObdPollingGroup.NORMAL),
    ABSOLUTE_ENGINE_LOAD(0x43, 2, 2, ObdPollingGroup.NORMAL),
    COMMANDED_EQUIVALENCE_RATIO(0x44, 2, 2, ObdPollingGroup.NORMAL),
    RELATIVE_THROTTLE_POSITION(0x45, 1, 1, ObdPollingGroup.NORMAL),
    AMBIENT_TEMPERATURE(0x46, 1, 1, ObdPollingGroup.SLOW),
    THROTTLE_POSITION_B(0x47, 1, 1, ObdPollingGroup.NORMAL),
    THROTTLE_POSITION_C(0x48, 1, 1, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_D(0x49, 1, 1, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_E(0x4A, 1, 1, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_F(0x4B, 1, 1, ObdPollingGroup.NORMAL),
    COMMANDED_THROTTLE(0x4C, 1, 1, ObdPollingGroup.NORMAL),
    TIME_WITH_MIL(0x4D, 2, 2, ObdPollingGroup.SLOW),
    TIME_SINCE_CLEAR(0x4E, 2, 2, ObdPollingGroup.SLOW),
    ALCOHOL_PERCENTAGE(0x52, 1, 1, ObdPollingGroup.SLOW),
    EVAP_VAPOR_PRESSURE_ABSOLUTE(0x53, 2, 2, ObdPollingGroup.NORMAL),
    EVAP_VAPOR_PRESSURE_WIDE(0x54, 2, 2, ObdPollingGroup.NORMAL),
    FUEL_RAIL_PRESSURE_ABSOLUTE(0x59, 2, 2, ObdPollingGroup.NORMAL),
    RELATIVE_ACCELERATOR_POSITION(0x5A, 1, 1, ObdPollingGroup.NORMAL),
    HYBRID_BATTERY_REMAINING(0x5B, 1, 1, ObdPollingGroup.SLOW),
    ENGINE_OIL_TEMPERATURE(0x5C, 1, 1, ObdPollingGroup.NORMAL),
    FUEL_INJECTION_TIMING(0x5D, 2, 2, ObdPollingGroup.NORMAL),
    ENGINE_FUEL_RATE(0x5E, 2, 2, ObdPollingGroup.NORMAL),
    DRIVER_DEMAND_TORQUE(0x61, 1, 1, ObdPollingGroup.NORMAL),
    ACTUAL_ENGINE_TORQUE(0x62, 1, 1, ObdPollingGroup.NORMAL),
    ENGINE_REFERENCE_TORQUE(0x63, 2, 2, ObdPollingGroup.SLOW),
    OXYGEN_SENSOR_1(0x14, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_1(0x24, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_1(0x34, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_2(0x15, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_2(0x25, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_2(0x35, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_3(0x16, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_3(0x26, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_3(0x36, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_4(0x17, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_4(0x27, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_4(0x37, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_5(0x18, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_5(0x28, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_5(0x38, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_6(0x19, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_6(0x29, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_6(0x39, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_7(0x1A, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_7(0x2A, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_7(0x3A, 4, 4, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_8(0x1B, 2, 2, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_8(0x2B, 4, 4, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_8(0x3B, 4, 4, ObdPollingGroup.NORMAL),
    CATALYST_TEMPERATURE_B1_S1(0x3C, 2, 2, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B2_S1(0x3D, 2, 2, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B1_S2(0x3E, 2, 2, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B2_S2(0x3F, 2, 2, ObdPollingGroup.SLOW),
    SECONDARY_SHORT_TERM_TRIM_BANK_1(0x55, 2, 1, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_1(0x56, 2, 1, ObdPollingGroup.NORMAL),
    SECONDARY_SHORT_TERM_TRIM_BANK_2(0x57, 2, 1, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_2(0x58, 2, 1, ObdPollingGroup.NORMAL),
    ENGINE_TORQUE_MAP(0x64, 5, 5, ObdPollingGroup.SLOW),
    SCALING_CONFIGURATION(0x4F, 4, 4, ObdPollingGroup.METADATA),
    MAF_SCALING_CONFIGURATION(0x50, 4, 4, ObdPollingGroup.METADATA);

    val command: String get() = "01" + pid.toString(16).padStart(2, '0').uppercase(Locale.ROOT)
    val fields: List<ObdField> get() = ObdField.entries.filter { it.pid == pid }
    val label: String get() = fields.firstOrNull()?.label ?: "Decoder scaling configuration"
    val unit: String get() = fields.firstOrNull()?.unit.orEmpty()

    fun acceptsLength(length: Int): Boolean = length in minimumDataBytes..dataBytes

    fun decodeValues(data: List<Int>, context: ObdDecodeContext = ObdDecodeContext()): Map<ObdField, ObdDecodedValue> {
        if (!acceptsLength(data.size) || data.any { it !in 0..255 }) {
            throw ObdParseException(ObdFailure.MALFORMED, "Invalid Mode 01 data length or byte.")
        }
        return fields.associateWith { it.decode(data, context) }
    }

    /** Compatibility helper for single-channel callers. Multi-channel acquisition uses decodeValues. */
    fun decode(data: List<Int>): Double = decodeValues(data).values.firstOrNull()?.value
        ?: throw ObdParseException(ObdFailure.MALFORMED, "No scalar value is available.")
}

/** Actual protocols reported by ATDPN; Auto alone and user-defined/J1939 layouts are not supported. */
enum class ObdProtocol(val code: String, val description: String, val canIdBits: Int? = null) {
    J1850_PWM("1", "SAE J1850 PWM (41.6 kbaud)"),
    J1850_VPW("2", "SAE J1850 VPW (10.4 kbaud)"),
    ISO_9141_2("3", "ISO 9141-2 (5 baud init)"),
    KWP_SLOW("4", "ISO 14230-4 KWP (5 baud init)"),
    KWP_FAST("5", "ISO 14230-4 KWP (fast init)"),
    CAN_11_500("6", "ISO 15765-4 CAN (11 bit, 500 kbaud)", 11),
    CAN_29_500("7", "ISO 15765-4 CAN (29 bit, 500 kbaud)", 29),
    CAN_11_250("8", "ISO 15765-4 CAN (11 bit, 250 kbaud)", 11),
    CAN_29_250("9", "ISO 15765-4 CAN (29 bit, 250 kbaud)", 29),
}

/** ecu is the CAN identifier or legacy source address; data excludes framing and checksum bytes. */
data class ObdResponse(val ecu: String, val pid: Int, val data: List<Int>)

enum class ElmError {
    NO_DATA, UNABLE_TO_CONNECT, BUS_INIT_ERROR, BUS_ERROR, CAN_ERROR, DATA_ERROR,
    BUFFER_FULL, STOPPED, UNKNOWN_COMMAND, LOW_VOLTAGE, OTHER_ERROR,
}

data class ElmReply(
    val dataLines: List<String>,
    val errors: List<ElmError>,
    val searching: Boolean,
    val isOk: Boolean,
)

enum class ObdFailure { NO_DATA, ADAPTER_ERROR, MALFORMED, UNSUPPORTED_PROTOCOL, NEGATIVE_RESPONSE }

class ObdParseException(
    val reason: ObdFailure,
    message: String,
    val ecu: String? = null,
) : IllegalArgumentException(message)
