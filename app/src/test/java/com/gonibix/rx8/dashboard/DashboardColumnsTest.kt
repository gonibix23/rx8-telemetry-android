package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdMetric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardColumnsTest {
    @Test fun earlyColumnChangesWaitForLoadingAndRetainEveryExistingCardPreference() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val disk = Storage().apply { beforeRead = { loaded.await() } }
        val store = DashboardFavoritesStore(disk, backgroundScope)
        store.setColumns(DashboardColumns.TWO)
        store.moveCard(ObdMetric.SPEED, -1)
        runCurrent()
        assertEquals(DashboardColumns.ONE, store.columns.value)
        assertTrue(store.isSaving.value)
        loaded.complete(Unit)
        runCurrent()
        assertEquals(DashboardColumns.TWO, store.columns.value)
        assertEquals(disk.cards, store.cards.value)
        assertEquals(ObdMetric.SPEED, store.cards.value.first().metric)
        assertTrue(store.cards.value.first().showInOverlay)
        val reopened = DashboardFavoritesStore(disk, backgroundScope)
        runCurrent()
        assertEquals(store.columns.value, reopened.columns.value)
        assertEquals(store.cards.value, reopened.cards.value)
    }

    @Test fun failedColumnCommitKeepsConfirmedLayoutAndNextCardChangeDoesNotOverwriteIt() = runTest {
        val disk = Storage().apply { success = false }
        val store = DashboardFavoritesStore(disk, backgroundScope)
        runCurrent()
        store.setColumns(DashboardColumns.TWO)
        runCurrent()
        assertEquals(DashboardColumns.ONE, store.columns.value)
        assertEquals(DashboardColumns.ONE, disk.columns)
        assertNotNull(store.error.value)
        disk.success = true
        store.setColumns(DashboardColumns.TWO)
        store.toggleFavorite(ObdMetric.COOLANT_TEMPERATURE)
        runCurrent()
        assertEquals(DashboardColumns.TWO, disk.columns)
        assertEquals(DashboardColumns.TWO, store.columns.value)
        assertNull(store.error.value)
        assertFalse(store.isSaving.value)
    }

    @Test fun rapidColumnSelectionsPublishOnlyAfterDurableCommitAndKeepQueueOrder() = runTest {
        val release = CompletableDeferred<Unit>()
        val disk = Storage().apply { beforeWrite = { release.await() } }
        val store = DashboardFavoritesStore(disk, backgroundScope)
        runCurrent()
        store.setColumns(DashboardColumns.TWO)
        store.setColumns(DashboardColumns.ONE)
        runCurrent()
        assertEquals(DashboardColumns.ONE, store.columns.value)
        assertEquals(listOf(DashboardColumns.TWO), disk.writes)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(DashboardColumns.TWO, DashboardColumns.ONE), disk.writes)
        assertEquals(DashboardColumns.ONE, store.columns.value)
        assertFalse(store.isSaving.value)
    }

    private class Storage : DashboardFavoritesStorage {
        var cards = listOf(defaultCard(ObdMetric.ENGINE_RPM), DashboardCardConfig(ObdMetric.SPEED, DashboardCardStyle.BAR, showInOverlay = true))
        var columns = DashboardColumns.ONE
        var success = true
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        val writes = mutableListOf<DashboardColumns>()
        override suspend fun read() = emptySet<String>()
        override suspend fun write(ids: Set<String>) = error("Use card preferences")
        override suspend fun readCards(): List<DashboardCardConfig> { beforeRead(); return cards }
        override suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean {
            if (success) this.cards = cards
            return success
        }
        override suspend fun readColumns() = columns
        override suspend fun writeColumns(columns: DashboardColumns): Boolean {
            writes += columns
            beforeWrite()
            if (success) this.columns = columns
            return success
        }
    }
}
