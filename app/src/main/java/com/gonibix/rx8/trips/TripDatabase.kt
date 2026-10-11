package com.gonibix.rx8.trips

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.gonibix.rx8.vehicle.VehicleEntity

@Entity(
    tableName = "trip_sessions",
    foreignKeys = [ForeignKey(
        entity = VehicleEntity::class,
        parentColumns = ["id"],
        childColumns = ["vehicleId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("vehicleId"), Index("startedAtEpochMillis"), Index("status")],
)
data class TripSessionEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val vehicleAlias: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long,
    val status: String,
    val endReason: String?,
    val protocolLabel: String?,
    val origin: String,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    val appVersion: String? = null,
)

@Entity(
    tableName = "trip_samples",
    foreignKeys = [ForeignKey(
        entity = TripSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["tripId", "sequence"], unique = true)],
)
data class TripSampleEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val sequence: Long,
    val observedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val metric: String,
    val value: Double?,
    val unit: String,
    val ecuId: String,
    val quality: String,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trip_sessions ORDER BY startedAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun list(limit: Int): List<TripSessionEntity>

    @Query("SELECT * FROM trip_sessions WHERE id = :tripId LIMIT 1")
    suspend fun find(tripId: String): TripSessionEntity?

    @Query("SELECT * FROM trip_sessions WHERE status = 'RECORDING'")
    suspend fun recording(): List<TripSessionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: TripSessionEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSession(session: TripSessionEntity)

    // No sample update/upsert API: saved observations are immutable.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSamples(samples: List<TripSampleEntity>)

    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId ORDER BY sequence DESC LIMIT 1")
    suspend fun lastSample(tripId: String): TripSampleEntity?

    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId AND sequence > :afterSequence ORDER BY sequence LIMIT :limit")
    suspend fun samples(tripId: String, afterSequence: Long, limit: Int): List<TripSampleEntity>

    @Query("DELETE FROM trip_sessions WHERE id = :tripId")
    suspend fun deleteSession(tripId: String)
}
