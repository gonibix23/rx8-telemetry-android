package com.gonibix.rx8.vehicle

import androidx.room.withTransaction

class RoomVehicleRepository(database: VehicleDatabase) : VehicleRepository by PersistentVehicleRepository(RoomVehicleStore(database))

internal class RoomVehicleStore(private val database: VehicleDatabase) : VehicleStore {
    override suspend fun <T> transaction(block: suspend VehicleTransaction.() -> T): T = database.withTransaction {
        block(RoomVehicleTransaction(database.vehicles()))
    }
}

private class RoomVehicleTransaction(private val dao: VehicleDao) : VehicleTransaction {
    override suspend fun profiles(): List<VehicleProfile> = dao.all().map(VehicleEntity::toProfile)
    override suspend fun activeVehicleId(): String? = dao.activeId()
    override suspend fun find(vehicleId: String): VehicleProfile? = dao.find(vehicleId)?.toProfile()
    override suspend fun findVin(vin: String): VehicleProfile? = dao.findVin(vin)?.toProfile()
    override suspend fun insert(profile: VehicleProfile) = dao.insert(profile.toEntity())
    override suspend fun update(profile: VehicleProfile) = dao.update(profile.toEntity())
    override suspend fun setActive(vehicleId: String) = dao.setActive(ActiveVehicleEntity(vehicleId = vehicleId))
}

private fun VehicleEntity.toProfile(): VehicleProfile = VehicleProfile(
    id, alias, vin, vinSource?.let(VinSource::valueOf), vinEcuId, createdAtEpochMillis,
)

private fun VehicleProfile.toEntity(): VehicleEntity = VehicleEntity(
    id, alias, vin, vinSource?.name, vinEcuId, createdAtEpochMillis,
)
