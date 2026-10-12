package com.gonibix.rx8.connection

import android.annotation.SuppressLint
import android.content.Context
import com.gonibix.rx8.bluetooth.AdapterDevice
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private opt-in connection preference, deliberately independent of vehicle/profile storage. */
data class FavoriteAdapterPreference(val adapter: AdapterDevice? = null, val paused: Boolean = false)

interface FavoriteAdapterStore {
    suspend fun load(): FavoriteAdapterPreference
    suspend fun save(preference: FavoriteAdapterPreference)
}

internal class PrivateFavoriteAdapterStore(context: Context) : FavoriteAdapterStore {
    private val preferences = context.applicationContext.getSharedPreferences("favorite_adapter", Context.MODE_PRIVATE)
    private val writes = Mutex()

    override suspend fun load(): FavoriteAdapterPreference = writes.withLock { withContext(Dispatchers.IO) {
        val address = normalizedAdapterAddress(preferences.getString("address", null))
        FavoriteAdapterPreference(
            address?.let { AdapterDevice(it, preferences.getString("name", null)?.take(120).orEmpty(), isPaired = false) },
            preferences.getBoolean("paused", false),
        )
    } }

    @SuppressLint("ApplySharedPref", "UseKtx") // Commit on IO before publishing persistence success.
    override suspend fun save(preference: FavoriteAdapterPreference) = writes.withLock {
        val adapter = preference.adapter
        val address = adapter?.let { requireNotNull(normalizedAdapterAddress(it.address)) }
        withContext(Dispatchers.IO) {
            if (!preferences.edit().putString("address", address)
                    .putString("name", adapter?.name?.take(120))
                    .putBoolean("paused", preference.paused).commit()) {
                throw IOException("Favorite adapter preference could not be saved")
            }
        }
    }
}
