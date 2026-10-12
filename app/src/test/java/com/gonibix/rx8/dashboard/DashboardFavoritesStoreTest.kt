package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdMetric
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFavoritesStoreTest {
    @Test
    fun defaultsAreEmptyAndNeverCreateAStoredSelectionUntilRequested() = runTest {
        val storage = MemoryStorage()
        val store = DashboardFavoritesStore(storage, backgroundScope)
        assertTrue(store.isLoading.value)
        runCurrent()
        assertFalse(store.isLoading.value)
        assertEquals(emptySet<ObdMetric>(), store.favorites.value)
        assertTrue(storage.writes.isEmpty())
        assertNull(store.error.value)
    }

    @Test
    fun unknownIdsAreIgnoredAndAllBasicMetricsCanBeAddedOrRemoved() = runTest {
        val storage = MemoryStorage(setOf("engine_load", "future_metric", "engine_rpm", "SPEED"))
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD, ObdMetric.ENGINE_RPM), store.favorites.value)
        primaryDashboardMetrics.forEach(store::toggleFavorite)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD, ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE), store.favorites.value)
        assertEquals(3, storage.writes.size)
    }

    @Test
    fun stableIdsRoundTripAllSelectableMetricsAndPreserveAnExplicitEmptySelection() = runTest {
        val storage = MemoryStorage()
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        val extras = setOf(ObdMetric.INTAKE_TEMPERATURE, ObdMetric.THROTTLE_POSITION, ObdMetric.ENGINE_LOAD)
        extras.forEach(store::toggleFavorite)
        runCurrent()
        assertEquals(extras, store.favorites.value)
        assertEquals(setOf("intake_temperature", "throttle_position", "engine_load"), storage.saved)
        val reopened = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        assertEquals(extras, reopened.favorites.value)
        extras.forEach(reopened::toggleFavorite)
        runCurrent()
        assertTrue(reopened.favorites.value.isEmpty())
        assertTrue(storage.saved.isEmpty())
        val emptyReopened = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        assertTrue(emptyReopened.favorites.value.isEmpty())
    }

    @Test
    fun earlyTogglesWaitForStoredFavoritesInsteadOfOverwritingThemWithTheEmptyDefault() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val storage = MemoryStorage(setOf("engine_load")).apply { beforeRead = { loaded.await() } }
        val store = DashboardFavoritesStore(storage, backgroundScope)
        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        runCurrent()
        assertTrue(store.isLoading.value)
        assertTrue(store.isSaving.value)
        assertTrue(storage.writes.isEmpty())
        loaded.complete(Unit)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD, ObdMetric.INTAKE_TEMPERATURE), store.favorites.value)
        assertFalse(store.isSaving.value)
    }

    @Test
    fun rapidTogglesAreSerializedAndNotPublishedUntilTheirCommitSucceeds() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val storage = MemoryStorage().apply { beforeWrite = { if (writes.size == 1) firstWrite.await() } }
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        store.toggleFavorite(ObdMetric.THROTTLE_POSITION)
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        runCurrent()
        assertEquals(listOf(setOf("engine_load")), storage.writes)
        assertTrue(store.favorites.value.isEmpty())
        assertTrue(store.isSaving.value)
        firstWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(
            setOf("engine_load"),
            setOf("engine_load", "throttle_position"),
            setOf("throttle_position"),
        ), storage.writes)
        assertEquals(setOf(ObdMetric.THROTTLE_POSITION), store.favorites.value)
        assertFalse(store.isSaving.value)
        assertEquals(1, storage.maximumConcurrentWrites)
    }

    @Test
    fun unsuccessfulCommitRetainsConfirmedSelectionAndLaterToggleCanRetry() = runTest {
        val storage = MemoryStorage(setOf("engine_load")).apply { writeSucceeds = false }
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD), store.favorites.value)
        assertEquals(setOf("engine_load"), storage.saved)
        assertNotNull(store.error.value)
        assertFalse(store.isSaving.value)
        storage.writeSucceeds = true
        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD, ObdMetric.INTAKE_TEMPERATURE), store.favorites.value)
        assertNull(store.error.value)
    }

    @Test
    fun storageExceptionsDoNotKillTheSaveQueueOrExposeRawFailureDetails() = runTest {
        val storage = MemoryStorage().apply { beforeWrite = { throw IOException("private filesystem detail") } }
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        runCurrent()
        assertTrue(store.favorites.value.isEmpty())
        assertNotNull(store.error.value)
        assertFalse(store.error.value!!.contains("private filesystem detail"))
        storage.beforeWrite = {}
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        runCurrent()
        assertEquals(setOf(ObdMetric.ENGINE_LOAD), store.favorites.value)
        assertNull(store.error.value)
    }

    @Test
    fun failedLoadNeverOverwritesUnknownSavedStateAndLaterActionReloadsBeforeChangingIt() = runTest {
        val storage = MemoryStorage(setOf("throttle_position")).apply { beforeRead = { throw IOException("read failed") } }
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        runCurrent()
        assertTrue(storage.writes.isEmpty())
        assertNotNull(store.error.value)
        assertFalse(store.isLoading.value)
        assertFalse(store.isSaving.value)
        storage.beforeRead = {}
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        runCurrent()
        assertEquals(setOf(ObdMetric.THROTTLE_POSITION, ObdMetric.ENGINE_LOAD), store.favorites.value)
        assertNull(store.error.value)
    }

    @Test
    fun separateObserversShareOneOrderedSelectionWithoutLosingEachOthersChanges() = runTest {
        val storage = MemoryStorage()
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        val firstObserver = store.favorites
        val secondObserver = store.favorites
        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        store.toggleFavorite(ObdMetric.THROTTLE_POSITION)
        runCurrent()
        val expected = setOf(ObdMetric.INTAKE_TEMPERATURE, ObdMetric.THROTTLE_POSITION)
        assertEquals(expected, firstObserver.value)
        assertEquals(expected, secondObserver.value)
        assertEquals(setOf("intake_temperature", "throttle_position"), storage.saved)
    }

    @Test
    fun expandedFavoriteIdsReopenWithoutLosingLegacySelectionsOrCombiningOxygenChannels() = runTest {
        val storage = MemoryStorage(setOf("intake_temperature", "throttle_position", "engine_load"))
        val store = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        val additions = setOf(ObdMetric.MASS_AIR_FLOW, ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO, ObdMetric.WIDE_OXYGEN_SENSOR_1_VOLTAGE)
        additions.forEach(store::toggleFavorite)
        runCurrent()
        assertEquals(setOf("intake_temperature", "throttle_position", "engine_load", "mass_air_flow",
            "wide_oxygen_sensor_1_ratio", "wide_oxygen_sensor_1_voltage"), storage.saved)
        val reopened = DashboardFavoritesStore(storage, backgroundScope)
        runCurrent()
        assertEquals(store.favorites.value, reopened.favorites.value)
        reopened.toggleFavorite(ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO)
        runCurrent()
        assertFalse(ObdMetric.WIDE_OXYGEN_SENSOR_1_RATIO in reopened.favorites.value)
        assertTrue(ObdMetric.WIDE_OXYGEN_SENSOR_1_VOLTAGE in reopened.favorites.value)
    }

    @Test
    fun allNewScalarChannelsHaveUniqueStableFavoriteIds() {
        val selectable = ObdMetric.entries.toSet()
        val encoded = FavoriteMetricIds.encode(selectable)
        assertEquals(120, encoded.size)
        assertEquals(selectable, FavoriteMetricIds.decode(encoded + "unknown_future_metric"))
        assertEquals(emptySet<ObdMetric>(), FavoriteMetricIds.decode(emptySet()))
    }

    private class MemoryStorage(initial: Set<String> = emptySet()) : DashboardFavoritesStorage {
        var saved = initial
        val writes = mutableListOf<Set<String>>()
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var writeSucceeds = true
        private var concurrentWrites = 0
        var maximumConcurrentWrites = 0
        override suspend fun read(): Set<String> { beforeRead(); return saved }
        override suspend fun write(ids: Set<String>): Boolean {
            concurrentWrites++
            maximumConcurrentWrites = maxOf(maximumConcurrentWrites, concurrentWrites)
            try {
                writes += ids
                beforeWrite()
                if (writeSucceeds) saved = ids.toSet()
                return writeSucceeds
            } finally { concurrentWrites-- }
        }
    }
}
