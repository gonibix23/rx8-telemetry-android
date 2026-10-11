package com.gonibix.rx8.trips

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.sync.AckStatus
import com.gonibix.rx8.sync.BatchLimits
import com.gonibix.rx8.sync.RoomSyncStore
import com.gonibix.rx8.sync.SampleAck
import com.gonibix.rx8.sync.SyncLease
import com.gonibix.rx8.sync.SyncQueueSummary
import com.gonibix.rx8.sync.SyncRequestKind
import com.gonibix.rx8.sync.SyncResult
import com.gonibix.rx8.vehicle.RoomVehicleRepository
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.vehicle.VinSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, disposable databases exercise real keyset queries and the index-only upgrade. */
@RunWith(AndroidJUnit4::class)
class TripHistoryPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var name: String
    private lateinit var database: VehicleDatabase
    private lateinit var firstVehicle: String
    private lateinit var secondVehicle: String

    @Before fun setUp() = runBlocking {
        name = "trip-history-${UUID.randomUUID()}.db"
        database = open()
        firstVehicle = vehicles().create("Same name").activeVehicleId!!
        secondVehicle = vehicles().create("Same name").activeVehicleId!!
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun moreThanOneHundredTripsAndTimestampTiesTraverseBothDirectionsWithoutLoss() = runBlocking {
        val expected = seed(135)
        val first = repository().historyPage(TripHistoryRequest())
        assertEquals(expected.take(50), first.trips)
        assertFalse(first.hasNewer)
        assertTrue(first.hasOlder)
        val second = repository().historyPage(TripHistoryRequest(cursor = first.trips.last().cursor(), direction = TripHistoryDirection.OLDER))
        assertEquals(expected.drop(50).take(50), second.trips)
        assertTrue(second.hasNewer)
        assertTrue(second.hasOlder)
        val third = repository().historyPage(TripHistoryRequest(cursor = second.trips.last().cursor(), direction = TripHistoryDirection.OLDER))
        assertEquals(expected.drop(100), third.trips)
        assertTrue(third.hasNewer)
        assertFalse(third.hasOlder)
        assertEquals(expected, first.trips + second.trips + third.trips)
        val backToSecond = repository().historyPage(TripHistoryRequest(cursor = third.trips.first().cursor(), direction = TripHistoryDirection.NEWER))
        assertEquals(second, backToSecond)
        val backToFirst = repository().historyPage(TripHistoryRequest(cursor = second.trips.first().cursor(), direction = TripHistoryDirection.NEWER))
        assertEquals(first, backToFirst)
        assertEquals(TripHistoryPage(emptyList()), repository().historyPage(TripHistoryRequest(cursor = third.trips.last().cursor(), direction = TripHistoryDirection.OLDER)))
        assertEquals(TripHistoryPage(emptyList()), repository().historyPage(TripHistoryRequest(cursor = first.trips.first().cursor(), direction = TripHistoryDirection.NEWER)))
    }

    @Test fun vehicleFilterUsesUuidRetainsRecordedNamesAndDoesNotChangeSelection() = runBlocking {
        val expected = seed(141).filter { it.vehicleId == firstVehicle }
        vehicles().rename(firstVehicle, "Renamed current profile")
        val selectedBefore = vehicles().load().activeVehicleId
        var page = repository().historyPage(TripHistoryRequest(vehicleId = firstVehicle, limit = 13))
        val actual = mutableListOf<TripSession>()
        while (true) {
            assertTrue(page.trips.size in 1..13)
            assertEquals(actual.isNotEmpty(), page.hasNewer)
            actual += page.trips
            assertEquals(actual.size < expected.size, page.hasOlder)
            if (!page.hasOlder) break
            page = repository().historyPage(TripHistoryRequest(firstVehicle, page.trips.last().cursor(), TripHistoryDirection.OLDER, 13))
        }
        assertEquals(expected, actual)
        assertTrue(actual.all { it.vehicleAlias == "Frozen vehicle name" })
        assertEquals(selectedBefore, vehicles().load().activeVehicleId)
        val previous = repository().historyPage(TripHistoryRequest(firstVehicle, page.trips.first().cursor(), TripHistoryDirection.NEWER, 13))
        assertEquals(expected.drop(52).take(13), previous.trips)
        val refreshed = repository().historyPage(TripHistoryRequest(firstVehicle, previous.trips.first().cursor(), TripHistoryDirection.AT_OR_OLDER, 13))
        assertEquals(previous, refreshed)
        val noTripsVehicle = vehicles().create("No saved trips").activeVehicleId!!
        assertEquals(TripHistoryPage(emptyList()), repository().historyPage(TripHistoryRequest(vehicleId = noTripsVehicle)))
    }

    @Test fun deletedCursorAndNewTripDoNotShiftOlderPagesAndRefreshIsInclusive() = runBlocking {
        val expected = seed(125)
        val first = repository().historyPage(TripHistoryRequest())
        val boundary = first.trips.last().cursor()
        repository().delete(boundary.tripId)
        val inserted = fixture(1_000, firstVehicle, startedAt = 99_000L)
        database.trips().insertSession(inserted)
        val second = repository().historyPage(TripHistoryRequest(cursor = boundary, direction = TripHistoryDirection.OLDER))
        assertEquals(expected.drop(50).take(50), second.trips)
        assertTrue(second.hasNewer)
        assertTrue(second.hasOlder)
        assertEquals(inserted.id, repository().historyPage(TripHistoryRequest()).trips.first().id)
        val anchor = second.trips.first().cursor()
        assertEquals(second, repository().historyPage(TripHistoryRequest(cursor = anchor, direction = TripHistoryDirection.AT_OR_OLDER)))
        repository().delete(anchor.tripId)
        val refreshed = repository().historyPage(TripHistoryRequest(cursor = anchor, direction = TripHistoryDirection.AT_OR_OLDER))
        assertEquals(expected.drop(51).take(50), refreshed.trips)
        assertFalse(refreshed.trips.any { it.id == anchor.tripId || it.id == inserted.id })
    }

    @Test fun flagsDescribeFilteredRowsAndSingleItemPagesAtBothBoundaries() = runBlocking {
        val rows = seed(3)
        val only = rows.single { it.vehicleId == secondVehicle }
        val page = repository().historyPage(TripHistoryRequest(vehicleId = secondVehicle, limit = 1))
        assertEquals(listOf(only), page.trips)
        assertFalse(page.hasNewer)
        assertFalse(page.hasOlder)
        val newest = repository().historyPage(TripHistoryRequest(limit = 1))
        assertEquals(rows.take(1), newest.trips)
        assertFalse(newest.hasNewer)
        assertTrue(newest.hasOlder)
        val oldest = repository().historyPage(TripHistoryRequest(cursor = rows.last().cursor(), direction = TripHistoryDirection.AT_OR_OLDER, limit = 1))
        assertEquals(rows.takeLast(1), oldest.trips)
        assertTrue(oldest.hasNewer)
        assertFalse(oldest.hasOlder)
    }

    @Test fun invalidRequestsFailInsteadOfSilentlyChangingTheRequestedHistory() = runBlocking {
        val cursor = TripHistoryCursor(10_000L, UUID(0, 1).toString())
        val invalid = listOf(
            TripHistoryRequest(limit = 0), TripHistoryRequest(limit = 51),
            TripHistoryRequest(vehicleId = "not-a-vehicle-uuid"),
            TripHistoryRequest(cursor = cursor),
            TripHistoryRequest(direction = TripHistoryDirection.OLDER),
            TripHistoryRequest(direction = TripHistoryDirection.NEWER),
            TripHistoryRequest(direction = TripHistoryDirection.AT_OR_OLDER),
            TripHistoryRequest(cursor = cursor.copy(startedAtEpochMillis = -1L), direction = TripHistoryDirection.OLDER),
            TripHistoryRequest(cursor = cursor.copy(tripId = "1-1-1-1-1"), direction = TripHistoryDirection.OLDER),
        )
        invalid.forEach { request ->
            assertTrue("Expected rejected request: $request", runCatching { repository().historyPage(request) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(TripHistoryPage(emptyList()), repository().historyPage(TripHistoryRequest()))
    }

    @Test fun browsingAnActiveTripDoesNotRecoverRecordSamplesOrChangeQueueState() = runBlocking {
        val trip = TripSession(UUID.randomUUID().toString(), firstVehicle, "Recorded identity", 10_000L, appVersion = "0.11.0-dev")
        repository().create(trip)
        repository().append(trip.id, listOf(sample(trip, 0)))
        val before = savedRows()
        val page = repository().historyPage(TripHistoryRequest(vehicleId = firstVehicle))
        assertEquals(TripStatus.RECORDING, page.trips.single().status)
        assertEquals(before, savedRows())
    }

    @Test fun historyQueriesUseCompositeIndexesWithoutSortingTheWholeHistory() = runBlocking {
        seed(135)
        withContext(Dispatchers.IO) {
            val db = database.openHelper.readableDatabase
            listOf(false, true).forEach { filtered ->
                val filter = if (filtered) "vehicleId = ? AND " else ""
                val args = if (filtered) arrayOf<Any?>(firstVehicle, 11_000L, UUID(0, 90).toString())
                    else arrayOf<Any?>(11_000L, UUID(0, 90).toString())
                val index = if (filtered) VEHICLE_HISTORY_INDEX else HISTORY_INDEX
                listOf("<" to "DESC", "<=" to "DESC", ">" to "ASC").forEach { (operator, order) ->
                    val sql = "SELECT * FROM trip_sessions WHERE $filter(startedAtEpochMillis, id) $operator (?, ?) ORDER BY startedAtEpochMillis $order, id $order LIMIT 50"
                    val plan = db.query("EXPLAIN QUERY PLAN $sql", args).use { rows ->
                        buildList { while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("detail"))) }.joinToString("\n")
                    }
                    assertTrue(plan, plan.contains(index))
                    assertFalse(plan, plan.contains("TEMP B-TREE", ignoreCase = true))
                    assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
                }
            }
        }
    }

    @Test fun versionThreeUpgradePreservesEveryStoredRowIncludingAttemptedBodiesAndQueueStates() = runBlocking {
        val trip = TripSession(UUID.randomUUID().toString(), firstVehicle, "Frozen before upgrade", 10_000L, appVersion = "0.10.0-dev")
        repository().create(trip)
        repository().append(trip.id, (0L..4L).map { sample(trip, it) })
        repository().finish(trip.id, 11_000L, 1_000L, TripStatus.COMPLETED, "Stopped by user")
        vehicles().rename(firstVehicle, "Renamed before upgrade")
        vehicles().attachVin(firstVehicle, "TESTCAR0000000001", VinSource.MANUAL)
        val store = RoomSyncStore(database, isIsolatedTestStore = true)
        val batch = claimBatch(store)
        assertTrue(store.applyResult(batch, SyncResult.BatchAck(1, batch.request.resourceId, listOf(
            SampleAck(batch.request.sampleIds[0], AckStatus.STORED),
            SampleAck(batch.request.sampleIds[1], AckStatus.REJECTED, "synthetic_rejection"),
        )), NOW))
        val sending = claimBatch(store)
        assertEquals(2, sending.request.sampleIds.size)
        val summary = SyncQueueSummary(pending = 1, sending = 2, acknowledged = 1, blocked = 1)
        assertEquals(summary, store.summary())
        val before = savedRows()
        replaceIndexesAndIdentityWithVersionThree()
        database = open()
        assertEquals(before, savedRows())
        assertEquals(summary, RoomSyncStore(database).summary())
        assertEquals(sending.request.payloadJson, database.sync().work(sending.request.entryId)!!.payloadJson)
        assertEquals(3L, database.vehicles().find(firstVehicle)!!.revision)
        assertEquals(2L, database.trips().find(trip.id)!!.revision)
        assertEquals(secondVehicle, vehicles().load().activeVehicleId)
        assertEquals(trip.id, repository().historyPage(TripHistoryRequest(vehicleId = firstVehicle)).trips.single().id)
        assertEquals(4, withContext(Dispatchers.IO) { database.openHelper.readableDatabase.version })
        assertHistoryIndexes()
        database.close()
        database = open()
        assertEquals(before, savedRows())
    }

    @Test fun missingVersionThreeMigrationFailsWithoutRemovingSavedHistory() = runBlocking {
        val expected = seed(2)
        replaceIndexesAndIdentityWithVersionThree()
        database = Room.databaseBuilder(context, VehicleDatabase::class.java, name).build()
        assertTrue(runCatching { repository().historyPage(TripHistoryRequest()) }.isFailure)
        database.close()
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(3, raw.version)
                raw.rawQuery("SELECT COUNT(*) FROM trip_sessions", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(2, rows.getInt(0))
                }
            }
        }
        database = open()
        assertEquals(expected, repository().historyPage(TripHistoryRequest()).trips)
    }

    private suspend fun seed(count: Int): List<TripSession> = database.withTransaction {
        (1..count).map { number ->
            val entity = fixture(number, if (number % 2 == 1) firstVehicle else secondVehicle)
            database.trips().insertSession(entity)
            requireNotNull(repository().find(entity.id))
        }.sortedWith(compareByDescending<TripSession> { it.startedAtEpochMillis }.thenByDescending { it.id })
    }

    private fun fixture(number: Int, vehicleId: String, startedAt: Long = 10_000L + number / 70 * 1_000L) = TripSessionEntity(
        id = UUID(0, number.toLong()).toString(), vehicleId = vehicleId, vehicleAlias = "Frozen vehicle name",
        startedAtEpochMillis = startedAt, endedAtEpochMillis = startedAt + 100L,
        durationMillis = 100L, status = TripStatus.COMPLETED.name, endReason = "Stopped by user",
        protocolLabel = null, origin = "REAL_OBD", revision = 2, appVersion = "0.11.0-dev",
    )

    private fun sample(trip: TripSession, sequence: Long) = TripSample(
        UUID.randomUUID().toString(), trip.id, sequence, trip.startedAtEpochMillis + sequence * 100L,
        sequence * 100L, ObdMetric.SPEED, 0.0, ObdMetric.SPEED.unit, "7E8", ObdReadingStatus.VALID,
    )

    private suspend fun claimBatch(store: RoomSyncStore): SyncLease {
        repeat(20) {
            val lease = requireNotNull(store.claimNext(NOW, 60_000L, BatchLimits(maxSamples = 2)))
            if (lease.request.kind == SyncRequestKind.TELEMETRY) return lease
            assertTrue(store.applyResult(lease, SyncResult.MetadataAck(1, lease.request.resourceId, requireNotNull(lease.request.revision)), NOW))
        }
        error("Expected a synthetic telemetry batch")
    }

    private suspend fun savedRows(): Map<String, List<List<String?>>> = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        mapOf("vehicle_profiles" to "id", "active_vehicle" to "singletonId", "trip_sessions" to "id",
            "trip_samples" to "id", "sync_work" to "id", "sync_samples" to "sampleId").mapValues { (table, key) ->
            db.query("SELECT * FROM $table ORDER BY $key").use { rows ->
                buildList {
                    while (rows.moveToNext()) add((0 until rows.columnCount).map { column ->
                        if (rows.isNull(column)) null else rows.getString(column)
                    })
                }
            }
        }
    }

    private suspend fun replaceIndexesAndIdentityWithVersionThree() {
        database.close()
        withContext(Dispatchers.IO) {
            // v4 adds only these indexes. Removing them restores the exact committed v3 shape;
            // all populated data and its original SQLite types remain in the same file.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.beginTransaction()
                try {
                    raw.execSQL("DROP INDEX $HISTORY_INDEX")
                    raw.execSQL("DROP INDEX $VEHICLE_HISTORY_INDEX")
                    raw.execSQL("UPDATE room_master_table SET identity_hash = 'd37fe460ef983cf0d611bd102c67a286' WHERE id = 42")
                    raw.version = 3
                    raw.setTransactionSuccessful()
                } finally { raw.endTransaction() }
            }
        }
    }

    private suspend fun assertHistoryIndexes() = withContext(Dispatchers.IO) {
        val indexes = database.openHelper.readableDatabase.query("PRAGMA index_list(trip_sessions)").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(indexes.contains(HISTORY_INDEX))
        assertTrue(indexes.contains(VEHICLE_HISTORY_INDEX))
    }

    private fun TripSession.cursor() = TripHistoryCursor(startedAtEpochMillis, id)
    private fun repository() = RoomTripRepository(database)
    private fun vehicles() = RoomVehicleRepository(database)
    private fun open() = Room.databaseBuilder(context, VehicleDatabase::class.java, name)
        .addMigrations(VehicleDatabase.MIGRATION_1_2, VehicleDatabase.MIGRATION_2_3, VehicleDatabase.MIGRATION_3_4).build()

    private companion object {
        const val HISTORY_INDEX = "index_trip_sessions_startedAtEpochMillis_id"
        const val VEHICLE_HISTORY_INDEX = "index_trip_sessions_vehicleId_startedAtEpochMillis_id"
        const val NOW = 20_000L
    }
}
