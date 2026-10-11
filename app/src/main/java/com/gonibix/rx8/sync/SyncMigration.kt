package com.gonibix.rx8.sync

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gonibix.rx8.trips.TripSessionEntity
import com.gonibix.rx8.vehicle.VehicleEntity

/** The migration is one SQLite transaction; existing data is never replaced or marked delivered. */
internal object SyncMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE trip_sessions ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE trip_sessions ADD COLUMN appVersion TEXT")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_work (
                id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, resourceId TEXT NOT NULL,
                vehicleId TEXT NOT NULL, sessionId TEXT, revision INTEGER, payloadJson TEXT NOT NULL,
                state TEXT NOT NULL, attempt INTEGER NOT NULL, nextAttemptEpochMillis INTEGER NOT NULL,
                leaseToken TEXT, leaseExpiresAtEpochMillis INTEGER, errorCode TEXT,
                FOREIGN KEY(vehicleId) REFERENCES vehicle_profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(sessionId) REFERENCES trip_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_work_vehicleId ON sync_work(vehicleId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_work_sessionId ON sync_work(sessionId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_work_kind_resourceId_revision ON sync_work(kind, resourceId, revision)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_work_state_nextAttemptEpochMillis ON sync_work(state, nextAttemptEpochMillis)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_samples (
                sampleId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, batchId TEXT, errorCode TEXT,
                FOREIGN KEY(sampleId) REFERENCES trip_samples(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(batchId) REFERENCES sync_work(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_samples_batchId ON sync_samples(batchId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_samples_state ON sync_samples(state)")
        // SQLite streams this set operation. No telemetry is materialized as Kotlin objects or JSON.
        db.execSQL("INSERT INTO sync_samples(sampleId, state, batchId, errorCode) SELECT id, 'PENDING', NULL, NULL FROM trip_samples")
        // Only metadata snapshots are encoded, one cursor row at a time. The original app version is unknown.
        db.query("SELECT * FROM vehicle_profiles").use { rows ->
            while (rows.moveToNext()) {
                val profile = VehicleEntity(rows.text("id"), rows.text("alias"), rows.nullable("vin"),
                    rows.nullable("vinSource"), rows.nullable("vinEcuId"), rows.number("createdAtEpochMillis"))
                db.insertWork(profile.syncWork())
            }
        }
        db.query("SELECT * FROM trip_sessions").use { rows ->
            while (rows.moveToNext()) {
                val session = TripSessionEntity(rows.text("id"), rows.text("vehicleId"), rows.text("vehicleAlias"),
                    rows.number("startedAtEpochMillis"), rows.nullableNumber("endedAtEpochMillis"), rows.number("durationMillis"),
                    rows.text("status"), rows.nullable("endReason"), rows.nullable("protocolLabel"), rows.text("origin"))
                db.insertWork(session.syncWork())
            }
        }
    }

    private fun SupportSQLiteDatabase.insertWork(work: SyncWorkEntity) = execSQL(
        "INSERT INTO sync_work(id, kind, resourceId, vehicleId, sessionId, revision, payloadJson, state, attempt, nextAttemptEpochMillis, leaseToken, leaseExpiresAtEpochMillis, errorCode) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, NULL, NULL, NULL)",
        arrayOf<Any?>(work.id, work.kind, work.resourceId, work.vehicleId, work.sessionId, work.revision, work.payloadJson),
    )
    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullable(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.number(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.nullableNumber(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
}
