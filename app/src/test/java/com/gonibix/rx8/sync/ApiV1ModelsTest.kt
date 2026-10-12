package com.gonibix.rx8.sync

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleProfile
import com.gonibix.rx8.vehicle.VinSource
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiV1ModelsTest {
    @Test fun mapsEveryCurrentMetricWithExplicitWireUnitsAndPid() {
        ObdMetric.entries.forEach { metric ->
            val dto = ApiV1Mapper.sample(wireSample().copy(metric = metric, unit = metric.unit, value = metric.minimumValue))
            assertEquals(ApiMetric.valueOf(metric.name), dto.metric)
            val expectedUnit = when (metric.unit) {
                "°C" -> "Cel"
                "N·m" -> "N.m"
                "lambda", "count" -> "1"
                else -> metric.unit
            }
            assertEquals(expectedUnit, dto.unit)
            assertEquals(String.format(Locale.ROOT, "%02X", metric.pid), dto.pid)
            assertEquals("01", dto.service)
            assertEquals("7E8", dto.ecuAddress)
        }
    }

    @Test fun expandedComponentsKeepStableDistinctWireIdsUnitsSignsAndRanges() {
        val samples = listOf(
            wireSample().copy(metric = ObdMetric.CURRENT_OXYGEN_SENSOR_1_RATIO, value = 1.0, unit = "lambda"),
            wireSample(1).copy(metric = ObdMetric.CURRENT_OXYGEN_SENSOR_1_CURRENT, value = -17.875, unit = "mA"),
            wireSample(2).copy(metric = ObdMetric.ENGINE_REFERENCE_TORQUE, value = 400.0, unit = "N·m"),
        )
        val mapped = samples.map(ApiV1Mapper::sample)
        assertEquals(listOf("current_oxygen_sensor_1_ratio", "current_oxygen_sensor_1_current", "engine_reference_torque"), mapped.map { it.metric.wire })
        assertEquals(listOf("1", "mA", "N.m"), mapped.map { it.unit })
        assertEquals(listOf("34", "34", "63"), mapped.map { it.pid })
        assertEquals(-17.875, mapped[1].value)
        val json = ApiV1Json.batch(ApiV1Mapper.batch(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, samples))
        assertTrue(json.contains("\"value\":-17.875,\"unit\":\"mA\""))
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.sample(samples[1].copy(value = -256.0)) }
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.sample(samples[2].copy(unit = "Nm")) }
    }

    @Test fun errorsRemainDistinctNullValuesAndValidZeroRemainsZero() {
        val expected = mapOf(
            ObdReadingStatus.TIMEOUT to "timeout", ObdReadingStatus.UNSUPPORTED to "unsupported",
            ObdReadingStatus.PARSE_ERROR to "parse_error", ObdReadingStatus.NO_DATA to "no_data",
            ObdReadingStatus.ADAPTER_ERROR to "adapter_error",
        )
        expected.forEach { (quality, wire) ->
            val dto = ApiV1Mapper.sample(wireSample().copy(quality = quality, value = 42.0))
            assertEquals(wire, dto.status.wire)
            assertNull(dto.value)
        }
        assertEquals(0.0, ApiV1Mapper.sample(wireSample().copy(value = 0.0)).value)
    }

    @Test fun staleWrongUnitMissingValidValueAndNonfiniteValuesAreRejected() {
        val invalid = listOf(
            wireSample().copy(quality = ObdReadingStatus.STALE), wireSample().copy(unit = "mph"),
            wireSample().copy(value = null), wireSample().copy(value = Double.NaN),
            wireSample().copy(value = Double.POSITIVE_INFINITY),
        )
        invalid.forEach { sample -> assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.sample(sample) } }
    }

    @Test fun timestampsAndMonotonicSequenceSurviveMapping() {
        val dto = ApiV1Mapper.sample(wireSample().copy(observedAtEpochMillis = 1_234, elapsedMillis = 500, sequence = 17))
        assertEquals(Instant.parse("1970-01-01T00:00:01.234Z"), dto.observedAt)
        assertEquals(500L, dto.elapsedMs)
        assertEquals(17L, dto.sequence)
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.sample(wireSample().copy(elapsedMillis = -1)) }
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.sample(wireSample().copy(observedAtEpochMillis = -1)) }
    }

    @Test fun legacyVersionUnknownAndRecordingDurationExcludedUntilTerminalRevision() {
        val session = TripSession(WIRE_SESSION, WIRE_VEHICLE, "Synthetic", 0, durationMillis = 5_000)
        val open = ApiV1Mapper.session(session, 1)
        assertNull(open.appVersion)
        assertNull(open.durationMs)
        assertNull(open.endedAt)
        val closed = ApiV1Mapper.session(session.copy(status = TripStatus.INTERRUPTED, endedAtEpochMillis = 5_000, appVersion = "0.8.0-dev"), 2)
        assertEquals(5_000L, closed.durationMs)
        assertEquals(2L, closed.revision)
        assertEquals("0.8.0-dev", closed.appVersion)
        assertEquals(ApiSessionStatus.INTERRUPTED, closed.status)
        assertEquals(ApiDataOrigin.REAL, closed.dataOrigin)
    }

    @Test fun originIsExplicitAndUnknownOriginRejected() {
        val session = TripSession(WIRE_SESSION, WIRE_VEHICLE, "Synthetic", 0)
        assertEquals(ApiDataOrigin.SIMULATED, ApiV1Mapper.session(session.copy(origin = "SIMULATED"), 1).dataOrigin)
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.session(session.copy(origin = "UNKNOWN"), 1) }
    }

    @Test fun optionalVinRevisionDoesNotChangeVehicleIdentityOrInventCarMetadata() {
        val profile = VehicleProfile(WIRE_VEHICLE, "Synthetic", null, null, null, 0)
        val first = ApiV1Mapper.vehicle(profile, 1)
        val later = ApiV1Mapper.vehicle(profile.copy(vin = "TEST1234567890123", vinSource = VinSource.MANUAL), 2)
        assertEquals(first.vehicleId, later.vehicleId)
        assertEquals(2L, later.revision)
        assertEquals(ApiVinSource.MANUAL, later.vinSource)
        assertNull(first.vin)
        assertNull(first.make)
        assertNull(first.model)
        assertNull(first.modelYear)
        assertNull(first.market)
        assertNull(first.powerCv)
        assertThrows(IllegalArgumentException::class.java) { first.copy(vin = "TEST1234567890123") }
    }

    @Test fun batchRejectsForeignSessionDuplicatesAndReorderedSequences() {
        val one = wireSample()
        val two = wireSample(1)
        listOf(listOf(one, one), listOf(two, one), listOf(one.copy(tripId = WIRE_VEHICLE))).forEach { samples ->
            assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.batch(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, samples) }
        }
        assertThrows(IllegalArgumentException::class.java) { ApiV1Mapper.batch(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, emptyList()) }
    }

    @Test fun boundedBatchAndPreparedRequestCopyCallerCollections() {
        val input = mutableListOf(ApiV1Mapper.sample(wireSample()))
        val batch = TelemetryBatchDtoV1(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, input)
        input.clear()
        assertEquals(1, batch.samples.size)
        val ids = mutableListOf(wireSample().id)
        val request = wireRequest(sampleIds = ids)
        ids.clear()
        assertEquals(1, request.sampleIds.size)
        assertThrows(UnsupportedOperationException::class.java) { (request.sampleIds as MutableList<String>).clear() }
        assertThrows(IllegalArgumentException::class.java) { BatchLimits(maxSamples = 501) }
        assertThrows(IllegalArgumentException::class.java) { BatchLimits(maxBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { BatchLimits(maxBytes = 1024 * 1024 + 1) }
    }

    @Test fun vehicleJsonHasStableEscapingNullsAndNoAdapterIdentity() {
        val dto = VehicleDtoV1(WIRE_VEHICLE, 1, null, null, "Test \"é\"\\\uD83D\uDE97")
        val expected = "{\"schemaVersion\":1,\"vehicleId\":\"$WIRE_VEHICLE\",\"revision\":1," +
            "\"vin\":null,\"vinSource\":null,\"alias\":\"Test \\\"é\\\"\\\\\\ud83d\\ude97\",\"make\":null," +
            "\"model\":null,\"modelYear\":null,\"market\":null,\"powerCv\":null}"
        assertEquals(expected, ApiV1Json.vehicle(dto))
        assertEquals(expected, ApiV1Json.vehicle(dto))
        assertFalse(expected.contains("mac", ignoreCase = true))
        assertThrows(IllegalArgumentException::class.java) { dto.copy(alias = "Control\nline") }
        assertThrows(IllegalArgumentException::class.java) { dto.copy(alias = "Control\u0001") }
        assertThrows(IllegalArgumentException::class.java) { dto.copy(alias = " ") }
        assertThrows(IllegalArgumentException::class.java) { dto.copy(vehicleId = "1-1-1-1-1") }
    }

    @Test fun batchJsonUsesWireEnumsUtcAndNullRatherThanInventedZero() {
        val dto = ApiV1Mapper.batch(WIRE_BATCH, WIRE_VEHICLE, WIRE_SESSION, listOf(
            wireSample().copy(value = 0.0), wireSample(1).copy(quality = ObdReadingStatus.NO_DATA, value = null),
        ))
        val json = ApiV1Json.batch(dto)
        assertTrue(json.contains("\"observedAt\":\"1970-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"metric\":\"speed\",\"value\":0.0,\"unit\":\"km/h\",\"status\":\"ok\""))
        assertTrue(json.contains("\"value\":null,\"unit\":\"km/h\",\"status\":\"no_data\""))
        assertTrue(json.contains("\"source\":\"obd_standard\",\"service\":\"01\",\"pid\":\"0D\",\"ecuAddress\":\"7E8\""))
    }

    @Test fun payloadByteLimitMeasuresUtf8AndMetadataHasNoIdempotencyHeader() {
        val request = wireRequest(payload = "é")
        assertEquals(2, request.payloadBytes)
        assertEquals(WIRE_BATCH, request.idempotencyKey)
        assertNull(PreparedRequest(SyncRequestKind.VEHICLE, "entry", WIRE_VEHICLE, WIRE_VEHICLE, revision = 1, payloadJson = "{}").idempotencyKey)
    }
}

internal const val WIRE_VEHICLE = "00000000-0000-4000-8000-000000000001"
internal const val WIRE_SESSION = "00000000-0000-4000-8000-000000000002"
internal const val WIRE_BATCH = "00000000-0000-4000-8000-000000000003"

internal fun wireSample(sequence: Long = 0) = TripSample(
    id = "00000000-0000-4000-8000-${(sequence + 100).toString().padStart(12, '0')}", tripId = WIRE_SESSION,
    sequence = sequence, observedAtEpochMillis = sequence * 1_000, elapsedMillis = sequence * 1_000,
    metric = ObdMetric.SPEED, value = 50.0, unit = "km/h", ecuId = "7E8", quality = ObdReadingStatus.VALID,
)

internal fun wireRequest(
    sampleIds: List<String> = listOf(wireSample().id), payload: String = "{\"synthetic\":true}", batchId: String = WIRE_BATCH,
) = PreparedRequest(SyncRequestKind.TELEMETRY, "entry-$batchId", batchId, WIRE_VEHICLE, WIRE_SESSION, payloadJson = payload, sampleIds = sampleIds)
