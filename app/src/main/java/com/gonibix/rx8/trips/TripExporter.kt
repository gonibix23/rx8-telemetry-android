package com.gonibix.rx8.trips

import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Versioned, local exports. The caller owns flushing/closing the destination and reporting success. */
object TripExporter {
    suspend fun writeJson(repository: TripRepository, session: TripSession, writer: Writer): Unit = withContext(Dispatchers.IO) {
        requireFinishedTrip(session)
        currentCoroutineContext().ensureActive()
        writer.write("{\"schemaVersion\":1,\"trip\":{")
        writer.jsonField("id", session.id)
        writer.jsonField("vehicleId", session.vehicleId)
        writer.jsonField("vehicleAlias", session.vehicleAlias)
        writer.jsonField("startedAtUtc", utc(session.startedAtEpochMillis))
        writer.jsonNumber("startedAtEpochMillis", session.startedAtEpochMillis.toString())
        writer.jsonField("endedAtUtc", session.endedAtEpochMillis?.let(::utc))
        writer.jsonNumber("endedAtEpochMillis", session.endedAtEpochMillis?.toString())
        writer.jsonNumber("durationMillis", session.durationMillis.toString())
        writer.jsonField("status", session.status.name)
        writer.jsonField("endReason", session.endReason)
        writer.jsonField("protocolLabel", session.protocolLabel)
        writer.jsonField("origin", session.origin, trailingComma = false)
        writer.write("},\"samples\":[")
        var first = true
        forEachTripSample(repository, session.id) { sample ->
            val number = sample.exportNumber()
            if (!first) writer.write(",")
            first = false
            writer.write("{")
            writer.jsonField("id", sample.id)
            writer.jsonField("tripId", sample.tripId)
            writer.jsonNumber("sequence", sample.sequence.toString())
            writer.jsonField("observedAtUtc", utc(sample.observedAtEpochMillis))
            writer.jsonNumber("observedAtEpochMillis", sample.observedAtEpochMillis.toString())
            writer.jsonNumber("elapsedMillis", sample.elapsedMillis.toString())
            writer.jsonField("metric", sample.metric.name)
            writer.jsonNumber("value", number)
            writer.jsonField("unit", sample.unit)
            writer.jsonField("ecuId", sample.ecuId)
            writer.jsonField("quality", sample.quality.name, trailingComma = false)
            writer.write("}")
        }
        currentCoroutineContext().ensureActive()
        writer.write("]}\n")
    }

    suspend fun writeCsv(repository: TripRepository, session: TripSession, writer: Writer): Unit = withContext(Dispatchers.IO) {
        requireFinishedTrip(session)
        currentCoroutineContext().ensureActive()
        writer.write(CSV_HEADER + "\r\n")
        val metadata = listOf(
            csvText(session.id), csvText(session.vehicleId), csvText(session.vehicleAlias),
            csvText(utc(session.startedAtEpochMillis)), session.startedAtEpochMillis.toString(),
            csvText(session.endedAtEpochMillis?.let(::utc)), session.endedAtEpochMillis?.toString().orEmpty(),
            session.durationMillis.toString(), csvText(session.status.name), csvText(session.endReason),
            csvText(session.protocolLabel), csvText(session.origin),
        )
        var hasSamples = false
        forEachTripSample(repository, session.id) { sample ->
            val values = listOf(
                csvText(sample.id), sample.sequence.toString(), csvText(utc(sample.observedAtEpochMillis)),
                sample.observedAtEpochMillis.toString(), sample.elapsedMillis.toString(), csvText(sample.metric.name),
                sample.exportNumber().orEmpty(), csvText(sample.unit), csvText(sample.ecuId), csvText(sample.quality.name),
            )
            writer.write((listOf("1", csvText("SAMPLE")) + metadata + values).joinToString(",") + "\r\n")
            hasSamples = true
        }
        currentCoroutineContext().ensureActive()
        if (!hasSamples) writer.write(
            (listOf("1", csvText("METADATA")) + metadata + List(10) { "" }).joinToString(",") + "\r\n",
        )
    }

    private const val CSV_HEADER = "schema_version,record_type,trip_id,vehicle_id,vehicle_alias," +
        "trip_started_at_utc,trip_started_at_epoch_ms,trip_ended_at_utc,trip_ended_at_epoch_ms," +
        "trip_duration_ms,trip_status,trip_end_reason,protocol_label,origin,sample_id,sequence," +
        "observed_at_utc,observed_at_epoch_ms,elapsed_ms,metric,value,unit,ecu_id,quality"
}

private fun TripSample.exportNumber(): String? {
    if (value != null && !value.isFinite()) throw IOException("A saved sample has a non-finite numeric value.")
    return value?.toString()
}

private fun utc(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

private fun Writer.jsonField(name: String, value: String?, trailingComma: Boolean = true) {
    jsonString(name)
    write(":")
    jsonString(value)
    if (trailingComma) write(",")
}

private fun Writer.jsonNumber(name: String, value: String?) {
    jsonString(name)
    write(":")
    write(value ?: "null")
    write(",")
}

private fun Writer.jsonString(value: String?) {
    if (value == null) { write("null"); return }
    write("\"")
    value.forEach { character ->
        when (character) {
            '"' -> write("\\\"")
            '\\' -> write("\\\\")
            '\b' -> write("\\b")
            '\u000C' -> write("\\f")
            '\n' -> write("\\n")
            '\r' -> write("\\r")
            '\t' -> write("\\t")
            else -> if (character.code < 0x20 || character.isSurrogate()) {
                write(String.format(Locale.ROOT, "\\u%04x", character.code))
            } else write(character.code)
        }
    }
    write("\"")
}

/** Quoting alone does not stop spreadsheets interpreting a textual cell as a formula. */
private fun csvText(value: String?): String {
    if (value == null) return ""
    val firstMeaningful = value.firstOrNull { !it.isWhitespace() && !it.isISOControl() && it != '\uFEFF' }
    val dangerous = firstMeaningful in setOf('=', '+', '-', '@') || value.firstOrNull()?.isISOControl() == true
    val safe = if (dangerous) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}
