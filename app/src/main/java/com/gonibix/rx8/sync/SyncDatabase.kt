package com.gonibix.rx8.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.gonibix.rx8.trips.TripSampleEntity
import com.gonibix.rx8.trips.TripSessionEntity
import com.gonibix.rx8.vehicle.VehicleEntity
import kotlinx.coroutines.flow.Flow

/** Only metadata and claimed batches have bodies; unclaimed telemetry remains a sample reference. */
@Entity(
    tableName = "sync_work",
    foreignKeys = [
        ForeignKey(entity = VehicleEntity::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TripSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("vehicleId"), Index("sessionId"), Index(value = ["kind", "resourceId", "revision"], unique = true), Index(value = ["state", "nextAttemptEpochMillis"])],
)
data class SyncWorkEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val resourceId: String,
    val vehicleId: String,
    val sessionId: String?,
    val revision: Long?,
    val payloadJson: String,
    val state: String = PENDING,
    val attempt: Int = 0,
    val nextAttemptEpochMillis: Long = 0,
    val leaseToken: String? = null,
    val leaseExpiresAtEpochMillis: Long? = null,
    val errorCode: String? = null,
)

@Entity(
    tableName = "sync_samples",
    foreignKeys = [
        ForeignKey(entity = TripSampleEntity::class, parentColumns = ["id"], childColumns = ["sampleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SyncWorkEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("batchId"), Index("state")],
)
data class SyncSampleEntity(
    @PrimaryKey val sampleId: String,
    val state: String = PENDING,
    val batchId: String? = null,
    val errorCode: String? = null,
)

data class SyncStateCount(val state: String, val count: Long)

@Dao
interface SyncDao {
    /** Count terminal trips only; a trip needs both current metadata revisions and every sample ACK. */
    @Query("""
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN delivery = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmed,
            COALESCE(SUM(CASE WHEN delivery = 'WAITING' THEN 1 ELSE 0 END), 0) AS waiting,
            COALESCE(SUM(CASE WHEN delivery = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked
        FROM (
            SELECT CASE
                WHEN EXISTS (SELECT 1 FROM sync_work w WHERE w.state = 'BLOCKED' AND
                    ((w.kind = 'VEHICLE' AND w.vehicleId = t.vehicleId) OR (w.kind = 'SESSION' AND w.sessionId = t.id)))
                  OR EXISTS (SELECT 1 FROM trip_samples s JOIN sync_samples q ON q.sampleId = s.id
                    WHERE s.tripId = t.id AND q.state = 'BLOCKED') THEN 'BLOCKED'
                WHEN NOT EXISTS (SELECT 1 FROM sync_work w WHERE w.kind = 'VEHICLE' AND w.vehicleId = t.vehicleId
                    AND w.revision = v.revision AND w.state = 'ACKNOWLEDGED')
                  OR NOT EXISTS (SELECT 1 FROM sync_work w WHERE w.kind = 'SESSION' AND w.sessionId = t.id
                    AND w.revision = t.revision AND w.state = 'ACKNOWLEDGED')
                  OR EXISTS (SELECT 1 FROM sync_work w WHERE w.state != 'ACKNOWLEDGED' AND
                    ((w.kind = 'VEHICLE' AND w.vehicleId = t.vehicleId) OR (w.kind = 'SESSION' AND w.sessionId = t.id)))
                  OR EXISTS (SELECT 1 FROM trip_samples s LEFT JOIN sync_samples q ON q.sampleId = s.id
                    WHERE s.tripId = t.id AND (q.sampleId IS NULL OR q.state != 'ACKNOWLEDGED')) THEN 'WAITING'
                ELSE 'CONFIRMED' END AS delivery
            FROM trip_sessions t JOIN vehicle_profiles v ON v.id = t.vehicleId
            WHERE t.status IN ('COMPLETED', 'INTERRUPTED')
        )
    """)
    fun observeDelivery(): Flow<UploadDeliveryCounts>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWork(work: SyncWorkEntity)
    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateWork(work: SyncWorkEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSamples(samples: List<SyncSampleEntity>)
    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSample(sample: SyncSampleEntity)
    @Query("SELECT * FROM sync_work WHERE id = :id")
    suspend fun work(id: String): SyncWorkEntity?
    @Query("DELETE FROM sync_work WHERE id = :id")
    suspend fun deleteWork(id: String)
    @Query("SELECT * FROM sync_samples WHERE batchId = :id ORDER BY sampleId")
    suspend fun batchReferences(id: String): List<SyncSampleEntity>
    @Query("SELECT s.* FROM trip_samples s INNER JOIN sync_samples q ON q.sampleId = s.id WHERE q.batchId = :id ORDER BY s.sequence")
    suspend fun batchSamples(id: String): List<TripSampleEntity>
    @Query("SELECT state, COUNT(*) AS count FROM sync_samples GROUP BY state")
    suspend fun counts(): List<SyncStateCount>
    @Query("SELECT * FROM sync_work WHERE state = 'SENDING' AND leaseExpiresAtEpochMillis <= :now ORDER BY leaseExpiresAtEpochMillis, id LIMIT 100")
    suspend fun expired(now: Long): List<SyncWorkEntity>

    @Query("""
        SELECT w.* FROM sync_work w
        WHERE w.state = 'PENDING' AND w.nextAttemptEpochMillis <= :now AND w.kind = :kind
          AND EXISTS (SELECT 1 FROM trip_sessions t WHERE t.status IN ('COMPLETED', 'INTERRUPTED')
              AND ((w.kind = 'VEHICLE' AND t.vehicleId = w.vehicleId) OR t.id = w.sessionId))
          AND NOT EXISTS (SELECT 1 FROM sync_work older WHERE older.kind = w.kind AND older.resourceId = w.resourceId
              AND older.revision < w.revision AND older.state != 'ACKNOWLEDGED')
          AND (w.kind = 'VEHICLE' OR NOT EXISTS (SELECT 1 FROM sync_work v
              WHERE v.kind = 'VEHICLE' AND v.vehicleId = w.vehicleId AND v.state != 'ACKNOWLEDGED'))
          AND (w.kind != 'TELEMETRY' OR NOT EXISTS (SELECT 1 FROM sync_work s
              WHERE s.kind = 'SESSION' AND s.sessionId = w.sessionId AND s.state != 'ACKNOWLEDGED'))
        ORDER BY w.nextAttemptEpochMillis, w.revision, w.id LIMIT 1
    """)
    suspend fun nextWork(kind: String, now: Long): SyncWorkEntity?

    @Query("""
        SELECT t.* FROM trip_sessions t
        WHERE t.status IN ('COMPLETED', 'INTERRUPTED')
          AND EXISTS (SELECT 1 FROM trip_samples s INNER JOIN sync_samples q ON q.sampleId = s.id
            WHERE s.tripId = t.id AND q.state = 'PENDING' AND q.batchId IS NULL)
          AND NOT EXISTS (SELECT 1 FROM sync_work w WHERE w.state != 'ACKNOWLEDGED'
            AND ((w.kind = 'VEHICLE' AND w.vehicleId = t.vehicleId) OR (w.kind = 'SESSION' AND w.sessionId = t.id)))
        ORDER BY t.startedAtEpochMillis, t.id LIMIT 1
    """)
    suspend fun nextUnbatchedSession(): TripSessionEntity?

    @Query("""
        SELECT s.* FROM trip_samples s INNER JOIN sync_samples q ON q.sampleId = s.id
        WHERE s.tripId = :tripId AND q.state = 'PENDING' AND q.batchId IS NULL
        ORDER BY s.sequence LIMIT :limit
    """)
    suspend fun unbatchedSamples(tripId: String, limit: Int): List<TripSampleEntity>

    @Query("""
        SELECT MIN(CASE WHEN w.state = 'SENDING' THEN w.leaseExpiresAtEpochMillis ELSE w.nextAttemptEpochMillis END)
        FROM sync_work w WHERE w.state IN ('PENDING', 'SENDING')
          AND EXISTS (SELECT 1 FROM trip_sessions t WHERE t.status IN ('COMPLETED', 'INTERRUPTED')
              AND ((w.kind = 'VEHICLE' AND t.vehicleId = w.vehicleId) OR t.id = w.sessionId))
          AND NOT EXISTS (SELECT 1 FROM sync_work older WHERE older.kind = w.kind AND older.resourceId = w.resourceId
              AND older.revision < w.revision AND older.state != 'ACKNOWLEDGED')
          AND (w.kind = 'VEHICLE' OR NOT EXISTS (SELECT 1 FROM sync_work v
              WHERE v.kind = 'VEHICLE' AND v.vehicleId = w.vehicleId AND v.state != 'ACKNOWLEDGED'))
          AND (w.kind != 'TELEMETRY' OR NOT EXISTS (SELECT 1 FROM sync_work s
              WHERE s.kind = 'SESSION' AND s.sessionId = w.sessionId AND s.state != 'ACKNOWLEDGED'))
    """)
    suspend fun nextEligibleTime(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_work WHERE state = 'BLOCKED') OR EXISTS(SELECT 1 FROM sync_samples WHERE state = 'BLOCKED')")
    suspend fun hasBlocked(): Boolean

    @Query("SELECT * FROM sync_work WHERE state = 'BLOCKED' AND errorCode = 'retry_limit_reached' ORDER BY id LIMIT 100")
    suspend fun exhausted(): List<SyncWorkEntity>
}

internal const val PENDING = "PENDING"
internal const val SENDING = "SENDING"
internal const val ACKNOWLEDGED = "ACKNOWLEDGED"
internal const val BLOCKED = "BLOCKED"
internal const val RETIRED = "RETIRED"
