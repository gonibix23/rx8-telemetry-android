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

@Entity(tableName = "vehicle_profiles", indices = [Index(value = ["vin"], unique = true)])
data class VehicleEntity(
    @PrimaryKey val id: String,
    val alias: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val vin: String?,
    val vinSource: String?,
    val vinEcuId: String?,
    val createdAtEpochMillis: Long,
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

@Database(entities = [VehicleEntity::class, ActiveVehicleEntity::class], version = 1, exportSchema = true)
abstract class VehicleDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao

    companion object {
        @Volatile private var instance: VehicleDatabase? = null

        /** Application-scoped database. The process owns its lifetime, not an Activity or ViewModel. */
        fun open(context: Context): VehicleDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VehicleDatabase::class.java,
                "rx8-vehicles.db",
            ).build().also { instance = it }
            // No destructive fallback: future schema changes require explicit migrations.
        }
    }
}
