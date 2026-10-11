package com.gonibix.rx8.trips

import com.gonibix.rx8.obd.ObdReadingStatus
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripExporterTest {
    private val exporters: List<suspend (TripRepository, TripSession, Writer) -> Unit> =
        listOf(TripExporter::writeJson, TripExporter::writeCsv)

    @Test fun jsonRoundTripsMetadataIdsEscapedTextUtcNumbersAndQuality() = runTest {
        val alias = "Synthetic \"RX-8\", line\nslash\\tab\tcontrol\u0001 snow \u2603 \uD83D\uDE97"
        val trip = analysisTrip().copy(vehicleAlias = alias, endReason = "STOP\r\nfixture")
        val samples = listOf(
            analysisSample(0, 1_000L, 0.0).copy(id = "00000000-0000-4000-8000-000000000003"),
            analysisSample(1, 2_000L, null, quality = ObdReadingStatus.TIMEOUT),
        )
        val writer = StringWriter()
        TripExporter.writeJson(AnalyticsExportRepository(samples), trip, writer)
        val root = JsonFixtureReader(writer.toString()).read() as Map<*, *>
        assertEquals(1L, root["schemaVersion"])
        val metadata = root["trip"] as Map<*, *>
        assertEquals(alias, metadata["vehicleAlias"])
        assertEquals(trip.id, metadata["id"])
        assertEquals(trip.vehicleId, metadata["vehicleId"])
        assertEquals("1970-01-01T00:00:00Z", metadata["startedAtUtc"])
        assertEquals("1970-01-01T00:00:10Z", metadata["endedAtUtc"])
        assertEquals("STOP\r\nfixture", metadata["endReason"])
        assertFalse(metadata.keys.any { it.toString().contains("vin", true) || it.toString().contains("mac", true) })
        val exported = root["samples"] as List<*>
        val zero = exported[0] as Map<*, *>
        assertEquals(samples[0].id, zero["id"])
        assertEquals(trip.id, zero["tripId"])
        assertEquals(0L, zero["sequence"])
        assertEquals(1_000L, zero["elapsedMillis"])
        assertEquals("1970-01-01T00:00:01Z", zero["observedAtUtc"])
        assertEquals(1_000L, zero["observedAtEpochMillis"])
        assertEquals("SPEED", zero["metric"])
        assertEquals("km/h", zero["unit"])
        assertEquals("7E8", zero["ecuId"])
        assertEquals(0.0, zero["value"])
        val missing = exported[1] as Map<*, *>
        assertNull(missing["value"])
        assertEquals("TIMEOUT", missing["quality"])
    }

    @Test fun csvRoundTripsQuotedFieldsAndKeepsNullDistinctFromZeroWithTripMetadataOnEveryRow() = runTest {
        val trip = analysisTrip().copy(vehicleAlias = "Synthetic, \"RX-8\"\nsecond line", endReason = "USER_STOP")
        val writer = StringWriter()
        TripExporter.writeCsv(AnalyticsExportRepository(listOf(
            analysisSample(0, 0, 0.0), analysisSample(1, 1_000, null, quality = ObdReadingStatus.NO_DATA),
        )), trip, writer)
        val rows = readCsv(writer.toString())
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.size == 24 })
        val headers = rows.first()
        val zero = headers.zip(rows[1]).toMap()
        val missing = headers.zip(rows[2]).toMap()
        for (row in listOf(zero, missing)) {
            assertEquals("1", row["schema_version"])
            assertEquals("SAMPLE", row["record_type"])
            assertEquals(trip.id, row["trip_id"])
            assertEquals(trip.vehicleAlias, row["vehicle_alias"])
            assertEquals("USER_STOP", row["trip_end_reason"])
            assertEquals("COMPLETED", row["trip_status"])
            assertEquals("REAL_OBD", row["origin"])
        }
        assertEquals("0.0", zero["value"])
        assertEquals("", missing["value"])
        assertEquals("NO_DATA", missing["quality"])
        assertEquals("1970-01-01T00:00:01Z", missing["observed_at_utc"])
    }

    @Test fun csvNeutralizesLeadingSpreadsheetFormulasIncludingWhitespaceAndControls() = runTest {
        for (alias in listOf("=1+1", "+SUM(1,2)", "-1+2", "@SUM(A1)", " \t=1+1", "\r\n=1+1", "\uFEFF=1+1")) {
            val writer = StringWriter()
            TripExporter.writeCsv(AnalyticsExportRepository(), analysisTrip().copy(vehicleAlias = alias), writer)
            val rows = readCsv(writer.toString())
            assertEquals("'$alias", rows[1][rows[0].indexOf("vehicle_alias")])
        }
        val writer = StringWriter()
        TripExporter.writeCsv(AnalyticsExportRepository(listOf(analysisSample(0, 0, 0.0).copy(
            id = "=sample", ecuId = "@ecu", unit = "+unit",
        ))), analysisTrip().copy(endReason = "-reason", protocolLabel = "=protocol"), writer)
        val rows = readCsv(writer.toString())
        val row = rows[0].zip(rows[1]).toMap()
        assertEquals("'=sample", row["sample_id"])
        assertEquals("'@ecu", row["ecu_id"])
        assertEquals("'+unit", row["unit"])
        assertEquals("'-reason", row["trip_end_reason"])
        assertEquals("'=protocol", row["protocol_label"])
    }

    @Test fun emptyTripRetainsMetadataInBothFormatsAndNullableMetadataRemainsAbsent() = runTest {
        val trip = analysisTrip().copy(status = TripStatus.INTERRUPTED, endReason = "PROCESS_LOSS", endedAtEpochMillis = null, protocolLabel = null)
        val json = StringWriter()
        TripExporter.writeJson(AnalyticsExportRepository(), trip, json)
        val root = JsonFixtureReader(json.toString()).read() as Map<*, *>
        assertEquals(emptyList<Any>(), root["samples"])
        val metadata = root["trip"] as Map<*, *>
        assertEquals("INTERRUPTED", metadata["status"])
        assertNull(metadata["endedAtUtc"])
        assertNull(metadata["protocolLabel"])
        val csv = StringWriter()
        TripExporter.writeCsv(AnalyticsExportRepository(), trip, csv)
        val rows = readCsv(csv.toString())
        assertEquals(2, rows.size)
        val row = rows[0].zip(rows[1]).toMap()
        assertEquals("METADATA", row["record_type"])
        assertEquals(trip.id, row["trip_id"])
        assertEquals("PROCESS_LOSS", row["trip_end_reason"])
        assertEquals("", row["sample_id"])
    }

    @Test fun samplesAreStreamedBeforeTheNextBoundedPageIsRead() = runTest {
        for (export in exporters) {
            val writer = StringWriter()
            val repository = AnalyticsExportRepository((0L..1_000L).map { analysisSample(it, it, 0.0) }).apply {
                beforePage = { number -> if (number == 2) assertTrue(writer.toString().contains("sample-499")) }
            }
            export(repository, analysisTrip(), writer)
            assertEquals(listOf(-1L, 499L, 999L, 1_000L), repository.requests.map { it.first })
            assertTrue(repository.requests.all { it.second == 500 })
            assertTrue(writer.toString().contains("sample-1000"))
        }
    }

    @Test fun callerRetainsOwnershipOfWriterOnSuccessAndFailure() = runTest {
        for (export in exporters) {
            val writer = OwnershipWriter()
            export(AnalyticsExportRepository(), analysisTrip(), writer)
            assertFalse(writer.closed)
            assertFalse(writer.flushed)
            val broken = OwnershipWriter(failWrites = true)
            var failure: Throwable? = null
            try { export(AnalyticsExportRepository(), analysisTrip(), broken) } catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IOException)
            assertFalse(broken.closed)
        }
    }

    @Test fun storageFailurePropagatesInsteadOfCompletingAPartialExport() = runTest {
        for (export in exporters) {
            val repository = AnalyticsExportRepository(listOf(analysisSample(0, 0, 1.0))).apply {
                beforePage = { number -> if (number == 2) throw IOException("Synthetic storage failure") }
            }
            var failure: Throwable? = null
            try { export(repository, analysisTrip(), StringWriter()) } catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IOException)
            assertEquals(2, repository.requests.size)
        }
    }

    @Test fun nonfiniteNumbersFailRatherThanEmitInvalidJsonOrFabricatedZeros() = runTest {
        for (export in exporters) for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            var failure: Throwable? = null
            try { export(AnalyticsExportRepository(listOf(analysisSample(0, 0, value))), analysisTrip(), StringWriter()) }
            catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IOException)
        }
    }

    @Test fun cancellationBetweenPagingAndWritingIsNotSwallowed() = runTest {
        for (export in exporters) {
            val job = Job()
            val repository = AnalyticsExportRepository(listOf(analysisSample(0, 0, 1.0))).apply { beforePage = { job.cancel() } }
            var cancelled = false
            try { withContext(job) { export(repository, analysisTrip(), StringWriter()) } }
            catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(1, repository.requests.size)
        }
    }

    @Test fun recordingSessionsAreRejectedBeforeWritingOrQuerying() = runTest {
        for (export in exporters) {
            val repository = AnalyticsExportRepository()
            val writer = StringWriter()
            var failure: Throwable? = null
            try { export(repository, analysisTrip().copy(status = TripStatus.RECORDING), writer) }
            catch (caught: Throwable) { failure = caught }
            assertTrue(failure is IllegalArgumentException)
            assertTrue(repository.requests.isEmpty())
            assertEquals("", writer.toString())
        }
    }

    private class OwnershipWriter(private val failWrites: Boolean = false) : Writer() {
        var closed = false
        var flushed = false
        override fun write(cbuf: CharArray, off: Int, len: Int) { if (failWrites) throw IOException("Synthetic destination failure") }
        override fun flush() { flushed = true }
        override fun close() { closed = true }
    }
}

