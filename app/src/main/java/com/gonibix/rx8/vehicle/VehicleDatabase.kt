package com.gonibix.rx8.vehicle

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gonibix.rx8.sync.SyncDao
import com.gonibix.rx8.sync.SyncWorkEntity
import com.gonibix.rx8.sync.SyncSampleEntity
import com.gonibix.rx8.sync.SyncMigration
import com.gonibix.rx8.trips.TripDao
import com.gonibix.rx8.trips.TripSampleEntity
import com.gonibix.rx8.trips.TripSessionEntity

@Entity(tableName = "vehicle_profiles", indices = [Index(value = ["vin"], unique = true)])
data class VehicleEntity(
    @PrimaryKey val id: String,
    val alias: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val vin: String?,
    val vinSource: String?,
    val vinEcuId: String?,
    val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)

@Entity(
    tableName = "active_vehicle",
    foreignKeys = [ForeignKey(
        entity = VehicleEntity::class,
        parentColumns = ["id"],
        childColumns = ["vehicleId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("vehicleId")],
)
data class ActiveVehicleEntity(@PrimaryKey val singletonId: Int = 0, val vehicleId: String)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicle_profiles ORDER BY createdAtEpochMillis, id")
    suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id LIMIT 1")
    suspend fun find(id: String): VehicleEntity?

    @Query("SELECT * FROM vehicle_profiles WHERE vin = :vin LIMIT 1")
    suspend fun findVin(vin: String): VehicleEntity?

    @Query("SELECT vehicleId FROM active_vehicle WHERE singletonId = 0")
    suspend fun activeId(): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: VehicleEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(profile: VehicleEntity)

    @Upsert
    suspend fun setActive(active: ActiveVehicleEntity)
}

@Database(
    entities = [VehicleEntity::class, ActiveVehicleEntity::class, TripSessionEntity::class, TripSampleEntity::class, SyncWorkEntity::class, SyncSampleEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class VehicleDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun trips(): TripDao
    abstract fun sync(): SyncDao

    companion object {
        /** Add trip storage without rebuilding, replacing or reselecting any existing vehicle. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `trip_sessions` (
                        `id` TEXT NOT NULL,
                        `vehicleId` TEXT NOT NULL,
                        `vehicleAlias` TEXT NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `endedAtEpochMillis` INTEGER,
                        `durationMillis` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `endReason` TEXT,
                        `protocolLabel` TEXT,
                        `origin` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`vehicleId`) REFERENCES `vehicle_profiles`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_vehicleId` ON `trip_sessions` (`vehicleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_startedAtEpochMillis` ON `trip_sessions` (`startedAtEpochMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_status` ON `trip_sessions` (`status`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `trip_samples` (
                        `id` TEXT NOT NULL,
                        `tripId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `observedAtEpochMillis` INTEGER NOT NULL,
                        `elapsedMillis` INTEGER NOT NULL,
                        `metric` TEXT NOT NULL,
                        `value` REAL,
                        `unit` TEXT NOT NULL,
                        `ecuId` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`tripId`) REFERENCES `trip_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trip_samples_tripId_sequence` ON `trip_samples` (`tripId`, `sequence`)")
            }
        }
        val MIGRATION_2_3: Migration = SyncMigration
        /** History-only indexes preserve all stored identities, samples, revisions and queue state. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_startedAtEpochMillis_id` ON `trip_sessions` (`startedAtEpochMillis`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_vehicleId_startedAtEpochMillis_id` ON `trip_sessions` (`vehicleId`, `startedAtEpochMillis`, `id`)")
            }
        }
        @Volatile private var instance: VehicleDatabase? = null

        /** Application-scoped database. The process owns its lifetime, not an Activity or ViewModel. */
        fun open(context: Context): VehicleDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VehicleDatabase::class.java,
                "rx8-vehicles.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            // No destructive fallback: future schema changes require explicit migrations.
        }
    }
}
