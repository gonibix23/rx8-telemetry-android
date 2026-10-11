package com.gonibix.rx8.sync

import java.util.Locale

/** Deterministic field order and number formatting freeze the exact UTF-8 request body. */
object ApiV1Json {
    fun vehicle(dto: VehicleDtoV1): String = encode(linkedMapOf(
        "schemaVersion" to 1, "vehicleId" to dto.vehicleId, "revision" to dto.revision,
        "vin" to dto.vin, "vinSource" to dto.vinSource?.wire, "alias" to dto.alias,
        "make" to dto.make, "model" to dto.model, "modelYear" to dto.modelYear, "market" to dto.market, "powerCv" to dto.powerCv,
    ))

    fun session(dto: SessionDtoV1): String = encode(linkedMapOf(
        "schemaVersion" to 1, "sessionId" to dto.sessionId, "vehicleId" to dto.vehicleId, "revision" to dto.revision,
        "startedAt" to dto.startedAt.toString(), "endedAt" to dto.endedAt?.toString(), "durationMs" to dto.durationMs,
        "status" to dto.status.wire, "appVersion" to dto.appVersion, "obdProtocol" to dto.obdProtocol, "dataOrigin" to dto.dataOrigin.wire,
    ))

    fun batch(dto: TelemetryBatchDtoV1): String = encode(linkedMapOf(
        "schemaVersion" to 1, "batchId" to dto.batchId, "vehicleId" to dto.vehicleId, "sessionId" to dto.sessionId,
        "samples" to dto.samples.map { sample -> linkedMapOf(
            "sampleId" to sample.sampleId, "sequence" to sample.sequence, "observedAt" to sample.observedAt.toString(),
            "elapsedMs" to sample.elapsedMs, "metric" to sample.metric.wire, "value" to sample.value,
            "unit" to sample.unit, "status" to sample.status.wire, "source" to sample.source.wire,
            "service" to sample.service, "pid" to sample.pid, "ecuAddress" to sample.ecuAddress,
        ) },
    ))

    private fun encode(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> {
                append('"')
                value.forEach { character -> when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20 || character.isSurrogate()) append(String.format(Locale.ROOT, "\\u%04x", character.code)) else append(character)
                } }
                append('"')
            }
            is Int, is Long -> append(value.toString())
            is Double -> { require(value.isFinite()); append(value.toString()) }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    require(entry.key is String); appendJson(entry.key); append(':'); appendJson(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item -> if (index > 0) append(','); appendJson(item) }
                append(']')
            }
            else -> error("Unsupported API JSON value.")
        }
    }
}
