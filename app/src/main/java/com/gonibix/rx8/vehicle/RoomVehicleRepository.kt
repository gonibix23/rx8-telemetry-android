package com.gonibix.rx8.vehicle

import androidx.room.withTransaction
import com.gonibix.rx8.sync.enqueueVehicle

class RoomVehicleRepository(database: VehicleDatabase) : VehicleRepository by PersistentVehicleRepository(RoomVehicleStore(database))

internal class RoomVehicleStore(private val database: VehicleDatabase) : VehicleStore {
    override suspend fun <T> transaction(block: suspend VehicleTransaction.() -> T): T = database.withTransaction {
        block(RoomVehicleTransaction(database))
    }
}

private class RoomVehicleTransaction(private val database: VehicleDatabase) : VehicleTransaction {
    private val dao get() = database.vehicles()
    override suspend fun profiles(): List<VehicleProfile> = dao.all().map(VehicleEntity::toProfile)
    override suspend fun activeVehicleId(): String? = dao.activeId()
    override suspend fun find(vehicleId: String): VehicleProfile? = dao.find(vehicleId)?.toProfile()
    override suspend fun findVin(vin: String): VehicleProfile? = dao.findVin(vin)?.toProfile()
    override suspend fun insert(profile: VehicleProfile) {
        val entity = profile.toEntity()
        dao.insert(entity)
        database.sync().enqueueVehicle(entity)
    }
    override suspend fun update(profile: VehicleProfile) {
        val before = requireNotNull(dao.find(profile.id))
        val proposed = profile.toEntity().copy(revision = before.revision)
        if (proposed == before) return
        val updated = proposed.copy(revision = Math.addExact(before.revision, 1L))
        dao.update(updated)
        database.sync().enqueueVehicle(updated)
    }
    override suspend fun setActive(vehicleId: String) = dao.setActive(ActiveVehicleEntity(vehicleId = vehicleId))
}

private fun VehicleEntity.toProfile(): VehicleProfile = VehicleProfile(
    id, alias, vin, vinSource?.let(VinSource::valueOf), vinEcuId, createdAtEpochMillis,
)

private fun VehicleProfile.toEntity(): VehicleEntity = VehicleEntity(
    id, alias, vin, vinSource?.name, vinEcuId, createdAtEpochMillis,
)
