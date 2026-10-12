package com.gonibix.rx8.dashboard

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses private test preference files; never modifies the user's dashboard settings. */
@SuppressLint("ApplySharedPref", "UseKtx") // Fixture commits need their actual durable result.
@RunWith(AndroidJUnit4::class)
class DashboardPreferencesPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val names = mutableListOf<String>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        names.forEach { application.deleteSharedPreferences(it) }
    }

    @Test
    fun favoritesAndExplicitEmptySelectionSurviveReloadFromCommittedBytes() = runBlocking {
        val name = uniqueName()
        val store = open(name)
        awaitSettled(store)
        assertEquals(primaryDashboardMetrics.toSet(), store.favorites.value)
        primaryDashboardMetrics.forEach(store::toggleFavorite)
        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        awaitSettled(store)
        val reopened = open(copyCommittedFile(name))
        awaitSettled(reopened)
        assertEquals(setOf(ObdMetric.INTAKE_TEMPERATURE, ObdMetric.ENGINE_LOAD), reopened.favorites.value)

        store.toggleFavorite(ObdMetric.INTAKE_TEMPERATURE)
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        awaitSettled(store)
        val cleared = open(copyCommittedFile(name))
        awaitSettled(cleared)
        assertTrue(cleared.favorites.value.isEmpty())
        assertEquals(
            mapOf(SharedPreferencesFavoritesStorage.FAVORITES_KEY to emptySet<String>(), SharedPreferencesFavoritesStorage.LAYOUT_KEY to ""),
            application.getSharedPreferences(name, Context.MODE_PRIVATE).all,
        )
    }

    @Test
    fun legacyFavoritesMigrateWithBasicCardsAndBasicsCanThenBeRemoved() = runBlocking {
        val name = uniqueName()
        withContext(Dispatchers.IO) {
            assertTrue(application.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putStringSet(
                SharedPreferencesFavoritesStorage.FAVORITES_KEY,
                setOf("engine_load", "unknown_future_sensor", "engine_rpm", "speed", "coolant_temperature"),
            ).commit())
        }
        val store = open(name)
        awaitSettled(store)
        assertEquals(primaryDashboardMetrics.toSet() + ObdMetric.ENGINE_LOAD, store.favorites.value)
        store.toggleFavorite(ObdMetric.SPEED)
        store.toggleFavorite(ObdMetric.THROTTLE_POSITION)
        awaitSettled(store)
        val reopened = open(copyCommittedFile(name))
        awaitSettled(reopened)
        assertEquals(setOf(ObdMetric.ENGINE_RPM, ObdMetric.COOLANT_TEMPERATURE, ObdMetric.ENGINE_LOAD, ObdMetric.THROTTLE_POSITION), reopened.favorites.value)
        assertEquals(
            setOf("engine_rpm", "coolant_temperature", "engine_load", "throttle_position"),
            application.getSharedPreferences(name, Context.MODE_PRIVATE)
                .getStringSet(SharedPreferencesFavoritesStorage.FAVORITES_KEY, null),
        )
    }

    @Test
    fun malformedPreferenceTypeReportsFailureWithoutReplacingStoredData() = runBlocking {
        val name = uniqueName()
        withContext(Dispatchers.IO) {
            assertTrue(application.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putString(SharedPreferencesFavoritesStorage.FAVORITES_KEY, "wrong_type").commit())
        }
        val store = open(name)
        awaitSettled(store)
        assertNotNull(store.error.value)
        store.toggleFavorite(ObdMetric.ENGINE_LOAD)
        awaitSettled(store)
        assertTrue(store.favorites.value.isEmpty())
        assertNotNull(store.error.value)
        assertEquals(
            "wrong_type",
            application.getSharedPreferences(name, Context.MODE_PRIVATE)
                .getString(SharedPreferencesFavoritesStorage.FAVORITES_KEY, null),
        )
    }

    @Test
    fun clearingAViewModelDoesNotCancelItsQueuedWriteAndNewObserverSharesTheResult() = runBlocking {
        val name = uniqueName()
        val disk = SharedPreferencesFavoritesStorage(application, name)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : DashboardFavoritesStorage {
            override suspend fun read() = disk.read()
            override suspend fun readCards() = disk.readCards()
            override suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean {
                entered.complete(Unit)
                release.await()
                return disk.writeCards(cards)
            }
            override suspend fun write(ids: Set<String>): Boolean {
                entered.complete(Unit)
                release.await()
                return disk.write(ids)
            }
        }
        val store = DashboardFavoritesStore(delayed, newScope())
        awaitSettled(store)
        val first = DashboardPreferencesViewModel(application, store)
        val firstOwner = ViewModelStore()
        instrumentation.runOnMainSync {
            ViewModelProvider(firstOwner, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(first))
            })[DashboardPreferencesViewModel::class.java]
            first.toggleFavorite(ObdMetric.ENGINE_LOAD)
        }
        withTimeout(5_000L) { entered.await() }
        assertTrue(first.isSaving.value)
        assertEquals(primaryDashboardMetrics.toSet(), first.favorites.value)
        instrumentation.runOnMainSync { firstOwner.clear() }
        val replacement = DashboardPreferencesViewModel(application, store)
        assertTrue(replacement.isSaving.value)
        release.complete(Unit)
        awaitSettled(store)
        assertEquals(primaryDashboardMetrics.toSet() + ObdMetric.ENGINE_LOAD, replacement.favorites.value)
        assertFalse(replacement.isSaving.value)
        val coldRead = open(copyCommittedFile(name))
        awaitSettled(coldRead)
        assertEquals(replacement.favorites.value, coldRead.favorites.value)
    }

    @Test fun cardOrderAppearanceAndOverlaySelectionSurviveCommittedFileReopen() = runBlocking {
        val name = uniqueName()
        val store = open(name)
        awaitSettled(store)
        store.updateCard(DashboardCardConfig(ObdMetric.SPEED, DashboardCardStyle.BAR, DashboardCardSize.LARGE, true))
        store.moveCard(ObdMetric.SPEED, -1)
        store.setColumns(DashboardColumns.TWO)
        awaitSettled(store)
        val reopened = open(copyCommittedFile(name))
        awaitSettled(reopened)
        assertEquals(store.cards.value, reopened.cards.value)
        assertEquals(DashboardColumns.TWO, reopened.columns.value)
        assertEquals(ObdMetric.SPEED, reopened.cards.value.first().metric)
        assertTrue(reopened.cards.value.first().showInOverlay)
        assertEquals(DashboardCardStyle.BAR, reopened.cards.value.first().style)
    }

    private fun uniqueName(): String = "dashboard-test-${UUID.randomUUID()}".also { names += it }
    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
    private fun open(name: String) = DashboardFavoritesStore(SharedPreferencesFavoritesStorage(application, name), newScope())

    private suspend fun awaitSettled(store: DashboardFavoritesStore) = withTimeout(5_000L) {
        combine(store.isLoading, store.isSaving) { loading, saving -> !loading && !saving }.first { it }
    }

    private suspend fun copyCommittedFile(name: String): String = withContext(Dispatchers.IO) {
        val reopenedName = uniqueName()
        // A fresh filename avoids Android's process-wide SharedPreferences cache and reads the actual commit.
        val directory = File(application.applicationInfo.dataDir, "shared_prefs")
        val committed = File(directory, "$name.xml")
        assertTrue("A successful commit must have a durable preference file", committed.isFile)
        committed.copyTo(File(directory, "$reopenedName.xml"))
        reopenedName
    }
}


