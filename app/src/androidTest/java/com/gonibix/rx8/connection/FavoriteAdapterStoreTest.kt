package com.gonibix.rx8.connection

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.bluetooth.AdapterDevice
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteAdapterStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "test_favorite_${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(filename, mode)
    }
    @After fun cleanup() { base.deleteSharedPreferences(filename) }

    @Test fun favoriteAndExplicitPausePersistIndependentlyOfAnyVehicle() = runBlocking {
        val adapter = AdapterDevice("02:aa:bb:cc:dd:42", "Synthetic favorite")
        assertNull(PrivateFavoriteAdapterStore(isolated).load().adapter)
        PrivateFavoriteAdapterStore(isolated).save(FavoriteAdapterPreference(adapter, true))
        val loaded = PrivateFavoriteAdapterStore(isolated).load()
        assertEquals("02:AA:BB:CC:DD:42", loaded.adapter!!.address)
        assertEquals(adapter.name, loaded.adapter.name)
        assertTrue(loaded.paused)
        assertEquals(setOf("address", "name", "paused"), isolated.getSharedPreferences("favorite_adapter", 0).all.keys)
        PrivateFavoriteAdapterStore(isolated).save(loaded.copy(paused = false))
        assertFalse(PrivateFavoriteAdapterStore(isolated).load().paused)
        PrivateFavoriteAdapterStore(isolated).save(FavoriteAdapterPreference())
        assertNull(PrivateFavoriteAdapterStore(isolated).load().adapter)
    }

    @Test fun malformedAddressCannotBecomeAnAutomaticConnectionTarget() = runBlocking {
        val preferences = isolated.getSharedPreferences("favorite_adapter", 0)
        preferences.edit().putString("address", "not an address").commit()
        assertNull(PrivateFavoriteAdapterStore(isolated).load().adapter)
        try {
            PrivateFavoriteAdapterStore(isolated).save(FavoriteAdapterPreference(AdapterDevice("invalid", "Synthetic")))
            fail("Malformed target must fail")
        } catch (_: IllegalArgumentException) {
            assertEquals("not an address", preferences.getString("address", null))
        }
    }
}
