package com.gonibix.rx8.capabilities

import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportTest {
    @Test fun onlyFinishedCapabilityDiscoveryEnablesCapture() {
        for (phase in ObdPhase.entries) {
            val snapshot = reportTestSnapshot().copy(phase = phase)
            val eligible = phase in setOf(ObdPhase.LIVE, ObdPhase.STOPPED, ObdPhase.ERROR)
            assertEquals(eligible, CapabilityReports.canCapture(snapshot))
            if (!eligible) assertThrows(IllegalArgumentException::class.java) { reportTestCapture(snapshot) }
        }
        assertFalse(CapabilityReports.canCapture(reportTestSnapshot().copy(ecus = emptyList())))
        assertFalse(CapabilityReports.canCapture(reportTestSnapshot().copy(elapsedMillis = -1)))
    }

    @Test fun completedDiscoveryCanHonestlyAdvertiseNoPidsOrReadings() {
        val snapshot = reportTestSnapshot().copy(ecus = listOf(ObdEcuCapabilities("7E8", emptySet())), readings = emptyList())
        assertTrue(CapabilityReports.canCapture(snapshot))
        val ecu = reportTestCapture(snapshot).ecus.single()
        assertTrue(ecu.advertisedPids.isEmpty())
        assertEquals(ObdMetric.entries, ecu.metrics.map { it.metric })
        assertTrue(ecu.metrics.all { !it.advertised && it.reading == null })
    }

    @Test fun sortsSourcesAndAllReportedPidsWhileSeparatingImplementedDecoders() {
        val snapshot = reportTestSnapshot().copy(ecus = listOf(
            ObdEcuCapabilities("7E9", setOf(0x20, 0xFF, 0x0D, 0x40, 0x01)),
            ObdEcuCapabilities("7E8", setOf(0x0C)),
        ))
        val report = reportTestCapture(snapshot)
        assertEquals(listOf("7E8", "7E9"), report.ecus.map { it.address })
        assertEquals(listOf(0x01, 0x0D, 0xFF), report.ecus[1].advertisedPids)
        assertEquals(listOf(ObdMetric.SPEED), report.ecus[1].metrics.filter { it.advertised }.map { it.metric })
        assertEquals(ObdMetric.entries.size, report.ecus[1].metrics.size)
    }

    @Test fun twoEcusKeepSeparateValuesWithoutSourceOrCapabilityMerging() {
        val report = reportTestCapture(reportTestSnapshot().copy(
            ecus = listOf(ObdEcuCapabilities("7E8", setOf(0x0D)), ObdEcuCapabilities("7E9", setOf(0x0D))),
            readings = listOf(reportTestReading(value = 0.0), reportTestReading(ecu = "7E9", value = 60.0)),
        ))
        assertEquals(0.0, report.ecus[0].metrics.single { it.metric == ObdMetric.SPEED }.reading?.value)
        assertEquals(60.0, report.ecus[1].metrics.single { it.metric == ObdMetric.SPEED }.reading?.value)
    }

    @Test fun failedDisplayStatesNeverRetainAValueAndZeroIsValid() {
        for (status in ObdReadingStatus.entries) {
            val reading = reportTestCapture(reportTestSnapshot().copy(readings = listOf(reportTestReading(value = 0.0, status = status))))
                .ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading!!
            assertEquals(status, reading.status)
            if (status in setOf(ObdReadingStatus.VALID, ObdReadingStatus.STALE)) assertEquals(0.0, reading.value) else assertNull(reading.value)
        }
    }

    @Test fun terminalAndOldValuesAreStaleWhileSourceSnapshotIsUnmodified() {
        for (phase in listOf(ObdPhase.STOPPED, ObdPhase.ERROR)) {
            val source = reportTestSnapshot().copy(phase = phase)
            val reading = reportTestCapture(source).ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading!!
            assertEquals(ObdReadingStatus.STALE, reading.status)
            assertEquals(40.0, reading.value)
            assertEquals(1_000L, reading.ageMillis)
            assertEquals(ObdReadingStatus.VALID, source.readings.single().status)
        }
        val old = reportTestCapture(reportTestSnapshot().copy(elapsedMillis = 6_000))
        assertEquals(ObdReadingStatus.STALE, old.ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading?.status)
    }

    @Test fun ageUsesElapsedTimeOnlyAndInvalidAgeIsNullWithoutOverflow() {
        val source = reportTestSnapshot()
        for (elapsed in listOf(-1L, Long.MAX_VALUE)) {
            val reading = reportTestCapture(source.copy(readings = listOf(reportTestReading().copy(elapsedMillis = elapsed))))
                .ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading!!
            assertNull(reading.ageMillis)
            assertEquals(ObdReadingStatus.STALE, reading.status)
        }
        val differentClocks = source.copy(readings = listOf(reportTestReading().copy(observedAtEpochMillis = Long.MAX_VALUE, monotonicMillis = Long.MIN_VALUE)))
        assertEquals(1_000L, reportTestCapture(differentClocks).ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading?.ageMillis)
        val extreme = source.copy(elapsedMillis = Long.MAX_VALUE, readings = listOf(reportTestReading().copy(elapsedMillis = 0)))
        assertEquals(Long.MAX_VALUE, reportTestCapture(extreme).ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading?.ageMillis)
    }

    @Test fun invalidNumericReadingsAreUnavailableAndRatesAreSanitized() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val report = reportTestCapture(reportTestSnapshot().copy(readings = listOf(reportTestReading(value = value))))
            assertNull(report.ecus.single().metrics.single { it.metric == ObdMetric.SPEED }.reading)
        }
        for (rate in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(reportTestCapture(reportTestSnapshot().copy(effectiveResponsesPerSecond = rate)).effectiveResponsesPerSecond)
        }
        assertEquals(0.0, reportTestCapture(reportTestSnapshot().copy(effectiveResponsesPerSecond = 0.0)).effectiveResponsesPerSecond)
        assertNull(reportTestCapture(reportTestSnapshot().copy(elapsedMillis = 0)).effectiveResponsesPerSecond)
    }

    @Test fun onlyExactRecognizedProtocolDescriptionsAreRetained() {
        for (protocol in ObdProtocol.entries) {
            assertEquals(protocol.description, reportTestCapture(reportTestSnapshot().copy(protocolLabel = protocol.description)).protocolLabel)
        }
        for (unknown in listOf("CAN", "6", "ELM327", "ISO 15765-CAN (11 bit, 500 kbaud)", "private error\nTEST1234567890123")) {
            assertNull(reportTestCapture(reportTestSnapshot().copy(protocolLabel = unknown)).protocolLabel)
        }
    }

    @Test fun identifiersOutsideKnownParserFormatsAreOmittedAndNoValidEcuFails() {
        val invalid = listOf("SIMULATOR", "00:11:22:33:44:55", "car alias", "TEST1234567890123", "7e8", "FFF", "FFFFFFFF", "7E8\n", "7E8\"payload")
        for (address in invalid) {
            val snapshot = reportTestSnapshot().copy(ecus = listOf(ObdEcuCapabilities(address, setOf(0x0D)), ObdEcuCapabilities("7E8", setOf(0x0D))))
            assertEquals(listOf("7E8"), reportTestCapture(snapshot).ecus.map { it.address })
            assertFalse(CapabilityReports.canCapture(snapshot.copy(ecus = snapshot.ecus.take(1))))
        }
        for (address in listOf("10", "7E8", "18DAF110")) {
            assertTrue(CapabilityReports.canCapture(reportTestSnapshot().copy(ecus = listOf(ObdEcuCapabilities(address, setOf(0x0D))))))
        }
    }

    @Test fun duplicateValidEcuOrAdvertisedMetricIsRejectedRatherThanPickedArbitrarily() {
        val snapshot = reportTestSnapshot()
        assertFalse(CapabilityReports.canCapture(snapshot.copy(ecus = snapshot.ecus + snapshot.ecus)))
        assertFalse(CapabilityReports.canCapture(snapshot.copy(readings = snapshot.readings + snapshot.readings)))
        assertThrows(IllegalArgumentException::class.java) { reportTestCapture(snapshot.copy(readings = snapshot.readings + reportTestReading(value = 900.0))) }
    }

    @Test fun unsupportedAndUnmatchedReadingsCannotInventSupportOrReplaceTheRealSource() {
        val snapshot = reportTestSnapshot().copy(readings = listOf(
            reportTestReading(ecu = "7E9", value = 999.0),
            reportTestReading(metric = ObdMetric.ENGINE_RPM, value = 9_000.0),
            reportTestReading(metric = ObdMetric.ENGINE_RPM, value = 8_000.0),
        ))
        val ecu = reportTestCapture(snapshot).ecus.single()
        assertTrue(ecu.metrics.all { it.reading == null })
        assertTrue(ecu.metrics.single { it.metric == ObdMetric.SPEED }.advertised)
        assertFalse(ecu.metrics.single { it.metric == ObdMetric.ENGINE_RPM }.advertised)
    }

    @Test fun captureDeeplyFreezesCallerListsAndCapabilitySets() {
        val pids = mutableSetOf(0x0D)
        val ecus = mutableListOf(ObdEcuCapabilities("7E8", pids))
        val readings = mutableListOf(reportTestReading())
        val report = reportTestCapture(reportTestSnapshot().copy(ecus = ecus, readings = readings))
        val json = CapabilityReportJson.encode(report)
        pids.clear(); ecus.clear(); readings.clear()
        assertEquals(json, CapabilityReportJson.encode(report))
        assertThrows(UnsupportedOperationException::class.java) { (report.ecus as MutableList<CapabilityEcu>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (report.ecus[0].advertisedPids as MutableList<Int>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (report.ecus[0].metrics as MutableList<CapabilityMetric>).clear() }
        val suppliedMetrics = report.ecus[0].metrics.toMutableList()
        val suppliedPids = mutableListOf(0x0D)
        val suppliedEcu = CapabilityEcu("7E8", suppliedPids, suppliedMetrics)
        suppliedMetrics.clear(); suppliedPids.clear()
        assertEquals(ObdMetric.entries.size, suppliedEcu.metrics.size)
        assertEquals(listOf(0x0D), suppliedEcu.advertisedPids)
    }

    @Test fun invalidPidsOversizedCollectionsAndInvalidMetadataFailWithoutEchoingInput() {
        for (pid in listOf(-1, 0, 256, Int.MAX_VALUE)) {
            assertFalse(CapabilityReports.canCapture(reportTestSnapshot().copy(ecus = listOf(ObdEcuCapabilities("7E8", setOf(pid))))))
        }
        assertFalse(CapabilityReports.canCapture(reportTestSnapshot().copy(ecus = List(33) { ObdEcuCapabilities("%02X".format(it), setOf(0x0D)) })))
        assertFalse(CapabilityReports.canCapture(reportTestSnapshot().copy(readings = List(32 * ObdMetric.entries.size + 1) { reportTestReading() })))
        for (version in listOf("TEST1234567890123", "0.9.0-private-person", "0.9.0-dev\n", "x".repeat(513_000))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { CapabilityReports.capture(reportTestSnapshot(), version, 36, 0) }
            assertFalse(failure.message.orEmpty().contains(version))
        }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReports.capture(reportTestSnapshot(), "0.9.0-dev", -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReports.capture(reportTestSnapshot(), "0.9.0-dev", 36, -1) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityReports.capture(reportTestSnapshot(), "0.9.0-dev", 36, 0, "private-name") }
    }

    @Test fun expandedMetricsPreserveTheirOwnFreshnessSignedValuesAndRangeValidation() {
        val metric = ObdMetric.CURRENT_OXYGEN_SENSOR_1_CURRENT
        val snapshot = reportTestSnapshot().copy(
            elapsedMillis = 30_000,
            ecus = listOf(ObdEcuCapabilities("7E8", setOf(metric.pid))),
            readings = listOf(reportTestReading(metric, -17.875).copy(elapsedMillis = 0)),
        )
        val reading = reportTestCapture(snapshot).ecus.single().metrics.single { it.metric == metric }.reading!!
        assertEquals(ObdReadingStatus.VALID, reading.status)
        assertEquals(-17.875, reading.value)
        assertEquals(30_000L, reading.ageMillis)
        val old = reportTestCapture(snapshot.copy(elapsedMillis = metric.freshnessMillis))
        assertEquals(ObdReadingStatus.STALE, old.ecus.single().metrics.single { it.metric == metric }.reading!!.status)
        val invalid = reportTestCapture(snapshot.copy(readings = listOf(reportTestReading(metric, -256.0))))
        assertNull(invalid.ecus.single().metrics.single { it.metric == metric }.reading)
    }

    @Test fun everyCaptureHasANewReportUuidEvenWhenSnapshotIsUnchanged() {
        val first = CapabilityReports.capture(reportTestSnapshot(), "0.9.0-dev", 36, 0)
        val second = CapabilityReports.capture(reportTestSnapshot(), "0.9.0-dev", 36, 0)
        assertNotEquals(first.reportId, second.reportId)
        assertEquals(4, UUID.fromString(first.reportId).version())
    }
}

internal fun reportTestSnapshot() = ObdSessionSnapshot(
    phase = ObdPhase.LIVE,
    protocolLabel = ObdProtocol.CAN_11_500.description,
    ecus = listOf(ObdEcuCapabilities("7E8", setOf(0x0D))),
    readings = listOf(reportTestReading()),
    elapsedMillis = 2_000,
    effectiveResponsesPerSecond = 2.5,
)

internal fun reportTestReading(
    metric: ObdMetric = ObdMetric.SPEED,
    value: Double? = 40.0,
    status: ObdReadingStatus = ObdReadingStatus.VALID,
    ecu: String = "7E8",
) = ObdReading(metric, value, status, ecu, 123_456, 1_000, 5_000)

internal fun reportTestCapture(snapshot: ObdSessionSnapshot = reportTestSnapshot()) = CapabilityReports.capture(
    snapshot, "0.9.0-dev", 36, 1_234, "00000000-0000-4000-8000-000000000001",
)
