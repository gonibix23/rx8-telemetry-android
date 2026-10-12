package com.gonibix.rx8.telemetry.obd

/**
 * Stable scalar channels for read-only SAE J1979 Mode 01.
 * Formulas: SAE J1979-DA OCT2011, Appendix B, tables B5-B80.
 * https://f30.bimmerpost.com/forums/attachment.php?attachmentid=1800282&d=1523076762
 * O2 slots deliberately do not guess bank topology from a PID alone.
 * Metadata PID4F/50 scales are kept per ECU; missing advertised metadata is never defaulted.
 */
enum class ObdField(
    val pid: Int,
    val label: String,
    private val scale: ObdScale,
    private val offset: Int = 0,
    val pollingGroup: ObdPollingGroup = ObdPollingGroup.NORMAL,
) {
    ENGINE_RPM(0x0C, "Engine speed", ObdScale.RPM, 0, ObdPollingGroup.FAST),
    SPEED(0x0D, "Vehicle speed", ObdScale.SPEED, 0, ObdPollingGroup.FAST),
    COOLANT_TEMPERATURE(0x05, "Coolant temperature", ObdScale.TEMPERATURE, 0, ObdPollingGroup.PRIMARY),
    INTAKE_TEMPERATURE(0x0F, "Intake air temperature", ObdScale.TEMPERATURE, 0, ObdPollingGroup.PRIMARY),
    THROTTLE_POSITION(0x11, "Throttle position", ObdScale.PERCENT, 0, ObdPollingGroup.PRIMARY),
    ENGINE_LOAD(0x04, "Engine load", ObdScale.PERCENT, 0, ObdPollingGroup.PRIMARY),
    SHORT_TERM_FUEL_TRIM_BANK_1(0x06, "Short-term fuel trim, bank 1", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SHORT_TERM_FUEL_TRIM_BANK_3(0x06, "Short-term fuel trim, bank 3", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_1(0x07, "Long-term fuel trim, bank 1", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_3(0x07, "Long-term fuel trim, bank 3", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    SHORT_TERM_FUEL_TRIM_BANK_2(0x08, "Short-term fuel trim, bank 2", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SHORT_TERM_FUEL_TRIM_BANK_4(0x08, "Short-term fuel trim, bank 4", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_2(0x09, "Long-term fuel trim, bank 2", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    LONG_TERM_FUEL_TRIM_BANK_4(0x09, "Long-term fuel trim, bank 4", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    FUEL_PRESSURE(0x0A, "Fuel pressure (gauge)", ObdScale.FUEL_PRESSURE, 0, ObdPollingGroup.NORMAL),
    MANIFOLD_PRESSURE(0x0B, "Intake manifold pressure (absolute)", ObdScale.MAP, 0, ObdPollingGroup.NORMAL),
    IGNITION_TIMING(0x0E, "Ignition timing advance", ObdScale.TIMING, 0, ObdPollingGroup.NORMAL),
    MASS_AIR_FLOW(0x10, "Mass air flow", ObdScale.MAF, 0, ObdPollingGroup.NORMAL),
    ENGINE_RUN_TIME(0x1F, "Run time since engine start", ObdScale.SECONDS, 0, ObdPollingGroup.SLOW),
    DISTANCE_WITH_MIL(0x21, "Distance with warning lamp on", ObdScale.DISTANCE, 0, ObdPollingGroup.SLOW),
    FUEL_RAIL_PRESSURE_RELATIVE(0x22, "Fuel rail pressure (relative to manifold)", ObdScale.RAIL_RELATIVE, 0, ObdPollingGroup.NORMAL),
    FUEL_RAIL_PRESSURE_GAUGE(0x23, "Fuel rail pressure (gauge)", ObdScale.RAIL, 0, ObdPollingGroup.NORMAL),
    COMMANDED_EGR(0x2C, "Commanded EGR", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    EGR_ERROR(0x2D, "EGR error", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    COMMANDED_EVAP_PURGE(0x2E, "Commanded evaporative purge", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    FUEL_LEVEL(0x2F, "Fuel tank level", ObdScale.PERCENT, 0, ObdPollingGroup.SLOW),
    WARM_UPS_SINCE_CLEAR(0x30, "Warm-ups since codes cleared", ObdScale.COUNT, 0, ObdPollingGroup.SLOW),
    DISTANCE_SINCE_CLEAR(0x31, "Distance since codes cleared", ObdScale.DISTANCE, 0, ObdPollingGroup.SLOW),
    EVAP_VAPOR_PRESSURE(0x32, "Evaporative vapor pressure", ObdScale.EVAP_QUARTER, 0, ObdPollingGroup.NORMAL),
    BAROMETRIC_PRESSURE(0x33, "Barometric pressure", ObdScale.PRESSURE, 0, ObdPollingGroup.SLOW),
    CONTROL_MODULE_VOLTAGE(0x42, "Control module voltage", ObdScale.VOLTAGE, 0, ObdPollingGroup.NORMAL),
    ABSOLUTE_ENGINE_LOAD(0x43, "Absolute engine load", ObdScale.ABSOLUTE_LOAD, 0, ObdPollingGroup.NORMAL),
    COMMANDED_EQUIVALENCE_RATIO(0x44, "Commanded air-fuel ratio (lambda)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    RELATIVE_THROTTLE_POSITION(0x45, "Relative throttle position", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    AMBIENT_TEMPERATURE(0x46, "Ambient air temperature", ObdScale.TEMPERATURE, 0, ObdPollingGroup.SLOW),
    THROTTLE_POSITION_B(0x47, "Throttle position B", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    THROTTLE_POSITION_C(0x48, "Throttle position C", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_D(0x49, "Accelerator pedal position D", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_E(0x4A, "Accelerator pedal position E", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    ACCELERATOR_POSITION_F(0x4B, "Accelerator pedal position F", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    COMMANDED_THROTTLE(0x4C, "Commanded throttle actuator", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    TIME_WITH_MIL(0x4D, "Run time with warning lamp on", ObdScale.MINUTES, 0, ObdPollingGroup.SLOW),
    TIME_SINCE_CLEAR(0x4E, "Run time since codes cleared", ObdScale.MINUTES, 0, ObdPollingGroup.SLOW),
    ALCOHOL_PERCENTAGE(0x52, "Fuel alcohol content", ObdScale.PERCENT, 0, ObdPollingGroup.SLOW),
    EVAP_VAPOR_PRESSURE_ABSOLUTE(0x53, "Evaporative vapor pressure (absolute)", ObdScale.EVAP_ABSOLUTE, 0, ObdPollingGroup.NORMAL),
    EVAP_VAPOR_PRESSURE_WIDE(0x54, "Evaporative vapor pressure (wide range)", ObdScale.EVAP_SIGNED, 0, ObdPollingGroup.NORMAL),
    FUEL_RAIL_PRESSURE_ABSOLUTE(0x59, "Fuel rail pressure (absolute)", ObdScale.RAIL, 0, ObdPollingGroup.NORMAL),
    RELATIVE_ACCELERATOR_POSITION(0x5A, "Relative accelerator pedal position", ObdScale.PERCENT, 0, ObdPollingGroup.NORMAL),
    HYBRID_BATTERY_REMAINING(0x5B, "Hybrid battery remaining life", ObdScale.PERCENT, 0, ObdPollingGroup.SLOW),
    ENGINE_OIL_TEMPERATURE(0x5C, "Engine oil temperature", ObdScale.TEMPERATURE, 0, ObdPollingGroup.NORMAL),
    FUEL_INJECTION_TIMING(0x5D, "Fuel injection timing", ObdScale.INJECTION_TIMING, 0, ObdPollingGroup.NORMAL),
    ENGINE_FUEL_RATE(0x5E, "Engine fuel rate", ObdScale.FUEL_RATE, 0, ObdPollingGroup.NORMAL),
    DRIVER_DEMAND_TORQUE(0x61, "Driver demand engine torque", ObdScale.TORQUE_PERCENT, 0, ObdPollingGroup.NORMAL),
    ACTUAL_ENGINE_TORQUE(0x62, "Actual indicated engine torque", ObdScale.TORQUE_PERCENT, 0, ObdPollingGroup.NORMAL),
    ENGINE_REFERENCE_TORQUE(0x63, "Engine reference torque", ObdScale.TORQUE_REFERENCE, 0, ObdPollingGroup.SLOW),
    OXYGEN_SENSOR_1_VOLTAGE(0x14, "O2 slot 1 voltage (PID 14)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_1_FUEL_TRIM(0x14, "O2 slot 1 fuel trim (PID 14)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_1_RATIO(0x24, "O2 slot 1 lambda (PID 24)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_1_VOLTAGE(0x24, "O2 slot 1 voltage (PID 24)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_1_RATIO(0x34, "O2 slot 1 lambda (PID 34)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_1_CURRENT(0x34, "O2 slot 1 current (PID 34)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_2_VOLTAGE(0x15, "O2 slot 2 voltage (PID 15)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_2_FUEL_TRIM(0x15, "O2 slot 2 fuel trim (PID 15)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_2_RATIO(0x25, "O2 slot 2 lambda (PID 25)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_2_VOLTAGE(0x25, "O2 slot 2 voltage (PID 25)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_2_RATIO(0x35, "O2 slot 2 lambda (PID 35)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_2_CURRENT(0x35, "O2 slot 2 current (PID 35)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_3_VOLTAGE(0x16, "O2 slot 3 voltage (PID 16)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_3_FUEL_TRIM(0x16, "O2 slot 3 fuel trim (PID 16)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_3_RATIO(0x26, "O2 slot 3 lambda (PID 26)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_3_VOLTAGE(0x26, "O2 slot 3 voltage (PID 26)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_3_RATIO(0x36, "O2 slot 3 lambda (PID 36)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_3_CURRENT(0x36, "O2 slot 3 current (PID 36)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_4_VOLTAGE(0x17, "O2 slot 4 voltage (PID 17)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_4_FUEL_TRIM(0x17, "O2 slot 4 fuel trim (PID 17)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_4_RATIO(0x27, "O2 slot 4 lambda (PID 27)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_4_VOLTAGE(0x27, "O2 slot 4 voltage (PID 27)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_4_RATIO(0x37, "O2 slot 4 lambda (PID 37)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_4_CURRENT(0x37, "O2 slot 4 current (PID 37)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_5_VOLTAGE(0x18, "O2 slot 5 voltage (PID 18)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_5_FUEL_TRIM(0x18, "O2 slot 5 fuel trim (PID 18)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_5_RATIO(0x28, "O2 slot 5 lambda (PID 28)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_5_VOLTAGE(0x28, "O2 slot 5 voltage (PID 28)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_5_RATIO(0x38, "O2 slot 5 lambda (PID 38)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_5_CURRENT(0x38, "O2 slot 5 current (PID 38)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_6_VOLTAGE(0x19, "O2 slot 6 voltage (PID 19)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_6_FUEL_TRIM(0x19, "O2 slot 6 fuel trim (PID 19)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_6_RATIO(0x29, "O2 slot 6 lambda (PID 29)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_6_VOLTAGE(0x29, "O2 slot 6 voltage (PID 29)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_6_RATIO(0x39, "O2 slot 6 lambda (PID 39)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_6_CURRENT(0x39, "O2 slot 6 current (PID 39)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_7_VOLTAGE(0x1A, "O2 slot 7 voltage (PID 1A)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_7_FUEL_TRIM(0x1A, "O2 slot 7 fuel trim (PID 1A)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_7_RATIO(0x2A, "O2 slot 7 lambda (PID 2A)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_7_VOLTAGE(0x2A, "O2 slot 7 voltage (PID 2A)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_7_RATIO(0x3A, "O2 slot 7 lambda (PID 3A)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_7_CURRENT(0x3A, "O2 slot 7 current (PID 3A)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_8_VOLTAGE(0x1B, "O2 slot 8 voltage (PID 1B)", ObdScale.O2_VOLTAGE, 0, ObdPollingGroup.NORMAL),
    OXYGEN_SENSOR_8_FUEL_TRIM(0x1B, "O2 slot 8 fuel trim (PID 1B)", ObdScale.O2_TRIM, 1, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_8_RATIO(0x2B, "O2 slot 8 lambda (PID 2B)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    WIDE_OXYGEN_SENSOR_8_VOLTAGE(0x2B, "O2 slot 8 voltage (PID 2B)", ObdScale.WIDE_VOLTAGE, 2, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_8_RATIO(0x3B, "O2 slot 8 lambda (PID 3B)", ObdScale.RATIO, 0, ObdPollingGroup.NORMAL),
    CURRENT_OXYGEN_SENSOR_8_CURRENT(0x3B, "O2 slot 8 current (PID 3B)", ObdScale.O2_CURRENT, 2, ObdPollingGroup.NORMAL),
    CATALYST_TEMPERATURE_B1_S1(0x3C, "Catalyst temperature, bank 1 sensor 1", ObdScale.CATALYST_TEMPERATURE, 0, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B2_S1(0x3D, "Catalyst temperature, bank 2 sensor 1", ObdScale.CATALYST_TEMPERATURE, 0, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B1_S2(0x3E, "Catalyst temperature, bank 1 sensor 2", ObdScale.CATALYST_TEMPERATURE, 0, ObdPollingGroup.SLOW),
    CATALYST_TEMPERATURE_B2_S2(0x3F, "Catalyst temperature, bank 2 sensor 2", ObdScale.CATALYST_TEMPERATURE, 0, ObdPollingGroup.SLOW),
    SECONDARY_SHORT_TERM_TRIM_BANK_1(0x55, "Secondary short-term fuel trim, bank 1", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SECONDARY_SHORT_TERM_TRIM_BANK_3(0x55, "Secondary short-term fuel trim, bank 3", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_1(0x56, "Secondary long-term fuel trim, bank 1", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_3(0x56, "Secondary long-term fuel trim, bank 3", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    SECONDARY_SHORT_TERM_TRIM_BANK_2(0x57, "Secondary short-term fuel trim, bank 2", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SECONDARY_SHORT_TERM_TRIM_BANK_4(0x57, "Secondary short-term fuel trim, bank 4", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_2(0x58, "Secondary long-term fuel trim, bank 2", ObdScale.TRIM, 0, ObdPollingGroup.NORMAL),
    SECONDARY_LONG_TERM_TRIM_BANK_4(0x58, "Secondary long-term fuel trim, bank 4", ObdScale.OPTIONAL_TRIM, 1, ObdPollingGroup.NORMAL),
    ENGINE_TORQUE_POINT_1(0x64, "Available engine torque, idle", ObdScale.TORQUE_PERCENT, 0, ObdPollingGroup.SLOW),
    ENGINE_TORQUE_POINT_2(0x64, "Available engine torque, point 2", ObdScale.TORQUE_PERCENT, 1, ObdPollingGroup.SLOW),
    ENGINE_TORQUE_POINT_3(0x64, "Available engine torque, point 3", ObdScale.TORQUE_PERCENT, 2, ObdPollingGroup.SLOW),
    ENGINE_TORQUE_POINT_4(0x64, "Available engine torque, point 4", ObdScale.TORQUE_PERCENT, 3, ObdPollingGroup.SLOW),
    ENGINE_TORQUE_POINT_5(0x64, "Available engine torque, point 5", ObdScale.TORQUE_PERCENT, 4, ObdPollingGroup.SLOW);

    val unit: String get() = scale.unit
    val minimumValue: Double get() = scale.minimum
    val maximumValue: Double get() = scale.maximum
    val displayDecimals: Int get() = scale.decimals
    val freshnessMillis: Long get() = when (pollingGroup) {
        ObdPollingGroup.FAST -> 5_000L
        ObdPollingGroup.PRIMARY -> 15_000L
        ObdPollingGroup.NORMAL -> 120_000L
        ObdPollingGroup.SLOW, ObdPollingGroup.METADATA -> 180_000L
    }
    val parameter: ObdPid get() = ObdPid.entries.single { it.pid == pid }
    fun isValueInRange(value: Double): Boolean = value.isFinite() && value in minimumValue..maximumValue

    internal fun decode(data: List<Int>, context: ObdDecodeContext): ObdDecodedValue {
        if (offset >= data.size) return ObdDecodedValue(null, available = false)
        val a = data[offset]
        fun word(): Int = a * 256 + data[offset + 1]
        fun signedWord(): Int = word().let { if (it >= 32768) it - 65536 else it }
        val value = when (scale) {
            ObdScale.RPM -> word() / 4.0
            ObdScale.SPEED, ObdScale.PRESSURE, ObdScale.COUNT -> a.toDouble()
            ObdScale.TEMPERATURE -> a - 40.0
            ObdScale.PERCENT -> a * 100.0 / 255.0
            ObdScale.TRIM, ObdScale.OPTIONAL_TRIM -> (a - 128) * 100.0 / 128.0
            ObdScale.FUEL_PRESSURE -> a * 3.0
            ObdScale.MAP -> context.maximumManifoldPressure?.let { a * if (it == 0) 1.0 else it * 10.0 / 255.0 }
            ObdScale.TIMING -> a / 2.0 - 64.0
            ObdScale.MAF -> context.maximumMassAirFlow?.let { word() * if (it == 0) 0.01 else it * 10.0 / 65535.0 }
            ObdScale.SECONDS, ObdScale.DISTANCE, ObdScale.MINUTES, ObdScale.TORQUE_REFERENCE -> word().toDouble()
            ObdScale.RAIL_RELATIVE -> word() * 0.079
            ObdScale.RAIL -> word() * 10.0
            ObdScale.EVAP_QUARTER -> signedWord() / 4.0
            ObdScale.VOLTAGE -> word() / 1000.0
            ObdScale.ABSOLUTE_LOAD -> word() * 100.0 / 255.0
            // The cited Mode01 tables specify 2/65535 and 8/65535 for the default ratio/voltage.
            ObdScale.RATIO -> context.maximumRatio?.let { word() * (if (it == 0) 2.0 else it.toDouble()) / 65535.0 }
            ObdScale.EVAP_ABSOLUTE -> word() / 200.0
            ObdScale.EVAP_SIGNED -> signedWord().toDouble()
            ObdScale.INJECTION_TIMING -> word() / 128.0 - 210.0
            ObdScale.FUEL_RATE -> word() / 20.0
            ObdScale.TORQUE_PERCENT -> a - 125.0
            ObdScale.O2_VOLTAGE -> a / 200.0
            ObdScale.O2_TRIM -> if (a == 255) null else (a - 128) * 100.0 / 128.0
            ObdScale.WIDE_VOLTAGE -> context.maximumOxygenVoltage?.let { word() * (if (it == 0) 8.0 else it.toDouble()) / 65535.0 }
            ObdScale.O2_CURRENT -> context.maximumOxygenCurrent?.let { (word() - 32768) * (if (it == 0) 128.0 else it.toDouble()) / 32768.0 }
            ObdScale.CATALYST_TEMPERATURE -> word() / 10.0 - 40.0
        }
        return ObdDecodedValue(value)
    }
}

enum class ObdPollingGroup { FAST, PRIMARY, NORMAL, SLOW, METADATA }

/** Null means advertised scaling metadata could not be read; zero selects the standard default. */
data class ObdDecodeContext(
    val maximumRatio: Int? = 0,
    val maximumOxygenVoltage: Int? = 0,
    val maximumOxygenCurrent: Int? = 0,
    val maximumManifoldPressure: Int? = 0,
    val maximumMassAirFlow: Int? = 0,
) {
    init { require(listOf(maximumRatio, maximumOxygenVoltage, maximumOxygenCurrent, maximumManifoldPressure, maximumMassAirFlow).all { it == null || it in 0..255 }) }
}

data class ObdDecodedValue(val value: Double?, val available: Boolean = true)

private enum class ObdScale(val unit: String, val minimum: Double, val maximum: Double, val decimals: Int) {
    RPM("rpm", 0.0, 16383.75, 0),
    SPEED("km/h", 0.0, 255.0, 0),
    TEMPERATURE("°C", -40.0, 215.0, 0),
    PERCENT("%", 0.0, 100.0, 1),
    TRIM("%", -100.0, 99.21875, 1),
    OPTIONAL_TRIM("%", -100.0, 99.21875, 1),
    FUEL_PRESSURE("kPa", 0.0, 765.0, 0),
    MAP("kPa", 0.0, 2550.0, 1),
    TIMING("deg", -64.0, 63.5, 1),
    MAF("g/s", 0.0, 2550.0, 2),
    SECONDS("s", 0.0, 65535.0, 0),
    DISTANCE("km", 0.0, 65535.0, 0),
    RAIL_RELATIVE("kPa", 0.0, 5177.265, 2),
    RAIL("kPa", 0.0, 655350.0, 0),
    COUNT("count", 0.0, 255.0, 0),
    EVAP_QUARTER("Pa", -8192.0, 8191.75, 2),
    PRESSURE("kPa", 0.0, 255.0, 0),
    VOLTAGE("V", 0.0, 65.535, 3),
    ABSOLUTE_LOAD("%", 0.0, 25700.0, 1),
    RATIO("lambda", 0.0, 255.0, 3),
    MINUTES("min", 0.0, 65535.0, 0),
    EVAP_ABSOLUTE("kPa", 0.0, 327.675, 3),
    EVAP_SIGNED("Pa", -32768.0, 32767.0, 0),
    INJECTION_TIMING("deg", -210.0, 301.9921875, 2),
    FUEL_RATE("L/h", 0.0, 3276.75, 2),
    TORQUE_PERCENT("%", -125.0, 130.0, 0),
    TORQUE_REFERENCE("N·m", 0.0, 65535.0, 0),
    O2_VOLTAGE("V", 0.0, 1.275, 3),
    O2_TRIM("%", -100.0, 99.21875, 1),
    WIDE_VOLTAGE("V", 0.0, 255.0, 3),
    O2_CURRENT("mA", -255.0, 254.99221801757812, 3),
    CATALYST_TEMPERATURE("°C", -40.0, 6513.5, 1);
}
