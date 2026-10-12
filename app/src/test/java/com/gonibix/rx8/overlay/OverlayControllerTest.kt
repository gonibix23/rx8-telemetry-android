package com.gonibix.rx8.overlay

import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import org.junit.Assert.*
import org.junit.Test

class OverlayControllerTest {
    private var now = 1_000L
    private val host = Host()
    private val controller = OverlayController(host) { now }
    private val cards = listOf(DashboardCardConfig(ObdMetric.SPEED, showInOverlay = true))
    private val snapshot = ObdSessionSnapshot(phase = ObdPhase.LIVE, elapsedMillis = 1_000,
        ecus = listOf(ObdEcuCapabilities("7E8", setOf(13))),
        readings = listOf(ObdReading(ObdMetric.SPEED, 0.0, ObdReadingStatus.VALID, "7E8", 123, 1_000)))

    @Test fun enablingNeedsExplicitPermissionConnectionAndSelectionAndNeverAutoResumes() {
        assertFalse(controller.setEnabled(true))
        host.permission = true
        assertFalse(controller.setEnabled(true))
        controller.update(snapshot, true, emptyList())
        assertFalse(controller.setEnabled(true))
        controller.update(snapshot, true, cards)
        assertFalse(controller.state.value.enabled)
        assertTrue(controller.setEnabled(true))
    }
    @Test fun visibleAppAndLockedScreenHideWithoutLosingTheExplicitSessionChoice() {
        enable()
        assertFalse(controller.state.value.visible)
        controller.setAppVisible(false)
        assertTrue(controller.state.value.visible)
        controller.setScreenAvailable(false)
        assertFalse(controller.state.value.visible)
        assertTrue(controller.state.value.enabled)
        controller.setScreenAvailable(true)
        assertTrue(controller.state.value.visible)
        controller.setAppVisible(true)
        assertFalse(controller.state.value.visible)
    }
    @Test fun agesAdvanceWithoutIncomingSnapshotsAndRetainedZeroBecomesExplicitlyStale() {
        enable(); controller.setAppVisible(false)
        assertEquals(0.0, host.rows.single().reading.value)
        now += 5_000
        controller.refresh()
        assertEquals(ObdReadingStatus.STALE, host.rows.single().reading.status)
        assertEquals(5_000L, host.rows.single().reading.ageMillis)
        assertEquals(0.0, host.rows.single().reading.value)
    }
    @Test fun preferenceAndStatusOnlyUpdatesNeverRejuvenateAStalledReading() {
        enable(); controller.setAppVisible(false)
        now += 6_000
        controller.refresh()
        controller.update(snapshot, true, cards.map { it.copy(size = com.gonibix.rx8.dashboard.DashboardCardSize.LARGE) })
        assertEquals(ObdReadingStatus.STALE, host.rows.single().reading.status)
        assertEquals(6_000L, host.rows.single().reading.ageMillis)
        now += 1_000
        controller.update(snapshot.copy(statusMessage = "A status update"), true, cards)
        assertEquals(7_000L, host.rows.single().reading.ageMillis)
        assertEquals(ObdReadingStatus.STALE, host.rows.single().reading.status)
    }
    @Test fun permissionLossClosesAndDoesNotReopenWhenPermissionReturns() {
        enable(); controller.setAppVisible(false)
        host.permission = false; controller.refresh()
        assertFalse(controller.state.value.enabled)
        assertFalse(host.shown)
        assertNotNull(controller.state.value.error)
        host.permission = true; controller.refresh()
        assertFalse(host.shown)
    }
    @Test fun disconnectAndRemovingLastOverlaySensorCloseThePanelWithoutStoppingOtherOwners() {
        enable(); controller.setAppVisible(false)
        controller.update(snapshot, false, cards)
        assertFalse(host.shown)
        controller.update(snapshot, true, cards)
        assertFalse(controller.state.value.enabled)
        assertTrue(controller.setEnabled(true))
        controller.update(snapshot, true, emptyList())
        assertFalse(host.shown)
        assertFalse(controller.state.value.enabled)
    }
    @Test fun hostFailureAndIdempotentDestroyReleaseWindowAndRejectLateUpdates() {
        enable(); host.fail = true; controller.setAppVisible(false)
        assertFalse(controller.state.value.enabled)
        assertNotNull(controller.state.value.error)
        host.fail = false; controller.setEnabled(true)
        controller.close(); controller.close()
        controller.update(snapshot, true, cards); controller.setAppVisible(false)
        assertFalse(controller.setEnabled(true))
        assertFalse(host.shown)
    }
    @Test fun failuresAndUnsupportedSensorsHaveNoNumericValueAndSourcesNeverMerge() {
        val multi = snapshot.copy(ecus = listOf(ObdEcuCapabilities("7E8", setOf(13)), ObdEcuCapabilities("7E9", emptySet())),
            readings = listOf(snapshot.readings.single().copy(status = ObdReadingStatus.TIMEOUT)))
        host.permission = true; controller.update(multi, true, cards); controller.setEnabled(true); controller.setAppVisible(false)
        assertEquals(listOf("7E8", "7E9"), host.rows.map { it.reading.ecuId })
        assertTrue(host.rows.all { it.reading.value == null })
        assertEquals(listOf(ObdReadingStatus.TIMEOUT, ObdReadingStatus.UNSUPPORTED), host.rows.map { it.reading.status })
    }
    private fun enable() { host.permission = true; controller.update(snapshot, true, cards); assertTrue(controller.setEnabled(true)) }
    private class Host : OverlayHost {
        var permission = false
        var shown = false
        var fail = false
        var rows = emptyList<OverlayRow>()
        override fun permissionGranted() = permission
        override fun show(rows: List<OverlayRow>) { if (fail) error("Synthetic failure"); shown = true; this.rows = rows }
        override fun hide() { shown = false; rows = emptyList() }
    }
}
