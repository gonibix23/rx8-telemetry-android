package com.gonibix.rx8.dashboard

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Dedicated file: no vehicle identifiers or telemetry are stored in these preferences. */
internal class SharedPreferencesFavoritesStorage(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : DashboardFavoritesStorage {
    private val application = context.applicationContext
    private val preferences by lazy { application.getSharedPreferences(preferencesName, Context.MODE_PRIVATE) }

    override suspend fun read(): Set<String> = withContext(Dispatchers.IO) {
        // Never expose or mutate Android's shared Set instance.
        preferences.getStringSet(FAVORITES_KEY, emptySet()).orEmpty().toSet()
    }

    @SuppressLint("ApplySharedPref", "UseKtx") // IO commit result is required before publishing durable favorites.
    override suspend fun write(ids: Set<String>): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putStringSet(FAVORITES_KEY, ids.toSet()).commit()
    }

    override suspend fun readCards(): List<DashboardCardConfig> = withContext(Dispatchers.IO) {
        if (preferences.contains(LAYOUT_KEY)) DashboardLayoutCodec.decode(preferences.getString(LAYOUT_KEY, "").orEmpty())
        else (primaryDashboardMetrics + FavoriteMetricIds.decode(preferences.getStringSet(FAVORITES_KEY, emptySet()).orEmpty()).toList())
            .distinct().map(::defaultCard)
    }

    override suspend fun readColumns(): DashboardColumns = withContext(Dispatchers.IO) {
        DashboardColumns.entries.firstOrNull { it.name == preferences.getString(COLUMNS_KEY, null) } ?: DashboardColumns.ONE
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override suspend fun writeColumns(columns: DashboardColumns): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString(COLUMNS_KEY, columns.name).commit()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString(LAYOUT_KEY, DashboardLayoutCodec.encode(cards))
            .putStringSet(FAVORITES_KEY, FavoriteMetricIds.encode(cards.map { it.metric }.toSet())).commit()
    }

    internal companion object {
        const val PREFERENCES_NAME = "dashboard_preferences"
        const val LAYOUT_KEY = "dashboard_layout_v2"
        const val FAVORITES_KEY = "favorite_metric_ids_v1"
        const val COLUMNS_KEY = "dashboard_columns_v1"
    }
}

/** Process lifetime prevents Activity/ViewModel clearing from cancelling an accepted preference write. */
internal object DashboardFavoritesProvider {
    @Volatile private var instance: DashboardFavoritesStore? = null

    fun open(context: Context): DashboardFavoritesStore = instance ?: synchronized(this) {
        instance ?: DashboardFavoritesStore(
            SharedPreferencesFavoritesStorage(context.applicationContext),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { instance = it }
    }
}

/** Shared read-only layout for the existing session service; observing it never starts acquisition. */
object DashboardPreferences {
    fun openCards(context: Context): kotlinx.coroutines.flow.StateFlow<List<DashboardCardConfig>> = DashboardFavoritesProvider.open(context).cards
}
