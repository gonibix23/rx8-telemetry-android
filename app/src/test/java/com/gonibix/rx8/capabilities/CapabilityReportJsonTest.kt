package com.gonibix.rx8.capabilities

import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdVinSnapshot
import com.gonibix.rx8.obd.ObdVinStatus
import com.gonibix.rx8.telemetry.obd.VinResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportJsonTest {
    @Test fun schemaRoundTripsOnlyAllowlistedFieldsWithUtcCaptureTimeAndDisplaySemantics() {
        val report = reportTestCapture()
        val json = CapabilityReportJson.encode(report)
        val root = CapabilityJsonFixture(json).read() as Map<*, *>
        assertEquals(setOf("schemaVersion", "kind", "dataOrigin", "snapshotSemantics", "reportId", "capturedAtUtc", "capturedAtEpochMillis",
            "appVersion", "androidApiLevel", "phase", "protocolLabel", "elapsedMillis", "effectiveResponsesPerSecond", "ecus"), root.keys)
        assertEquals(1L, root["schemaVersion"])
        assertEquals("capability_snapshot", root["kind"])
        assertEquals("real_obd", root["dataOrigin"])
        assertEquals("latest_display_state", root["snapshotSemantics"])
        assertEquals("1970-01-01T00:00:01.234Z", root["capturedAtUtc"])
        assertEquals(1_234L, root["capturedAtEpochMillis"])
        assertEquals(2_000L, root["elapsedMillis"])
        assertEquals(2.5, root["effectiveResponsesPerSecond"])
        val ecu = (root["ecus"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("address", "reportedPids", "implementedMetrics"), ecu.keys)
        assertEquals(listOf("0D"), ecu["reportedPids"])
        val metrics = ecu["implementedMetrics"] as List<*>
        assertEquals(6, metrics.size)
        for (entry in metrics) {
            val metric = entry as Map<*, *>
            assertEquals(setOf("metric", "pid", "unit", "advertised", "reading"), metric.keys)
        }
        val speed = metrics.map { it as Map<*, *> }.single { it["metric"] == "SPEED" }
        val reading = speed["reading"] as Map<*, *>
        assertEquals(setOf("value", "status", "ageMillis"), reading.keys)
        assertEquals("VALID", reading["status"])
        assertEquals(1_000L, reading["ageMillis"])
        assertEquals(json, CapabilityReportJson.encode(report))
    }

    @Test fun hostileSnapshotFieldsVinAndIdentifiersNeverEnterTheReport() {
        val privateVin = "TEST1234567890123"
        val privateName = "Synthetic private person"
        val privateMac = "00:11:22:33:44:55"
        val privateId = "00000000-0000-4000-8000-999999999999"
        val attack = "$privateVin $privateName $privateMac $privateId \"payload\":\"secret\"\nraw command log"
        val snapshot = reportTestSnapshot().copy(
            adapterIdentity = attack,
            statusMessage = attack,
            protocolLabel = attack,
            vin = ObdVinSnapshot(ObdVinStatus.AVAILABLE, attack, listOf(VinResponse(privateName, privateVin))),
            ecus = reportTestSnapshot().ecus + ObdEcuCapabilities(attack, setOf(0x0C)),
            readings = reportTestSnapshot().readings + reportTestReading(ecu = attack),
        )
        val json = CapabilityReportJson.encode(reportTestCapture(snapshot))
        for (secret in listOf(privateVin, privateName, privateMac, privateId, "secret", "raw command log")) assertFalse(json.contains(secret))
        for (field in listOf("vin", "candidates", "adapterIdentity", "statusMessage", "observedAtEpochMillis", "monotonicMillis", "vehicleId", "tripId", "profileId")) {
            assertFalse(json.contains("\"$field\""))
        }
        val root = CapabilityJsonFixture(json).read() as Map<*, *>
        assertNull(root["protocolLabel"])
        assertEquals(1, (root["ecus"] as List<*>).size)
    }

    @Test fun noDataDisplayPlaceholderDoesNotBecomeRequestHistoryOrFailureCounts() {
        val snapshot = reportTestSnapshot().copy(readings = listOf(reportTestReading(value = null, status = ObdReadingStatus.NO_DATA)))
        val json = CapabilityReportJson.encode(reportTestCapture(snapshot))
        assertTrue(json.contains("\"value\":null,\"status\":\"NO_DATA\",\"ageMillis\":1000"))
        for (field in listOf("requestCount", "successCount", "failureCount", "responses", "history", "validated", "observedSamples")) {
            assertFalse(json.contains("\"$field\""))
        }
    }

    @Test fun terminalZeroIsExplicitlyStaleAndFailedValuesAreJsonNull() {
        val stopped = reportTestSnapshot().copy(phase = ObdPhase.STOPPED, readings = listOf(reportTestReading(value = 0.0)))
        val json = CapabilityReportJson.encode(reportTestCapture(stopped))
        assertTrue(json.contains("\"phase\":\"STOPPED\""))
        assertTrue(json.contains("\"value\":0.0,\"status\":\"STALE\""))
        assertFalse(json.contains("\"status\":\"VALID\""))
        val error = CapabilityReportJson.encode(reportTestCapture(reportTestSnapshot().copy(readings = listOf(reportTestReading(value = 999.0, status = ObdReadingStatus.ADAPTER_ERROR)))))
        assertTrue(error.contains("\"value\":null,\"status\":\"ADAPTER_ERROR\""))
        assertFalse(error.contains("999.0"))
    }

    @Test fun maximumAllowedReportRemainsBoundedAndKeepsAll248NonContinuationPids() {
        val pids = (1..255).toSet()
        val addresses = (0 until 32).map { it.toString(16).uppercase().padStart(2, '0') }
        val snapshot = reportTestSnapshot().copy(
            ecus = addresses.map { ObdEcuCapabilities(it, pids) },
            readings = addresses.flatMap { ecu -> ObdMetric.entries.map { reportTestReading(it, Double.MAX_VALUE, ecu = ecu).copy(elapsedMillis = 0) } },
            elapsedMillis = Long.MAX_VALUE,
            effectiveResponsesPerSecond = Double.MAX_VALUE,
        )
        val report = reportTestCapture(snapshot)
        val json = CapabilityReportJson.encode(report)
        assertEquals(32, report.ecus.size)
        assertTrue(report.ecus.all { it.advertisedPids.size == 248 })
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= CapabilityReportJson.MAX_BYTES)
        assertEquals(32, ((CapabilityJsonFixture(json).read() as Map<*, *>)["ecus"] as List<*>).size)
        assertFalse(json.contains("Infinity"))
        assertFalse(json.contains("NaN"))
    }

    @Test fun directConstructionCannotBypassPrivacyOrStoppedQualityValidation() {
        val valid = reportTestCapture()
        fun construct(protocol: String? = valid.protocolLabel, phase: ObdPhase = valid.phase, appVersion: String = valid.appVersion) = CapabilityReport(
            valid.reportId, valid.capturedAtEpochMillis, appVersion, valid.androidApiLevel, phase, protocol,
            valid.elapsedMillis, valid.effectiveResponsesPerSecond, valid.ecus,
        )
        assertThrows(IllegalArgumentException::class.java) { construct(protocol = "private protocol text") }
        assertThrows(IllegalArgumentException::class.java) { construct(appVersion = "0.9.0-private-person") }
        assertThrows(IllegalArgumentException::class.java) { construct(phase = ObdPhase.STOPPED) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReading(Double.NaN, ObdReadingStatus.VALID, 0) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReading(1.0, ObdReadingStatus.TIMEOUT, 0) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReading(1.0, ObdReadingStatus.VALID, null) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityMetric(ObdMetric.SPEED, false, CapabilityReading(0.0, ObdReadingStatus.VALID, 0)) }
    }
}

/** Independent small JSON reader: verifies complete documents, escaping, and duplicate-key absence. */
private class CapabilityJsonFixture(private val source: String) {
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
            'n' -> { literal("null"); null }
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            else -> {
                val start = cursor
                while (cursor < source.length && source[cursor] in "-+0123456789.eE") cursor++
                val number = source.substring(start, cursor)
                check(number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                if (number.any { it in ".eE" }) number.toDouble().also { check(it.isFinite()) } else number.toLong()
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
    private fun literal(value: String) { check(source.substring(cursor, cursor + value.length) == value); cursor += value.length }
    private fun whitespace() { while (cursor < source.length && source[cursor] in " \r\n\t") cursor++ }
    private fun take(character: Char): Boolean = (source.getOrNull(cursor) == character).also { if (it) cursor++ }
}
