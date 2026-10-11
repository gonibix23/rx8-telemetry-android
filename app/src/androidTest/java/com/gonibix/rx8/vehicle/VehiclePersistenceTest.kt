package com.gonibix.rx8.vehicle

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** File-backed tests exercise actual SQLite constraints, transactions, and process-style reopening. */
@RunWith(AndroidJUnit4::class)
class VehiclePersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: VehicleDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseName = "vehicle-test-${UUID.randomUUID()}.db"
        database = openTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun emptyDatabaseDoesNotCreateAnIdentityOrSelectOne() = runBlocking {
        assertEquals(VehicleState(), repository().load())
        reopen()
        assertEquals(VehicleState(), repository().load())
    }

    @Test
    fun uuidAliasVinAndActiveSelectionSurviveCloseAndReopen() = runBlocking {
        val first = repository().create("First", VIN_A, VinSource.MANUAL).profiles.single()
        val second = repository().create("Second").profiles.single { it.id != first.id }
        repository().rename(first.id, "Updated alias")
        val before = repository().select(first.id)
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertNull(second.vin)

        reopen()

        assertEquals(before, repository().load())
        assertEquals(first.id, repository().load().activeVehicleId)
    }

    @Test
    fun attachingDetectedVinRetainsTheOriginalPersistentUuid() = runBlocking {
        val before = repository().create("VIN unavailable").profiles.single()
        val attached = repository().attachVin(before.id, VIN_A, VinSource.OBD, "7e8")
        reopen()

        val loaded = repository().load()
        assertEquals(attached, loaded)
        assertEquals(before.id, loaded.profiles.single().id)
        assertEquals(before.createdAtEpochMillis, loaded.profiles.single().createdAtEpochMillis)
        assertEquals(VinSource.OBD, loaded.profiles.single().vinSource)
        assertEquals("7E8", loaded.profiles.single().vinEcuId)
    }

    @Test
    fun conflictingAndDuplicateVinsNeverReassignSavedIdentity() = runBlocking {
        val first = repository().create("First", VIN_A, VinSource.MANUAL).profiles.single()
        val before = repository().create("Second")
        val second = before.profiles.single { it.id != first.id }
        val wrong = failure { repository().attachVin(first.id, VIN_B, VinSource.OBD, "7E9") }
        val duplicate = failure { repository().attachVin(second.id, VIN_A, VinSource.MANUAL) }

        assertEquals(VehicleFailure.DIFFERENT_VIN, wrong.reason)
        assertEquals(VehicleFailure.VIN_ALREADY_ASSIGNED, duplicate.reason)
        assertEquals(first.id, duplicate.vehicleId)
        reopen()
        assertEquals(before, repository().load())
    }

    @Test
    fun twoRepositoryInstancesCannotCreateDuplicateVinProfiles() = runBlocking {
        val attempts = listOf(repository(), repository()).mapIndexed { index, repository ->
            async(Dispatchers.IO) { runCatching { repository.create("Concurrent $index", VIN_A, VinSource.MANUAL) } }
        }.awaitAll()

        assertEquals(1, attempts.count { it.isSuccess })
        val rejected = attempts.single { it.isFailure }.exceptionOrNull() as VehicleRepositoryException
        assertEquals(VehicleFailure.VIN_ALREADY_ASSIGNED, rejected.reason)
        reopen()
        val state = repository().load()
        assertEquals(1, state.profiles.size)
        assertEquals(state.profiles.single().id, state.activeVehicleId)
    }

    @Test
    fun sqliteUniqueVinConstraintAlsoProtectsAgainstCaseVariants() = runBlocking {
        val before = repository().create("First", VIN_A, VinSource.MANUAL)
        val duplicate = VehicleEntity(UUID.randomUUID().toString(), "Duplicate", VIN_A.lowercase(), "MANUAL", null, 1L)

        assertTrue(runCatching { database.vehicles().insert(duplicate) }.isFailure)
        assertEquals(before, repository().load())
    }

    @Test
    fun activeSelectionForeignKeyRejectsMissingProfiles() = runBlocking {
        val before = repository().create("Existing")

        assertTrue(runCatching { database.vehicles().setActive(ActiveVehicleEntity(vehicleId = UUID.randomUUID().toString())) }.isFailure)
        assertEquals(before, repository().load())
    }

    @Test
    fun failedActiveSelectionRollsBackNewProfileInsertion() = runBlocking {
        val before = repository().create("Existing")
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_active_insert BEFORE INSERT ON active_vehicle BEGIN SELECT RAISE(ABORT, 'Synthetic storage failure'); END",
            )
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_active_update BEFORE UPDATE ON active_vehicle BEGIN SELECT RAISE(ABORT, 'Synthetic storage failure'); END",
            )
        }

        val rejected = failure { repository().create("Must roll back", VIN_A, VinSource.MANUAL) }
        assertEquals(VehicleFailure.STORAGE_ERROR, rejected.reason)
        assertEquals(before, repository().load())
        reopen()
        assertEquals(before, repository().load())
    }

    @Test
    fun storageFailureDoesNotReportAnEmptySuccessfulLoad() = runBlocking {
        repository().create("Existing")
        database.close()

        assertEquals(VehicleFailure.STORAGE_ERROR, failure { repository().load() }.reason)
        database = openTestDatabase()
        assertEquals(1, repository().load().profiles.size)
    }

    @Test
    fun unsupportedDatabaseVersionFailsWithoutDeletingProfiles() = runBlocking {
        val profile = repository().create("Preserved").profiles.single()
        database.close()
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.version = 3
            }
        }
        database = openTestDatabase()

        assertEquals(VehicleFailure.STORAGE_ERROR, failure { repository().load() }.reason)
        database.close()
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                raw.rawQuery("SELECT id FROM vehicle_profiles", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(profile.id, rows.getString(0))
                    assertEquals(1, rows.count)
                }
            }
        }
    }

    private fun repository(): VehicleRepository = RoomVehicleRepository(database)

    private fun openTestDatabase(): VehicleDatabase = Room.databaseBuilder(context, VehicleDatabase::class.java, databaseName).build()

    private fun reopen() {
        database.close()
        database = openTestDatabase()
    }

    private suspend fun failure(action: suspend () -> Unit): VehicleRepositoryException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a repository failure, got $failure", failure is VehicleRepositoryException)
        return failure as VehicleRepositoryException
    }

    private companion object {
        // Deliberately synthetic fixtures, not real VINs.
        const val VIN_A = "TESTCAR0000000001"
        const val VIN_B = "TESTCAR0000000002"
    }
}
