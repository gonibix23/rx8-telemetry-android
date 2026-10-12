package com.gonibix.rx8.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.vehicle.RoomVehicleRepository
import com.gonibix.rx8.vehicle.VehicleDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** File-backed SQLite tests validate transaction boundaries and actual schema upgrades. */
@RunWith(AndroidJUnit4::class)
class TripPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var name: String
    private lateinit var database: VehicleDatabase
    private lateinit var vehicleId: String

    @Before
    fun setUp() = runBlocking {
        name = "trip-test-${UUID.randomUUID()}.db"
        database = open()
        vehicleId = vehicles().create("Original vehicle").activeVehicleId!!
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationPreservesEveryV1VehicleFieldAndSelectionAndAllowsNewTrips() = runBlocking {
        replaceWithV1Database()
        database = open()
        val before = vehicles().load()
        assertEquals(2, before.profiles.size)
        assertEquals(V1_SECOND, before.activeVehicleId)
        val original = before.profiles.single { it.id == V1_FIRST }
        assertEquals("Original legacy profile", original.alias)
        assertEquals("TESTCAR0000000001", original.vin)
        assertEquals("MANUAL", original.vinSource!!.name)
        assertNull(original.vinEcuId)
        assertEquals(123L, original.createdAtEpochMillis)
        val second = before.profiles.single { it.id == V1_SECOND }
        assertEquals("Selected without VIN", second.alias)
        assertNull(second.vin)
        assertEquals(456L, second.createdAtEpochMillis)
        vehicleId = V1_FIRST
        val trip = session()
        repository().create(trip)
        repository().append(trip.id, listOf(sample(trip, 0)))
        repository().finish(trip.id, 10_002L, 100L, TripStatus.COMPLETED, "Stopped by user")
        reopen()
        assertEquals(before, vehicles().load())
        assertEquals(TripStatus.COMPLETED, repository().find(trip.id)!!.status)
        assertEquals(1, repository().samples(trip.id).size)
        assertEquals(4, withContext(Dispatchers.IO) { database.openHelper.readableDatabase.version })
    }

    @Test
    fun missingMigrationFailsWithoutDeletingLegacyProfiles() = runBlocking {
        replaceWithV1Database()
        database = Room.databaseBuilder(context, VehicleDatabase::class.java, name).build()
        assertTrue(runCatching { repository().list() }.isFailure)
        database.close()
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                raw.rawQuery("SELECT vehicleId FROM active_vehicle", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(V1_SECOND, rows.getString(0))
                }
                assertEquals(1, raw.version)
            }
        }
        database = open()
        assertEquals(2, vehicles().load().profiles.size)
    }

    @Test
    fun aliasSnapshotProfileForeignKeyAndSelectionRemainIndependent() = runBlocking {
        val trip = session()
        repository().create(trip)
        repository().append(trip.id, listOf(sample(trip, 0)))
        vehicles().rename(vehicleId, "Renamed later")
        val another = vehicles().create("Other profile").activeVehicleId!!
        assertEquals(trip.vehicleAlias, repository().find(trip.id)!!.vehicleAlias)
        assertEquals(another, vehicles().load().activeVehicleId)
        repository().finish(trip.id, 10_010L, 100L, TripStatus.COMPLETED, "Stopped by user")
        assertTrue(runCatching {
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM vehicle_profiles WHERE id = ?", arrayOf(vehicleId))
            }
        }.isFailure)
        assertNotNull(vehicles().load().profiles.find { it.id == vehicleId })
        assertEquals(another, vehicles().load().activeVehicleId)
    }

    @Test
    fun discoveringProtocolCreatesOneImmutableRevisionAndSurvivesReopen() = runBlocking {
        val trip = session().copy(protocolLabel = null)
        repository().create(trip)
        val originalBody = sessionWorkBodies(trip.id).single()
        val protocol = "ISO 15765-4 CAN (11 bit, 500 kbaud)"
        repository().updateProtocol(trip.id, protocol)
        repository().updateProtocol(trip.id, protocol)
        assertEquals(2L, database.trips().find(trip.id)!!.revision)
        val bodies = sessionWorkBodies(trip.id)
        assertEquals(2, bodies.size)
        assertEquals(originalBody, bodies.first())
        assertTrue(bodies.last().contains(protocol))
        assertTrue(runCatching { repository().updateProtocol(trip.id, "Different protocol") }.isFailure)
        assertEquals(bodies, sessionWorkBodies(trip.id))
        repository().finish(trip.id, 10_100, 100, TripStatus.COMPLETED, "Disconnected")
        assertEquals(3L, database.trips().find(trip.id)!!.revision)
        assertTrue(runCatching { repository().updateProtocol(trip.id, protocol) }.isFailure)
        reopen()
        assertEquals(protocol, repository().find(trip.id)!!.protocolLabel)
        assertEquals(3, sessionWorkBodies(trip.id).size)
    }

    private suspend fun sessionWorkBodies(tripId: String): List<String> = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT payloadJson FROM sync_work WHERE kind = 'SESSION' AND resourceId = ? ORDER BY revision",
            arrayOf(tripId),
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

    @Test
    fun creationRejectsMissingVehicleAndConcurrentActiveTripsAtomically() = runBlocking {
        assertTrue(runCatching { repository().create(session().copy(vehicleId = UUID.randomUUID().toString())) }.isFailure)
        assertTrue(repository().list().isEmpty())
        val candidates = listOf(session(), session())
        val results = candidates.map { candidate -> async(Dispatchers.IO) { runCatching { repository().create(candidate) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, repository().list().size)
        assertEquals(TripStatus.RECORDING, repository().list().single().status)
    }

    @Test
    fun samplesPreserveMetadataAndTrueZeroButPersistAllInvalidValuesAsNull() = runBlocking {
        val trip = session()
        repository().create(trip)
        val qualities = listOf(ObdReadingStatus.VALID) + ObdReadingStatus.entries.filter { it != ObdReadingStatus.VALID }
        val samples = qualities.mapIndexed { index, quality ->
            sample(trip, index.toLong()).copy(
                value = if (quality == ObdReadingStatus.VALID) 0.0 else 999.0,
                quality = quality,
                ecuId = if (index % 2 == 0) "7E8" else "7E9",
            )
        }
        repository().append(trip.id, samples)
        reopen()
        val loaded = repository().samples(trip.id)
        assertEquals(samples.map { it.id }, loaded.map { it.id })
        assertEquals(samples.map { it.sequence }, loaded.map { it.sequence })
        assertEquals(samples.map { it.observedAtEpochMillis }, loaded.map { it.observedAtEpochMillis })
        assertEquals(samples.map { it.ecuId }, loaded.map { it.ecuId })
        assertEquals(samples.map { it.quality }, loaded.map { it.quality })
        assertEquals(0.0, loaded.first().value!!, 0.0)
        assertTrue(loaded.drop(1).all { it.value == null })
        assertTrue(loaded.all { it.unit == ObdMetric.SPEED.unit && it.metric == ObdMetric.SPEED })
        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("SELECT metric, quality FROM trip_samples ORDER BY sequence LIMIT 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("SPEED", it.getString(0))
                assertEquals("VALID", it.getString(1))
            }
        }
    }

    @Test
    fun sequenceIdsAndElapsedTimeCannotOverwriteOrReorderExistingObservations() = runBlocking {
        val trip = session()
        repository().create(trip)
        val initial = sample(trip, 0).copy(elapsedMillis = 100L)
        repository().append(trip.id, listOf(initial))
        val invalid = listOf(
            listOf(sample(trip, 2)),
            listOf(sample(trip, 0)),
            listOf(sample(trip, 1).copy(id = initial.id)),
            listOf(sample(trip, 1).copy(tripId = UUID.randomUUID().toString())),
            listOf(sample(trip, 1).copy(elapsedMillis = 99L)),
            listOf(sample(trip, 1).copy(value = Double.NaN)),
            listOf(sample(trip, 1).copy(value = null)),
        )
        invalid.forEach { batch -> assertTrue(runCatching { repository().append(trip.id, batch) }.isFailure) }
        assertEquals(listOf(initial), repository().samples(trip.id))
        assertEquals(100L, repository().find(trip.id)!!.durationMillis)
        val duplicateId = UUID.randomUUID().toString()
        assertTrue(runCatching {
            repository().append(trip.id, listOf(sample(trip, 1).copy(id = duplicateId), sample(trip, 2).copy(id = duplicateId)))
        }.isFailure)
        assertEquals(listOf(initial), repository().samples(trip.id))
    }

    @Test
    fun failureUpdatingSessionRollsBackEveryInsertedSample() = runBlocking {
        val trip = session()
        repository().create(trip)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_trip_update BEFORE UPDATE ON trip_sessions BEGIN SELECT RAISE(ABORT, 'Synthetic disk failure'); END",
            )
        }
        assertTrue(runCatching { repository().append(trip.id, listOf(sample(trip, 0), sample(trip, 1))) }.isFailure)
        assertTrue(repository().samples(trip.id).isEmpty())
        assertEquals(0L, repository().find(trip.id)!!.durationMillis)
        assertTrue(runCatching { repository().finish(trip.id, 10_500L, 500L, TripStatus.COMPLETED, "Stopped") }.isFailure)
        assertEquals(TripStatus.RECORDING, repository().find(trip.id)!!.status)
        reopen()
        assertTrue(repository().samples(trip.id).isEmpty())
    }

    @Test
    fun pagesAreBoundedOrderedAndDoNotSkipDuplicateOrCrossTrips() = runBlocking {
        val first = session()
        repository().create(first)
        val expected = (0L..6L).map { sample(first, it) }
        repository().append(first.id, expected)
        repository().finish(first.id, 12_000L, 2_000L, TripStatus.COMPLETED, "Stopped")
        val second = session().copy(startedAtEpochMillis = 20_000L)
        repository().create(second)
        repository().append(second.id, listOf(sample(second, 0)))
        val page1 = repository().samples(first.id, limit = 3)
        val page2 = repository().samples(first.id, page1.last().sequence, 3)
        val page3 = repository().samples(first.id, page2.last().sequence, 3)
        assertEquals(expected, page1 + page2 + page3)
        assertTrue(repository().samples(first.id, page3.last().sequence, 3).isEmpty())
        assertEquals(second.id, repository().list(limit = 1).single().id)
        assertTrue(runCatching { repository().samples(first.id, limit = 0) }.isFailure)
        assertTrue(runCatching { repository().samples(first.id, limit = 1_001) }.isFailure)
        assertTrue(runCatching { repository().samples(first.id, afterSequence = -2) }.isFailure)
    }

    @Test
    fun finishIsTerminalAndDeletionRequiresStoppedTripAndCascadesOnlyItsSamples() = runBlocking {
        val trip = session()
        repository().create(trip)
        repository().append(trip.id, listOf(sample(trip, 0), sample(trip, 1)))
        assertTrue(runCatching { repository().delete(trip.id) }.isFailure)
        assertTrue(runCatching { repository().finish(trip.id, 10_300L, 50L, TripStatus.COMPLETED, "Stopped") }.isFailure)
        repository().finish(trip.id, 10_300L, 300L, TripStatus.COMPLETED, "Stopped by user")
        val finished = repository().find(trip.id)!!
        assertEquals(300L, finished.durationMillis)
        assertEquals("Stopped by user", finished.endReason)
        assertTrue(runCatching { repository().append(trip.id, listOf(sample(trip, 2))) }.isFailure)
        assertTrue(runCatching { repository().finish(trip.id, 10_500L, 500L, TripStatus.INTERRUPTED, "Different ending") }.isFailure)
        assertEquals(finished, repository().find(trip.id))
        val other = session()
        repository().create(other)
        repository().append(other.id, listOf(sample(other, 0)))
        repository().delete(trip.id)
        assertNull(repository().find(trip.id))
        assertTrue(repository().samples(trip.id).isEmpty())
        assertEquals(1, repository().samples(other.id).size)
        assertEquals(vehicleId, vehicles().load().activeVehicleId)
    }

    @Test
    fun recoveryUsesLastSavedObservationAndNeverRestoresRecordingOrChangesFinishedTrips() = runBlocking {
        val completed = session()
        repository().create(completed)
        repository().finish(completed.id, 10_200L, 200L, TripStatus.COMPLETED, "Stopped by user")
        val completedBefore = repository().find(completed.id)
        val interrupted = session()
        repository().create(interrupted)
        val samples = listOf(
            sample(interrupted, 0).copy(observedAtEpochMillis = 10_100L, elapsedMillis = 100L),
            // Wall time can move backward; monotonic duration still advances.
            sample(interrupted, 1).copy(observedAtEpochMillis = 9_000L, elapsedMillis = 800L),
        )
        repository().append(interrupted.id, samples)
        reopen()
        assertEquals(TripStatus.RECORDING, repository().find(interrupted.id)!!.status)
        repository().recoverInterrupted()
        val recovered = repository().find(interrupted.id)!!
        assertEquals(TripStatus.INTERRUPTED, recovered.status)
        assertEquals(RoomTripRepository.PROCESS_INTERRUPTION_REASON, recovered.endReason)
        assertEquals(9_000L, recovered.endedAtEpochMillis)
        assertEquals(800L, recovered.durationMillis)
        assertEquals(completedBefore, repository().find(completed.id))
        assertEquals(samples, repository().samples(interrupted.id))
        repository().recoverInterrupted()
        assertEquals(recovered, repository().find(interrupted.id))
        assertTrue(runCatching { repository().append(interrupted.id, listOf(sample(interrupted, 2))) }.isFailure)
        repository().create(session())
        assertEquals(1, repository().list().count { it.status == TripStatus.RECORDING })
    }

    @Test
    fun recoveryWithoutSamplesEndsAtStartWithZeroDuration() = runBlocking {
        val trip = session()
        repository().create(trip)
        reopen()
        repository().recoverInterrupted()
        val recovered = repository().find(trip.id)!!
        assertEquals(TripStatus.INTERRUPTED, recovered.status)
        assertEquals(trip.startedAtEpochMillis, recovered.endedAtEpochMillis)
        assertEquals(0L, recovered.durationMillis)
        assertTrue(repository().samples(trip.id).isEmpty())
    }

    @Test
    fun storageUsageIncludesDatabaseWalAndSharedMemoryWithoutCountingOtherFiles() = runBlocking {
        val trip = session()
        repository().create(trip)
        repository().append(trip.id, (0L..20L).map { sample(trip, it) })
        val path = context.getDatabasePath(name).path
        val expected = withContext(Dispatchers.IO) { listOf(path, "$path-wal", "$path-shm").sumOf { File(it).length() } }
        assertTrue(expected > 0L)
        assertEquals(expected, repository().storageBytes())
    }

    private fun repository() = RoomTripRepository(database)
    private fun vehicles() = RoomVehicleRepository(database)
    private fun session() = TripSession(UUID.randomUUID().toString(), vehicleId, "Original vehicle", 10_000L, protocolLabel = "Synthetic test protocol")
    private fun sample(trip: TripSession, sequence: Long) = TripSample(
        UUID.randomUUID().toString(), trip.id, sequence, trip.startedAtEpochMillis + sequence * 100L,
        sequence * 100L, ObdMetric.SPEED, 25.0, ObdMetric.SPEED.unit, "7E8", ObdReadingStatus.VALID,
    )
    private fun open() = Room.databaseBuilder(context, VehicleDatabase::class.java, name)
        .addMigrations(VehicleDatabase.MIGRATION_1_2, VehicleDatabase.MIGRATION_2_3, VehicleDatabase.MIGRATION_3_4)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
    private fun reopen() {
        database.close()
        database = open()
    }

    private suspend fun replaceWithV1Database() = withContext(Dispatchers.IO) {
        database.close()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
            // Frozen v1 schema, including its identity hash, matches the committed 1.json export.
            raw.execSQL("CREATE TABLE vehicle_profiles (id TEXT NOT NULL PRIMARY KEY, alias TEXT NOT NULL, vin TEXT COLLATE NOCASE, vinSource TEXT, vinEcuId TEXT, createdAtEpochMillis INTEGER NOT NULL)")
            raw.execSQL("CREATE UNIQUE INDEX index_vehicle_profiles_vin ON vehicle_profiles (vin)")
            raw.execSQL("CREATE TABLE active_vehicle (singletonId INTEGER NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, FOREIGN KEY(vehicleId) REFERENCES vehicle_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            raw.execSQL("CREATE INDEX index_active_vehicle_vehicleId ON active_vehicle (vehicleId)")
            raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, 'c8435a57896b299683ca1697198ea48d')")
            raw.execSQL("INSERT INTO vehicle_profiles VALUES (?, 'Original legacy profile', 'TESTCAR0000000001', 'MANUAL', NULL, 123)", arrayOf(V1_FIRST))
            raw.execSQL("INSERT INTO vehicle_profiles VALUES (?, 'Selected without VIN', NULL, NULL, NULL, 456)", arrayOf(V1_SECOND))
            raw.execSQL("INSERT INTO active_vehicle VALUES (0, ?)", arrayOf(V1_SECOND))
            raw.version = 1
        }
    }

    private companion object {
        const val V1_FIRST = "00000000-0000-0000-0000-000000000001"
        const val V1_SECOND = "00000000-0000-0000-0000-000000000002"
    }
}
