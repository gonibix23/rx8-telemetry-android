package com.gonibix.rx8.connection

import android.content.Context
import android.annotation.SuppressLint
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A local connection preference, never a vehicle identifier or part of an export. */
interface LastAdapterStore {
    suspend fun loadAddress(): String?
    suspend fun saveAddress(address: String)
}

internal class PrivateLastAdapterStore(context: Context) : LastAdapterStore {
    private val preferences = context.applicationContext.getSharedPreferences("last_adapter", Context.MODE_PRIVATE)
    private val writes = Mutex()

    override suspend fun loadAddress(): String? = withContext(Dispatchers.IO) {
        normalizedAdapterAddress(preferences.getString("successful_classic_address", null))
    }

    @SuppressLint("ApplySharedPref", "UseKtx") // Persist on IO and inspect commit success before claiming the adapter was remembered.
    override suspend fun saveAddress(address: String) = writes.withLock {
        val normalized = requireNotNull(normalizedAdapterAddress(address))
        withContext(Dispatchers.IO) {
            if (!preferences.edit().putString("successful_classic_address", normalized).commit()) {
                throw IOException("Adapter preference could not be saved")
            }
        }
    }

}

internal fun normalizedAdapterAddress(address: String?): String? =
    address?.takeIf { Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(it) }?.uppercase(Locale.ROOT)

internal object AutomaticConnectionRuntime {
    val policy = AutomaticConnectionPolicy()
    // Saving a successful adapter must survive an immediate Disconnect/service unbind.
    val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var preferences: LastAdapterStore? = null

    fun lastAdapter(context: Context): LastAdapterStore = preferences ?: synchronized(this) {
        preferences ?: PrivateLastAdapterStore(context).also { preferences = it }
    }
}
