package com.gonibix.rx8.connection

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated test preference file; no real adapter addresses or production preferences. */
@RunWith(AndroidJUnit4::class)
class LastAdapterStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "test_last_adapter_${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(filename, mode)
    }

    @After fun cleanup() { base.deleteSharedPreferences(filename) }

    @Test fun successfulAddressPersistsAcrossStoreRecreationWithoutNamesOrVehicleData() = runBlocking {
        assertNull(PrivateLastAdapterStore(isolated).loadAddress())
        PrivateLastAdapterStore(isolated).saveAddress("02:aa:bb:cc:dd:29")
        assertEquals("02:AA:BB:CC:DD:29", PrivateLastAdapterStore(isolated).loadAddress())
        assertEquals(mapOf("successful_classic_address" to "02:AA:BB:CC:DD:29"), isolated.getSharedPreferences("last_adapter", 0).all)
    }

    @Test fun malformedStoredOrRequestedAddressesCannotBecomeConnectionTargets() = runBlocking {
        isolated.getSharedPreferences("last_adapter", 0).edit().putString("successful_classic_address", "not a MAC").commit()
        val store = PrivateLastAdapterStore(isolated)
        assertNull(store.loadAddress())
        try {
            store.saveAddress("02:AA:BB:CC:DD:29 trailing text")
            fail("Invalid address must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals("not a MAC", isolated.getSharedPreferences("last_adapter", 0).getString("successful_classic_address", null))
        }
    }
}
