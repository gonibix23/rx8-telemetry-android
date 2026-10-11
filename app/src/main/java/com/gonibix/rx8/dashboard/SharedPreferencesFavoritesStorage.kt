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

    internal companion object {
        const val PREFERENCES_NAME = "dashboard_preferences"
        const val FAVORITES_KEY = "favorite_metric_ids_v1"
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
