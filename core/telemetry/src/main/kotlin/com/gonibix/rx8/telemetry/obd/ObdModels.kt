package com.gonibix.rx8.telemetry.obd

import com.gonibix.rx8.telemetry.TelemetryMetric
import java.util.Locale

/** The six generic Mode 01 candidates. Capability discovery decides which can actually be queried. */
enum class ObdPid(val pid: Int, val metric: TelemetryMetric, val dataBytes: Int) {
    ENGINE_RPM(0x0C, TelemetryMetric.ENGINE_RPM, 2),
    SPEED(0x0D, TelemetryMetric.SPEED, 1),
    COOLANT_TEMPERATURE(0x05, TelemetryMetric.COOLANT_TEMPERATURE, 1),
    INTAKE_TEMPERATURE(0x0F, TelemetryMetric.INTAKE_TEMPERATURE, 1),
    THROTTLE_POSITION(0x11, TelemetryMetric.THROTTLE_POSITION, 1),
    ENGINE_LOAD(0x04, TelemetryMetric.ENGINE_LOAD, 1);

    val command: String get() = "01" + pid.toString(16).padStart(2, '0').uppercase(Locale.ROOT)
    val label: String get() = metric.label
    val unit: String get() = metric.unit

    // ELM327 datasheet, Talking to the Vehicle (RPM/temperature), and CSS Electronics' PID table:
    // https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf
    // https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979
    fun decode(data: List<Int>): Double {
        if (data.size != dataBytes || data.any { it !in 0..255 }) {
            throw ObdParseException(ObdFailure.MALFORMED, "Invalid data length or byte for $command.")
        }
        return when (this) {
            ENGINE_RPM -> (data[0] * 256 + data[1]) / 4.0
            SPEED -> data[0].toDouble()
            COOLANT_TEMPERATURE, INTAKE_TEMPERATURE -> data[0] - 40.0
            THROTTLE_POSITION, ENGINE_LOAD -> data[0] * 100.0 / 255.0
        }
    }
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