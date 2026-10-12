package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardLayoutTest {
    @Test fun codecPreservesOrderStyleSizeEmptyAndRejectsDuplicateUnknownOrExcessOverlayIds() {
        val cards = ObdMetric.entries.map { DashboardCardConfig(it, DashboardCardStyle.BAR, DashboardCardSize.LARGE, true) }
        val decoded = DashboardLayoutCodec.decode(DashboardLayoutCodec.encode(cards) + "\nUNKNOWN:DIAL:LARGE:1\nSPEED:DIGITAL:COMPACT:0")
        assertEquals(120, decoded.size)
        assertEquals(cards.map { it.metric }, decoded.map { it.metric })
        assertEquals(6, decoded.count { it.showInOverlay })
        assertTrue(decoded.all { it.style == DashboardCardStyle.BAR && it.size == DashboardCardSize.LARGE })
        assertTrue(DashboardLayoutCodec.decode("").isEmpty())
        assertEquals(DashboardCardStyle.DIGITAL, DashboardLayoutCodec.decode("SPEED:future:future:0").single().style)
    }

    @Test fun selectedBasicsCanBeAbsentWhileUnsupportedSelectionIsExplicitAndEcusStaySeparate() {
        val snapshot = ObdSessionSnapshot(phase = ObdPhase.LIVE, elapsedMillis = 1_000,
            ecus = listOf(ObdEcuCapabilities("7E8", setOf(13)), ObdEcuCapabilities("7E9", setOf(13))),
            readings = listOf(ObdReading(ObdMetric.SPEED, 0.0, ObdReadingStatus.VALID, "7E8", 10_000, 1_000),
                ObdReading(ObdMetric.SPEED, 20.0, ObdReadingStatus.VALID, "7E9", 10_000, 1_000)))
        val model = modularDashboardModel(snapshot, listOf(DashboardCardConfig(ObdMetric.SPEED), DashboardCardConfig(ObdMetric.ENGINE_RPM)))
        assertEquals(listOf(0.0, 20.0), model.ecus.map { it.readings.first().value })
        assertTrue(model.ecus.all { it.readings.last().status == ObdReadingStatus.UNSUPPORTED && it.readings.last().value == null })
        assertTrue(modularDashboardModel(snapshot, emptyList()).ecus.all { it.readings.isEmpty() })
        assertTrue(model.ecus.none { ecu -> ecu.readings.any { it.metric == ObdMetric.COOLANT_TEMPERATURE } })
    }

    @Test fun unknownCapabilitiesPermitOfflineCustomizationWithoutInventedValuesOrSupport() {
        val model = modularDashboardModel(ObdSessionSnapshot(), listOf(DashboardCardConfig(ObdMetric.SPEED)))
        assertFalse(model.hasCapabilities)
        assertNull(model.ecus.single().readings.single().value)
        assertNull(model.ecus.single().readings.single().status)
        assertEquals("Unknown", model.ecus.single().ecuId)
    }

    @Test fun styleChangesReorderingAndRemovalShareOneDurableOrderedQueue() = runTest {
        val storage = LayoutStorage(primaryDashboardMetrics.map(::defaultCard))
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        val speed = DashboardCardConfig(ObdMetric.SPEED, DashboardCardStyle.BAR, DashboardCardSize.LARGE, true)
        store.updateCard(speed)
        store.moveCard(ObdMetric.SPEED, -1)
        store.toggleFavorite(ObdMetric.ENGINE_RPM)
        runCurrent()
        assertEquals(listOf(speed, defaultCard(ObdMetric.COOLANT_TEMPERATURE)), storage.saved)
        assertEquals(storage.saved, store.cards.value)
        storage.success = false
        store.updateCard(speed.copy(style = DashboardCardStyle.DIAL))
        runCurrent()
        assertEquals(speed, store.cards.value.first())
        assertNotNull(store.error.value)
    }

    @Test fun seventhOverlayChoiceIsRejectedWithoutDroppingTheExistingSix() = runTest {
        val initial = ObdMetric.entries.take(7).mapIndexed { index, metric -> DashboardCardConfig(metric, showInOverlay = index < 6) }
        val storage = LayoutStorage(initial)
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        store.updateCard(initial.last().copy(showInOverlay = true))
        runCurrent()
        assertEquals(initial, store.cards.value)
        assertNotNull(store.error.value)
        assertEquals(0, storage.writes)
    }

    @Test fun canonicalSignedRangesDoNotTurnInvalidValuesIntoGaugeProgress() {
        val metric = ObdMetric.COOLANT_TEMPERATURE
        val negative = DashboardReading(metric, "7E8", -20.0, ObdReadingStatus.VALID, 0, true)
        assertTrue(metricProgress(negative)!! > 0f)
        assertNull(metricProgress(negative.copy(value = Double.NaN)))
        assertNull(metricProgress(negative.copy(value = null)))
        assertEquals(0f, metricProgress(negative.copy(value = metric.minimumValue)))
    }

    private class LayoutStorage(var saved: List<DashboardCardConfig>) : DashboardFavoritesStorage {
        var success = true
        var writes = 0
        override suspend fun read() = emptySet<String>()
        override suspend fun write(ids: Set<String>) = error("Layout storage required")
        override suspend fun readCards() = saved
        override suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean {
            writes++
            if (success) saved = cards.toList()
            return success
        }
    }
}
