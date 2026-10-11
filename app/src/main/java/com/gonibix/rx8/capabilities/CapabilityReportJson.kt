package com.gonibix.rx8.capabilities

import java.time.Instant
import java.util.Locale

/** Only these fields may leave the temporary report. No source snapshot is serialized. */
object CapabilityReportJson {
    const val MAX_BYTES: Int = 512 * 1024

    fun encode(report: CapabilityReport): String {
        val json = encodeValue(linkedMapOf(
            "schemaVersion" to 1,
            "kind" to "capability_snapshot",
            "dataOrigin" to "real_obd",
            "snapshotSemantics" to "latest_display_state",
            "reportId" to report.reportId,
            "capturedAtUtc" to Instant.ofEpochMilli(report.capturedAtEpochMillis).toString(),
            "capturedAtEpochMillis" to report.capturedAtEpochMillis,
            "appVersion" to report.appVersion,
            "androidApiLevel" to report.androidApiLevel,
            "phase" to report.phase.name,
            "protocolLabel" to report.protocolLabel,
            "elapsedMillis" to report.elapsedMillis,
            "effectiveResponsesPerSecond" to report.effectiveResponsesPerSecond,
            "ecus" to report.ecus.map { ecu -> linkedMapOf(
                "address" to ecu.address,
                "reportedPids" to ecu.advertisedPids.map(::hexPid),
                "implementedMetrics" to ecu.metrics.map { metric -> linkedMapOf(
                    "metric" to metric.metric.name,
                    "pid" to hexPid(metric.metric.pid),
                    "unit" to metric.metric.unit,
                    "advertised" to metric.advertised,
                    "reading" to metric.reading?.let { reading -> linkedMapOf(
                        "value" to reading.value, "status" to reading.status.name, "ageMillis" to reading.ageMillis,
                    ) },
                ) },
            ) },
        ))
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "The capability report exceeds its size limit." }
        return json
    }

    private fun hexPid(pid: Int): String = String.format(Locale.ROOT, "%02X", pid)

    private fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { character -> when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character.code < 0x20 || character.isSurrogate() -> append(String.format(Locale.ROOT, "\\u%04x", character.code))
                else -> append(character)
            } }
            append('"')
        }
        is Int, is Long, is Boolean -> value.toString()
        is Double -> { require(value.isFinite()); value.toString() }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { entry ->
            require(entry.key is String)
            encodeValue(entry.key) + ":" + encodeValue(entry.value)
        }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::encodeValue)
        else -> error("Unsupported capability report field.")
    }
}
