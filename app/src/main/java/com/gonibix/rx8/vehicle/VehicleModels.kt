package com.gonibix.rx8.vehicle

enum class VinSource { MANUAL, OBD }

data class VehicleProfile(
    val id: String,
    val alias: String,
    val vin: String?,
    val vinSource: VinSource?,
    val vinEcuId: String?,
    val createdAtEpochMillis: Long,
)

data class VehicleState(
    val profiles: List<VehicleProfile> = emptyList(),
    val activeVehicleId: String? = null,
)

/** Each mutation returns only after the profile and active selection commit atomically. */
interface VehicleRepository {
    suspend fun load(): VehicleState
    suspend fun create(
        alias: String,
        vin: String? = null,
        vinSource: VinSource? = null,
        vinEcuId: String? = null,
    ): VehicleState
    suspend fun select(vehicleId: String): VehicleState
    suspend fun rename(vehicleId: String, alias: String): VehicleState
    suspend fun attachVin(vehicleId: String, vin: String, vinSource: VinSource, vinEcuId: String? = null): VehicleState
}

enum class VehicleFailure {
    INVALID_ALIAS, INVALID_VIN, INVALID_PROVENANCE, NOT_FOUND,
    VIN_ALREADY_ASSIGNED, DIFFERENT_VIN, STORAGE_ERROR,
}

/** vehicleId identifies the matching/target profile when resolving an identity conflict. */
class VehicleRepositoryException(
    val reason: VehicleFailure,
    val vehicleId: String? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
