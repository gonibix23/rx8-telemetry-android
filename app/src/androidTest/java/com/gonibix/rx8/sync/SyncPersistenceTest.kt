package com.gonibix.rx8.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.trips.RoomTripRepository
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.RoomVehicleRepository
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.vehicle.VinSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All ACKs are confined to random, disposable databases containing synthetic data. */
@RunWith(AndroidJUnit4::class)
class SyncPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var name: String
    private lateinit var database: VehicleDatabase
    private lateinit var vehicleId: String
    private val limits = BatchLimits()

    @Before fun setUp() = runBlocking {
        name = "sync-test-${UUID.randomUUID()}.db"
        database = open()
        vehicleId = vehicles().create("Synthetic vehicle").activeVehicleId!!
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun defaultStoreRefusesAnEnabledFakeBeforeClaimingOrSendingAndAppHasNoInternetPermission() = runBlocking {
        record(2)
        var sends = 0
        val fake = object : SyncTransport {
            override val enabled = true
            override suspend fun send(request: PreparedRequest): RemoteReply {
                sends++
                return RemoteReply.MetadataAck(SyncResult.MetadataAck(1, request.resourceId, requireNotNull(request.revision)))
            }
        }
        val productionDefault = RoomSyncStore(database)
        assertFalse(productionDefault.isIsolatedTestStore)
        val result = SyncCoordinator(productionDefault, fake, nowEpochMillis = { NOW }).runOnce()
        assertEquals(SyncStep.DISABLED, result)
        assertEquals(0, sends)
        assertEquals(SyncQueueSummary(pending = 2), productionDefault.summary())
        val vehicle = database.sync().nextWork(SyncRequestKind.VEHICLE.name, NOW)!!
        assertEquals(0, vehicle.attempt)
        assertEquals(PENDING, vehicle.state)
        assertNull(vehicle.leaseToken)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
    }

    @Test fun vehicleSnapshotsAndPositiveRevisionsSurviveLaterIdentityChanges() = runBlocking {
        val initial = claim()!!
        assertEquals(SyncRequestKind.VEHICLE, initial.request.kind)
        assertEquals(1L, initial.request.revision)
        val originalBody = initial.request.payloadJson
        vehicles().select(vehicleId)
        vehicles().rename(vehicleId, "Synthetic vehicle")
        assertEquals(1L, database.vehicles().find(vehicleId)!!.revision)
        vehicles().rename(vehicleId, "Renamed vehicle")
        vehicles().attachVin(vehicleId, "TESTCAR0000000001", VinSource.MANUAL)
        vehicles().attachVin(vehicleId, "TESTCAR0000000001", VinSource.OBD, "7E8")
        assertEquals(3L, database.vehicles().find(vehicleId)!!.revision)
        assertTrue(originalBody.contains("\"vin\":null"))
        assertNull(claim()) // An in-flight old revision cannot be bypassed by newer metadata.
        acknowledgeMetadata(initial)
        val renamed = claim()!!
        assertEquals(2L, renamed.request.revision)
        assertTrue(renamed.request.payloadJson.contains("Renamed vehicle"))
        assertTrue(renamed.request.payloadJson.contains("\"vin\":null"))
        acknowledgeMetadata(renamed)
        val withVin = claim()!!
        assertEquals(3L, withVin.request.revision)
        assertTrue(withVin.request.payloadJson.contains("TESTCAR0000000001"))
        assertEquals(originalBody, initial.request.payloadJson)
        acknowledgeMetadata(withVin)
        assertNull(claim())
    }

    @Test fun metadataMustBeAcknowledgedBeforeSamplesAndInvalidAckCannotUnlockDependencies() = runBlocking {
        val trip = record(2)
        val vehicle = claim()!!
        assertTrue(runCatching { store().applyResult(vehicle, SyncResult.MetadataAck(1, UUID.randomUUID().toString(), 1), NOW) }.exceptionOrNull() is InvalidSyncAckException)
        assertNull(claim())
        acknowledgeMetadata(vehicle)
        val session = claim()!!
        assertEquals(trip.id, session.request.resourceId)
        assertTrue(session.request.payloadJson.contains("\"durationMs\":null"))
        assertTrue(session.request.payloadJson.contains("synthetic-test-version"))
        assertTrue(runCatching { store().applyResult(session, SyncResult.MetadataAck(1, trip.id, 2), NOW) }.exceptionOrNull() is InvalidSyncAckException)
        assertNull(claim())
        acknowledgeMetadata(session)
        val batch = claim()!!
        assertEquals(SyncRequestKind.TELEMETRY, batch.request.kind)
        assertEquals(2, batch.request.sampleIds.size)
        assertEquals(SyncQueueSummary(sending = 2), store().summary())
    }

    @Test fun appendAndPendingWorkRollbackTogetherAndTerminalRevisionCommitsAtomically() = runBlocking {
        val trip = record(0)
        sql("CREATE TRIGGER reject_outbox BEFORE INSERT ON sync_samples BEGIN SELECT RAISE(ABORT, 'Synthetic queue failure'); END")
        assertTrue(runCatching { trips().append(trip.id, listOf(sample(trip, 0))) }.isFailure)
        assertTrue(trips().samples(trip.id).isEmpty())
        assertEquals(SyncQueueSummary(), store().summary())
        sql("DROP TRIGGER reject_outbox")
        trips().append(trip.id, listOf(sample(trip, 0)))
        assertEquals(SyncQueueSummary(pending = 1), store().summary())
        sql("CREATE TRIGGER reject_metadata BEFORE INSERT ON sync_work BEGIN SELECT RAISE(ABORT, 'Synthetic queue failure'); END")
        assertTrue(runCatching { trips().finish(trip.id, 12_000, 2_000, TripStatus.COMPLETED, "Stopped") }.isFailure)
        assertEquals(TripStatus.RECORDING, trips().find(trip.id)!!.status)
        assertEquals(1L, database.trips().find(trip.id)!!.revision)
        sql("DROP TRIGGER reject_metadata")
        trips().finish(trip.id, 12_000, 2_000, TripStatus.COMPLETED, "Stopped")
        assertEquals(2L, database.trips().find(trip.id)!!.revision)
        assertEquals("synthetic-test-version", trips().find(trip.id)!!.appVersion)
        acknowledgeMetadata(claim()!!)
        acknowledgeMetadata(claim()!!)
        val terminal = claim()!!
        assertEquals(2L, terminal.request.revision)
        assertTrue(terminal.request.payloadJson.contains("\"status\":\"completed\""))
        assertTrue(terminal.request.payloadJson.contains("\"durationMs\":2000"))
    }

    @Test fun lostResponseAndProcessReopenRetryTheExactBodyWhileRejectingTheOldLease() = runBlocking {
        record(3)
        val first = claimBatch()
        val frozen = first.request.payloadJson
        assertEquals(1, first.attempt)
        reopen()
        assertNull(claim(NOW + 999))
        val retry = claim(NOW + 1_000)!!
        assertEquals(first.request.resourceId, retry.request.resourceId)
        assertEquals(frozen, retry.request.payloadJson)
        assertEquals(first.request.sampleIds, retry.request.sampleIds)
        assertNotEquals(first.token, retry.token)
        assertEquals(2, retry.attempt)
        assertFalse(store().applyResult(first, allStored(first), NOW + 1_001))
        assertEquals(0L, store().summary().acknowledged)
        assertTrue(store().applyResult(retry, allStored(retry), NOW + 1_001))
        reopen()
        assertEquals(SyncQueueSummary(acknowledged = 3), store().summary())
        assertNull(claim(NOW + 1_002))
    }

    @Test fun partialAckAcknowledgesOnlyConfirmedIdsAndCreatesANewBatchForOmissions() = runBlocking {
        record(4)
        val batch = claimBatch()
        val ids = batch.request.sampleIds
        val reply = SyncResult.BatchAck(1, batch.request.resourceId, listOf(
            SampleAck(ids[0], AckStatus.STORED), SampleAck(ids[1], AckStatus.ALREADY_STORED),
            SampleAck(ids[2], AckStatus.REJECTED, "invalid_sample"),
        ))
        assertTrue(store().applyResult(batch, reply, NOW))
        assertEquals(SyncQueueSummary(pending = 1, acknowledged = 2, blocked = 1), store().summary())
        assertEquals(batch.request.payloadJson, database.sync().work(batch.request.entryId)!!.payloadJson)
        assertEquals(RETIRED, database.sync().work(batch.request.entryId)!!.state)
        val pending = claim()!!
        assertEquals(listOf(ids[3]), pending.request.sampleIds)
        assertNotEquals(batch.request.resourceId, pending.request.resourceId)
        assertFalse(store().applyResult(batch, allStored(batch), NOW))
        assertTrue(store().applyResult(pending, allStored(pending), NOW))
        assertEquals(SyncQueueSummary(acknowledged = 3, blocked = 1), store().summary())
    }

    @Test fun malformedDuplicateUnknownAndEmptyAcknowledgmentsHaveNoPartialEffects() = runBlocking {
        record(2)
        val batch = claimBatch()
        val id = batch.request.sampleIds.first()
        val invalid = listOf(
            SyncResult.BatchAck(2, batch.request.resourceId, listOf(SampleAck(id, AckStatus.STORED))),
            SyncResult.BatchAck(1, UUID.randomUUID().toString(), listOf(SampleAck(id, AckStatus.STORED))),
            SyncResult.BatchAck(1, batch.request.resourceId, listOf(SampleAck(id, AckStatus.STORED), SampleAck(id, AckStatus.STORED))),
            SyncResult.BatchAck(1, batch.request.resourceId, listOf(SampleAck(id, AckStatus.STORED), SampleAck(UUID.randomUUID().toString(), AckStatus.STORED))),
            SyncResult.BatchAck(1, batch.request.resourceId, emptyList()),
            SyncResult.BatchAck(1, batch.request.resourceId, listOf(SampleAck(id, AckStatus.REJECTED))),
            SyncResult.BatchAck(1, batch.request.resourceId, listOf(SampleAck(id, AckStatus.REJECTED, "unsafe server message"))),
            SyncResult.MetadataAck(1, batch.request.resourceId, 1),
        )
        invalid.forEach { reply ->
            assertTrue(runCatching { store().applyResult(batch, reply, NOW) }.exceptionOrNull() is InvalidSyncAckException)
            assertEquals(SyncQueueSummary(sending = 2), store().summary())
        }
        assertTrue(store().applyResult(batch, allStored(batch), NOW))
    }

    @Test fun retriesRespectDurableDelayAndConflictBlocksWithoutDeletingSamples() = runBlocking {
        val trip = record(2)
        val original = claimBatch()
        assertTrue(store().applyResult(original, SyncResult.Retry(NOW + 5_000, "http_503"), NOW))
        assertEquals(SyncQueueSummary(pending = 2), store().summary())
        reopen()
        assertNull(claim(NOW + 4_999))
        val retried = claim(NOW + 5_000)!!
        assertEquals(original.request.payloadJson, retried.request.payloadJson)
        assertEquals(original.request.resourceId, retried.request.resourceId)
        assertTrue(store().applyResult(retried, SyncResult.Block("http_409"), NOW + 5_001))
        assertEquals(SyncQueueSummary(blocked = 2), store().summary())
        assertEquals(2, trips().samples(trip.id).size)
        assertNull(claim(NOW + 50_000))
    }

    @Test fun oversizedResponseSplitsIntoFreshBoundedBatchesAndSingleSampleRejectionStopsRetrying() = runBlocking {
        record(4)
        val first = claimBatch()
        assertTrue(store().applyResult(first, SyncResult.Split(BatchLimits(2, 256 * 1024)), NOW))
        assertEquals(first.request.payloadJson, database.sync().work(first.request.entryId)!!.payloadJson)
        assertEquals(RETIRED, database.sync().work(first.request.entryId)!!.state)
        assertFalse(store().applyResult(first, allStored(first), NOW))
        val second = claim()!!
        assertEquals(2, second.request.sampleIds.size)
        assertNotEquals(first.request.resourceId, second.request.resourceId)
        assertTrue(store().applyResult(second, allStored(second), NOW))
        val third = claim()!!
        assertEquals(2, third.request.sampleIds.size)
        assertTrue(store().applyResult(third, SyncResult.Split(BatchLimits(1, 256 * 1024)), NOW))
        val one = claim()!!
        assertEquals(1, one.request.sampleIds.size)
        assertTrue(store().applyResult(one, SyncResult.Split(BatchLimits(1, 256 * 1024)), NOW))
        assertEquals(1L, store().summary().blocked)
        val last = claim()!!
        assertTrue(store().applyResult(last, allStored(last), NOW))
        assertEquals(SyncQueueSummary(acknowledged = 3, blocked = 1), store().summary())
    }

    @Test fun concurrentClaimsHaveOneOwnerAndTripDeletionInvalidatesInFlightResults() = runBlocking {
        val attempts = List(2) { async(Dispatchers.IO) { claim() } }.awaitAll()
        assertEquals(1, attempts.count { it != null })
        acknowledgeMetadata(attempts.filterNotNull().single())
        val trip = record(2)
        assertTrue(runCatching { trips().delete(trip.id) }.isFailure)
        trips().finish(trip.id, 12_000, 2_000, TripStatus.COMPLETED, "Stopped")
        val batch = claimBatch()
        trips().delete(trip.id)
        assertEquals(SyncQueueSummary(), store().summary())
        assertFalse(store().applyResult(batch, allStored(batch), NOW))
        assertNull(trips().find(trip.id))
        assertNotNull(database.vehicles().find(vehicleId))
        assertNull(claim())
    }

    @Test fun recoveryEnqueuesTerminalMetadataOnceAndPreservesTheSavedVersion() = runBlocking {
        val trip = record(2)
        trips().recoverInterrupted()
        trips().recoverInterrupted()
        assertEquals(2L, database.trips().find(trip.id)!!.revision)
        assertEquals(TripStatus.INTERRUPTED, trips().find(trip.id)!!.status)
        assertEquals("synthetic-test-version", trips().find(trip.id)!!.appVersion)
        acknowledgeMetadata(claim()!!)
        acknowledgeMetadata(claim()!!)
        val terminal = claim()!!
        assertEquals(2L, terminal.request.revision)
        assertTrue(terminal.request.payloadJson.contains("\"status\":\"interrupted\""))
        assertTrue(terminal.request.payloadJson.contains("\"durationMs\":100"))
        acknowledgeMetadata(terminal)
        assertEquals(2, claim()!!.request.sampleIds.size)
    }

    @Test fun preparationHonorsUtf8EnvelopeLimitsAndDoesNotAlterSourceSamples() = runBlocking {
        val trip = record(3)
        val original = trips().samples(trip.id)
        acknowledgeMetadata(claim()!!)
        acknowledgeMetadata(claim()!!)
        val twoBody = ApiV1Json.batch(ApiV1Mapper.batch(UUID.randomUUID().toString(), vehicleId, trip.id, original.take(2)))
        val small = BatchLimits(3, twoBody.toByteArray(Charsets.UTF_8).size - 1)
        repeat(3) {
            val batch = claim(custom = small)!!
            assertEquals(1, batch.request.sampleIds.size)
            assertTrue(batch.request.payloadBytes <= small.maxBytes)
            assertTrue(store().applyResult(batch, allStored(batch), NOW))
        }
        assertEquals(original, trips().samples(trip.id))
        assertEquals(SyncQueueSummary(acknowledged = 3), store().summary())
    }

    @Test fun unsupportedSamplesAndUnsendableSingletonsAreBlockedWithoutFabricatingValues() = runBlocking {
        val trip = record(0)
        trips().append(trip.id, listOf(
            sample(trip, 0).copy(quality = ObdReadingStatus.STALE, value = null),
            sample(trip, 1),
        ))
        acknowledgeMetadata(claim()!!)
        acknowledgeMetadata(claim()!!)
        assertNull(claim(custom = BatchLimits(100, 1)))
        assertEquals(SyncQueueSummary(blocked = 2), store().summary())
        assertNull(trips().samples(trip.id).first().value)
        assertEquals(0.0, trips().samples(trip.id).last().value!!, 0.0)
    }

    @Test fun failedVehicleQueueInsertionRollsBackProfileAndSelection() = runBlocking {
        val before = vehicles().load()
        sql("CREATE TRIGGER reject_vehicle_queue BEFORE INSERT ON sync_work BEGIN SELECT RAISE(ABORT, 'Synthetic queue failure'); END")
        assertTrue(runCatching { vehicles().create("Must not exist") }.isFailure)
        assertTrue(runCatching { vehicles().rename(vehicleId, "Must not save") }.isFailure)
        assertEquals(before, vehicles().load())
        assertEquals(1L, database.vehicles().find(vehicleId)!!.revision)
    }

    @Test fun versionTwoMigrationBackfillsAllSamplesAndRetainsProfilesSelectionAndUnknownAppVersion() = runBlocking {
        database.close()
        context.deleteDatabase(name)
        val legacyTrip = UUID.randomUUID().toString()
        val expectedIds = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                raw.beginTransaction()
                try {
                    // Exact frozen v2 table/index shape and Room identity from the committed schema.
                    raw.execSQL("CREATE TABLE vehicle_profiles (id TEXT NOT NULL PRIMARY KEY, alias TEXT NOT NULL, vin TEXT COLLATE NOCASE, vinSource TEXT, vinEcuId TEXT, createdAtEpochMillis INTEGER NOT NULL)")
                    raw.execSQL("CREATE UNIQUE INDEX index_vehicle_profiles_vin ON vehicle_profiles(vin)")
                    raw.execSQL("CREATE TABLE active_vehicle (singletonId INTEGER NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, FOREIGN KEY(vehicleId) REFERENCES vehicle_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                    raw.execSQL("CREATE INDEX index_active_vehicle_vehicleId ON active_vehicle(vehicleId)")
                    raw.execSQL("CREATE TABLE trip_sessions (id TEXT NOT NULL PRIMARY KEY, vehicleId TEXT NOT NULL, vehicleAlias TEXT NOT NULL, startedAtEpochMillis INTEGER NOT NULL, endedAtEpochMillis INTEGER, durationMillis INTEGER NOT NULL, status TEXT NOT NULL, endReason TEXT, protocolLabel TEXT, origin TEXT NOT NULL, FOREIGN KEY(vehicleId) REFERENCES vehicle_profiles(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                    raw.execSQL("CREATE INDEX index_trip_sessions_vehicleId ON trip_sessions(vehicleId)")
                    raw.execSQL("CREATE INDEX index_trip_sessions_startedAtEpochMillis ON trip_sessions(startedAtEpochMillis)")
                    raw.execSQL("CREATE INDEX index_trip_sessions_status ON trip_sessions(status)")
                    raw.execSQL("CREATE TABLE trip_samples (id TEXT NOT NULL PRIMARY KEY, tripId TEXT NOT NULL, sequence INTEGER NOT NULL, observedAtEpochMillis INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL, metric TEXT NOT NULL, value REAL, unit TEXT NOT NULL, ecuId TEXT NOT NULL, quality TEXT NOT NULL, FOREIGN KEY(tripId) REFERENCES trip_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    raw.execSQL("CREATE UNIQUE INDEX index_trip_samples_tripId_sequence ON trip_samples(tripId, sequence)")
                    raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    raw.execSQL("INSERT INTO room_master_table VALUES (42, 'fa28bd6fba4125fbcce7bae525a0396f')")
                    raw.execSQL("INSERT INTO vehicle_profiles VALUES (?, 'Legacy synthetic vehicle', 'TESTCAR0000000001', 'MANUAL', NULL, 123)", arrayOf(vehicleId))
                    raw.execSQL("INSERT INTO active_vehicle VALUES(0, ?)", arrayOf(vehicleId))
                    raw.execSQL("INSERT INTO trip_sessions VALUES (?, ?, 'Frozen recorded alias', 10000, 140500, 130500, 'COMPLETED', 'Stopped by user', 'Synthetic legacy protocol', 'REAL_OBD')", arrayOf(legacyTrip, vehicleId))
                    raw.compileStatement("INSERT INTO trip_samples VALUES (?, ?, ?, ?, ?, 'SPEED', 0.0, 'km/h', '7E8', 'VALID')").use { insert ->
                        repeat(1_205) { index ->
                            val id = UUID.randomUUID().toString()
                            expectedIds += id
                            insert.bindString(1, id)
                            insert.bindString(2, legacyTrip)
                            insert.bindLong(3, index.toLong())
                            insert.bindLong(4, 10_000L + index * 100)
                            insert.bindLong(5, index * 100L)
                            insert.executeInsert()
                        }
                    }
                    raw.version = 2
                    raw.setTransactionSuccessful()
                } finally { raw.endTransaction() }
            }
        }
        database = open()
        val restored = vehicles().load()
        assertEquals(vehicleId, restored.activeVehicleId)
        assertEquals("TESTCAR0000000001", restored.profiles.single().vin)
        assertEquals(123L, restored.profiles.single().createdAtEpochMillis)
        val trip = trips().find(legacyTrip)!!
        assertEquals("Frozen recorded alias", trip.vehicleAlias)
        assertEquals(130_500L, trip.durationMillis)
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertNull(trip.appVersion)
        assertEquals(SyncQueueSummary(pending = 1_205), store().summary())
        val loaded = trips().samples(legacyTrip, limit = 1_000) + trips().samples(legacyTrip, afterSequence = 999, limit = 1_000)
        assertEquals(expectedIds, loaded.map { it.id })
        assertEquals(1L, database.vehicles().find(vehicleId)!!.revision)
        assertEquals(1L, database.trips().find(legacyTrip)!!.revision)
        acknowledgeMetadata(claim()!!)
        val session = claim()!!
        assertTrue(session.request.payloadJson.contains("\"appVersion\":null"))
        acknowledgeMetadata(session)
        assertEquals(100, claim()!!.request.sampleIds.size)
        reopen()
        assertEquals(SyncQueueSummary(pending = 1_105, sending = 100), store().summary())
        assertEquals(vehicleId, vehicles().load().activeVehicleId)
    }

    private fun store() = RoomSyncStore(database, isIsolatedTestStore = true)
    private fun vehicles() = RoomVehicleRepository(database)
    private fun trips() = RoomTripRepository(database)
    private fun open() = Room.databaseBuilder(context, VehicleDatabase::class.java, name)
        .addMigrations(VehicleDatabase.MIGRATION_1_2, VehicleDatabase.MIGRATION_2_3).build()
    private fun reopen() { database.close(); database = open() }
    private suspend fun claim(now: Long = NOW, custom: BatchLimits = limits) = store().claimNext(now, 1_000L, custom)
    private suspend fun acknowledgeMetadata(lease: SyncLease) {
        assertTrue(store().applyResult(lease, SyncResult.MetadataAck(1, lease.request.resourceId, requireNotNull(lease.request.revision)), NOW))
    }
    private suspend fun claimBatch(): SyncLease {
        repeat(20) {
            val next = requireNotNull(claim())
            if (next.request.kind == SyncRequestKind.TELEMETRY) return next
            acknowledgeMetadata(next)
        }
        error("Metadata dependencies did not finish")
    }
    private fun allStored(lease: SyncLease) = SyncResult.BatchAck(1, lease.request.resourceId,
        lease.request.sampleIds.map { SampleAck(it, AckStatus.STORED) })
    private suspend fun record(count: Int): TripSession {
        val trip = TripSession(UUID.randomUUID().toString(), vehicleId, "Synthetic recorded vehicle", 10_000,
            appVersion = "synthetic-test-version")
        trips().create(trip)
        trips().append(trip.id, (0 until count).map { sample(trip, it.toLong()) })
        return trip
    }
    private fun sample(trip: TripSession, sequence: Long) = TripSample(
        UUID.randomUUID().toString(), trip.id, sequence, trip.startedAtEpochMillis + sequence * 100,
        sequence * 100, ObdMetric.SPEED, 0.0, "km/h", "7E8", ObdReadingStatus.VALID,
    )
    private suspend fun sql(command: String) = withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL(command) }
    private companion object { const val NOW = 100_000L }
}