/** Independent strict reader for the JSON subset in the export fixtures; no Android JSON stubs. */
private class JsonFixtureReader(private val source: String) {
    private var cursor = 0
    fun read(): Any? = value().also { whitespace(); check(cursor == source.length) }
    private fun value(): Any? {
        whitespace()
        return when (source[cursor]) {
            '{' -> {
                cursor++
                val fields = linkedMapOf<String, Any?>()
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string()
                        whitespace(); check(take(':'))
                        check(!fields.containsKey(key)); fields[key] = value()
                        whitespace()
                    } while (take(','))
                    check(take('}'))
                }
                fields
            }
            '[' -> {
                cursor++
                val entries = mutableListOf<Any?>()
                whitespace()
                if (!take(']')) {
                    do { entries += value(); whitespace() } while (take(','))
                    check(take(']'))
                }
                entries
            }
            '"' -> string()
            'n' -> { check(source.substring(cursor, cursor + 4) == "null"); cursor += 4; null }
            else -> {
                val start = cursor
                while (cursor < source.length && source[cursor] in "-+0123456789.eE") cursor++
                val number = source.substring(start, cursor)
                check(number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                if (number.any { it in ".eE" }) number.toDouble() else number.toLong()
            }
        }
    }
    private fun string(): String {
        check(take('"'))
        val result = StringBuilder()
        while (true) {
            val next = source[cursor++]
            if (next == '"') return result.toString()
            check(next.code >= 0x20)
            if (next != '\\') result.append(next) else when (val escape = source[cursor++]) {
                '"', '\\', '/' -> result.append(escape)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> { result.append(source.substring(cursor, cursor + 4).toInt(16).toChar()); cursor += 4 }
                else -> error("Invalid JSON string escape")
            }
        }
    }
    private fun whitespace() { while (cursor < source.length && source[cursor] in " \r\n\t") cursor++ }
    private fun take(character: Char): Boolean = (source.getOrNull(cursor) == character).also { if (it) cursor++ }
}

/** Parses quoted commas, doubled quotes, and multiline cells, independently of the writer. */
private fun readCsv(source: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < source.length) {
        val next = source[index++]
        when {
            quoted && next == '"' && source.getOrNull(index) == '"' -> { field.append('"'); index++ }
            next == '"' -> quoted = !quoted
            !quoted && next == ',' -> { fields += field.toString(); field.clear() }
            !quoted && next == '\r' -> {
                check(source.getOrNull(index) == '\n'); index++
                fields += field.toString(); field.clear(); rows += fields.toList(); fields.clear()
            }
            else -> field.append(next)
        }
    }
    check(!quoted)
    if (field.isNotEmpty() || fields.isNotEmpty()) { fields += field.toString(); rows += fields.toList() }
    return rows
}
