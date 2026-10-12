package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.DemoTelemetrySource
import com.gonibix.rx8.telemetry.SimulationScenario
import com.gonibix.rx8.telemetry.TelemetryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelsTest {
    @Test fun readingsDoNotInventCapabilitiesAndUnsupportedEssentialsAreOmitted() {
        val snapshot = ObdSessionSnapshot(
            phase = ObdPhase.LIVE,
            ecus = listOf(capabilities("7E8", ObdMetric.SPEED)),
            readings = listOf(reading(ObdMetric.SPEED, 0.0), reading(ObdMetric.ENGINE_RPM, 900.0)),
        )
        val model = dashboardModel(snapshot, emptySet())
        assertEquals(listOf(ObdMetric.SPEED), model.ecus.single().readings.map { it.metric })
        assertEquals(listOf(ObdMetric.ENGINE_RPM, ObdMetric.COOLANT_TEMPERATURE), model.unavailablePrimary)
        val withoutCapabilities = dashboardModel(snapshot.copy(ecus = emptyList()), emptySet())
        assertFalse(withoutCapabilities.hasCapabilities)
        assertTrue(withoutCapabilities.ecus.isEmpty())
    }

    @Test fun aFavoriteAppliesOnlyToEachSupportingEcuAndNeverMergesValues() {
        val metric = ObdMetric.INTAKE_TEMPERATURE
        val snapshot = ObdSessionSnapshot(
            phase = ObdPhase.LIVE,
            ecus = listOf(capabilities("7E8", metric), capabilities("7E9", metric), capabilities("7EA", ObdMetric.SPEED)),
            readings = listOf(reading(metric, 21.0), reading(metric, 35.0, ecu = "7E9")),
        )
        val model = dashboardModel(snapshot, setOf(metric))
        assertEquals(listOf("7E8", "7E9", "7EA"), model.ecus.map { it.ecuId })
        assertEquals(21.0, model.ecus[0].readings.single().value)
        assertEquals(35.0, model.ecus[1].readings.single().value)
        assertTrue(model.ecus[0].readings.single().isFavorite)
        assertFalse(model.ecus[2].readings.any { it.metric == metric })
    }

    @Test fun extrasRequireSelectionButSavedUnavailableFavoritesRemainRemovable() {
        val snapshot = ObdSessionSnapshot(ecus = listOf(capabilities("7E8", ObdMetric.ENGINE_LOAD)))
        val model = dashboardModel(snapshot, setOf(ObdMetric.INTAKE_TEMPERATURE))
        assertEquals(setOf(ObdMetric.ENGINE_LOAD), model.availableExtras)
        assertEquals(setOf(ObdMetric.INTAKE_TEMPERATURE), model.unavailableFavorites)
        assertTrue(model.ecus.single().readings.isEmpty())
        val selected = dashboardModel(snapshot, setOf(ObdMetric.ENGINE_LOAD))
        assertEquals(ObdMetric.ENGINE_LOAD, selected.ecus.single().readings.single().metric)
        assertNull(selected.ecus.single().readings.single().value)
    }

    @Test fun fixedMetricsCannotBecomeExtraFavorites() {
        val model = dashboardModel(ObdSessionSnapshot(), setOf(ObdMetric.SPEED))
        assertTrue(model.unavailableFavorites.isEmpty())
        assertTrue(model.availableExtras.isEmpty())
    }

    @Test fun legitimateZeroRemainsDistinctFromMissingAndFailurePlaceholders() {
        val snapshot = ObdSessionSnapshot(
            phase = ObdPhase.LIVE,
            elapsedMillis = 1_000L,
            ecus = listOf(capabilities("7E8", *ObdMetric.entries.toTypedArray())),
            readings = listOf(
                reading(ObdMetric.SPEED, 0.0),
                reading(ObdMetric.ENGINE_RPM, 0.0, ObdReadingStatus.TIMEOUT),
                reading(ObdMetric.COOLANT_TEMPERATURE, null, ObdReadingStatus.NO_DATA),
            ),
        )
        val readings = dashboardModel(snapshot, setOf(ObdMetric.ENGINE_LOAD)).ecus.single().readings.associateBy { it.metric }
        assertEquals(0.0, readings.getValue(ObdMetric.SPEED).value)
        assertEquals(ObdReadingStatus.VALID, readings.getValue(ObdMetric.SPEED).status)
        assertNull(readings.getValue(ObdMetric.ENGINE_RPM).value)
        assertEquals(ObdReadingStatus.TIMEOUT, readings.getValue(ObdMetric.ENGINE_RPM).status)
        assertNull(readings.getValue(ObdMetric.COOLANT_TEMPERATURE).value)
        assertNull(readings.getValue(ObdMetric.ENGINE_LOAD).ageMillis)
        assertNull(readings.getValue(ObdMetric.ENGINE_LOAD).status)
    }

    @Test fun stoppedAndFailedSessionsCannotPresentRetainedValidValuesAsCurrent() {
        for (phase in listOf(ObdPhase.STOPPED, ObdPhase.ERROR)) {
            val snapshot = singleReadingSnapshot(reading(ObdMetric.SPEED, 23.0)).copy(phase = phase, elapsedMillis = 3_000L)
            val model = dashboardModel(snapshot, emptySet())
            val reading = model.ecus.single().readings.single()
            assertTrue(model.stopped)
            assertEquals(ObdReadingStatus.STALE, reading.status)
            assertEquals(23.0, reading.value)
            assertEquals(2_000L, reading.ageMillis)
        }
    }

    @Test fun staleBoundaryUsesMonotonicAgeRegardlessOfWallTime() {
        val snapshot = singleReadingSnapshot(reading(ObdMetric.SPEED, 20.0).copy(observedAtEpochMillis = Long.MAX_VALUE))
        val current = dashboardModel(snapshot.copy(elapsedMillis = 5_999L), emptySet()).ecus.single().readings.single()
        val stale = dashboardModel(snapshot.copy(elapsedMillis = 6_000L), emptySet()).ecus.single().readings.single()
        assertEquals(ObdReadingStatus.VALID, current.status)
        assertEquals(ObdReadingStatus.STALE, stale.status)
        assertEquals(5_000L, stale.ageMillis)
        val invalidAge = dashboardModel(snapshot.copy(elapsedMillis = 0L), emptySet()).ecus.single().readings.single()
        assertNull(invalidAge.ageMillis)
        assertEquals(ObdReadingStatus.STALE, invalidAge.status)
    }

    @Test fun invalidNumericValuesAreNeverShownAsCurrentNumbers() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 256.0)) {
            val model = dashboardModel(singleReadingSnapshot(reading(ObdMetric.SPEED, value)), emptySet())
            assertNull(model.ecus.single().readings.single().value)
            assertEquals(ObdReadingStatus.NO_DATA, model.ecus.single().readings.single().status)
        }
    }

    @Test fun simulatorPreservesSyntheticValuesUnitsAndCapabilitiesWithoutInventedAdapterMetadata() {
        val source = DemoTelemetrySource()
        source.connect()
        val snapshot = simulatorDashboardSnapshot(source.advanceBy(1_000L))
        assertEquals(ObdPhase.LIVE, snapshot.phase)
        assertEquals("SIMULATOR", snapshot.ecus.single().ecuId)
        assertEquals(TelemetryMetric.entries.map { ObdMetric.valueOf(it.name).pid }.toSet(), snapshot.ecus.single().supportedPids)
        assertEquals(0.0, snapshot.readings.single { it.metric == ObdMetric.SPEED }.value)
        assertTrue(snapshot.readings.all { it.ecuId == "SIMULATOR" && it.observedAtEpochMillis == 0L })
        assertNull(snapshot.adapterIdentity)
        assertNull(snapshot.protocolLabel)
        assertEquals(1_000L, snapshot.elapsedMillis)
    }

    @Test fun simulatorFailuresAndDisconnectCannotBecomeZeroMeasurements() {
        val source = DemoTelemetrySource()
        source.connect()
        val timeout = simulatorDashboardSnapshot(source.setScenario(SimulationScenario.TIMEOUT))
        assertTrue(timeout.readings.all { it.status == ObdReadingStatus.TIMEOUT && it.value == null })
        val missing = simulatorDashboardSnapshot(source.setScenario(SimulationScenario.MISSING_DATA))
        assertEquals(ObdReadingStatus.NO_DATA, missing.readings.single { it.metric == ObdMetric.COOLANT_TEMPERATURE }.status)
        val disconnected = simulatorDashboardSnapshot(source.disconnect())
        assertEquals(ObdPhase.STOPPED, disconnected.phase)
        assertTrue(disconnected.readings.isEmpty())
        val model = dashboardModel(disconnected, emptySet())
        assertTrue(model.stopped)
        assertTrue(model.ecus.single().readings.all { it.value == null && it.ageMillis == null })
    }

    @Test fun retainedSyntheticReadingsAreStaleIfDisconnectedSnapshotContainsThem() {
        val source = DemoTelemetrySource()
        val retained = source.connect().copy(connection = ConnectionState.DISCONNECTED)
        val snapshot = simulatorDashboardSnapshot(retained)
        assertTrue(snapshot.readings.all { it.status == ObdReadingStatus.STALE })
    }

    @Test fun expandedFavoriteUsesItsOwnRangeAndFreshnessWithoutInventingSharedPidFields() {
        val metric = ObdMetric.CURRENT_OXYGEN_SENSOR_1_CURRENT
        val ratio = ObdMetric.CURRENT_OXYGEN_SENSOR_1_RATIO
        assertEquals(metric.pid, ratio.pid)
        val snapshot = singleReadingSnapshot(reading(metric, -3.25)).copy(elapsedMillis = 7_000L)
        val model = dashboardModel(snapshot, setOf(metric, ratio))
        assertTrue(model.availableExtras.containsAll(listOf(metric, ratio)))
        val readings = model.ecus.single().readings.associateBy { it.metric }
        assertEquals(-3.25, readings.getValue(metric).value)
        assertEquals(ObdReadingStatus.VALID, readings.getValue(metric).status)
        assertNull(readings.getValue(ratio).value)
        assertNull(readings.getValue(ratio).status)
        val stale = dashboardModel(snapshot.copy(elapsedMillis = 1_000L + metric.freshnessMillis), setOf(metric))
        assertEquals(ObdReadingStatus.STALE, stale.ecus.single().readings.single().status)
    }

    @Test fun duplicateSameSourceReadingsAreNotArbitrarilyChosen() {
        val snapshot = singleReadingSnapshot(reading(ObdMetric.SPEED, 10.0)).copy(
            readings = listOf(reading(ObdMetric.SPEED, 10.0), reading(ObdMetric.SPEED, 20.0)),
        )
        val reading = dashboardModel(snapshot, emptySet()).ecus.single().readings.single()
        assertNull(reading.value)
        assertNull(reading.status)
        assertNull(reading.ageMillis)
    }

    private fun capabilities(ecu: String, vararg metrics: ObdMetric) = ObdEcuCapabilities(ecu, metrics.map { it.pid }.toSet())
    private fun reading(metric: ObdMetric, value: Double?, status: ObdReadingStatus = ObdReadingStatus.VALID, ecu: String = "7E8") =
        ObdReading(metric, value, status, ecu, 123_000L, 1_000L)
    private fun singleReadingSnapshot(reading: ObdReading) = ObdSessionSnapshot(
        phase = ObdPhase.LIVE,
        ecus = listOf(capabilities(reading.ecuId, reading.metric)),
        readings = listOf(reading),
        elapsedMillis = 1_000L,
    )
}
