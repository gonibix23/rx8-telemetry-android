package com.gonibix.rx8.vehicle

import com.gonibix.rx8.telemetry.vehicle.Vin
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** The domain boundary is independent of Room so identity and failure rules have pure tests. */
internal interface VehicleStore {
    suspend fun <T> transaction(block: suspend VehicleTransaction.() -> T): T
}

internal interface VehicleTransaction {
    suspend fun profiles(): List<VehicleProfile>
    suspend fun activeVehicleId(): String?
    suspend fun find(vehicleId: String): VehicleProfile?
    suspend fun findVin(vin: String): VehicleProfile?
    suspend fun insert(profile: VehicleProfile)
    suspend fun update(profile: VehicleProfile)
    suspend fun setActive(vehicleId: String)
}

internal class PersistentVehicleRepository(
    private val store: VehicleStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val epochMillis: () -> Long = System::currentTimeMillis,
) : VehicleRepository {
    override suspend fun load(): VehicleState = stored { state() }

    override suspend fun create(alias: String, vin: String?, vinSource: VinSource?, vinEcuId: String?): VehicleState {
        val cleanAlias = validateAlias(alias)
        val identity = validateVin(vin, vinSource, vinEcuId, optional = true)
        return stored {
            identity.vin?.let { candidate -> findVin(candidate)?.let { duplicate(it.id) } }
            val profile = VehicleProfile(
                id = newId(),
                alias = cleanAlias,
                vin = identity.vin,
                vinSource = identity.source,
                vinEcuId = identity.ecu,
                createdAtEpochMillis = epochMillis(),
            )
            insert(profile)
            setActive(profile.id)
            state()
        }
    }

    override suspend fun select(vehicleId: String): VehicleState = stored {
        requireProfile(vehicleId)
        setActive(vehicleId)
        state()
    }

    override suspend fun rename(vehicleId: String, alias: String): VehicleState {
        val cleanAlias = validateAlias(alias)
        return stored {
            val profile = requireProfile(vehicleId)
            update(profile.copy(alias = cleanAlias))
            state()
        }
    }

    override suspend fun attachVin(vehicleId: String, vin: String, vinSource: VinSource, vinEcuId: String?): VehicleState {
        val identity = validateVin(vin, vinSource, vinEcuId, optional = false)
        return stored {
            val profile = requireProfile(vehicleId)
            when {
                profile.vin == identity.vin -> state() // Keep the original UUID and provenance.
                profile.vin != null -> throw VehicleRepositoryException(
                    VehicleFailure.DIFFERENT_VIN, profile.id,
                    "This profile already has a different VIN. Select or create the matching vehicle.",
                )
                else -> {
                    findVin(checkNotNull(identity.vin))?.let { duplicate(it.id) }
                    update(profile.copy(vin = identity.vin, vinSource = identity.source, vinEcuId = identity.ecu))
                    state()
                }
            }
        }
    }

    private suspend fun <T> stored(block: suspend VehicleTransaction.() -> T): T = try {
        store.transaction(block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: VehicleRepositoryException) {
        throw failure
    } catch (failure: Exception) {
        // Never return an empty/default state after a failed read or an uncommitted write.
        throw VehicleRepositoryException(
            VehicleFailure.STORAGE_ERROR,
            message = "Vehicle profiles could not be read or saved. Try again when storage is available.",
            cause = failure,
        )
    }

    private suspend fun VehicleTransaction.state(): VehicleState {
        val profiles = profiles()
        val active = activeVehicleId()
        check(active == null || profiles.any { it.id == active }) { "Active vehicle references a missing profile." }
        return VehicleState(profiles.toList(), active)
    }

    private suspend fun VehicleTransaction.requireProfile(vehicleId: String): VehicleProfile =
        find(vehicleId) ?: throw VehicleRepositoryException(VehicleFailure.NOT_FOUND, vehicleId, "The selected vehicle profile no longer exists.")

    private fun duplicate(vehicleId: String): Nothing = throw VehicleRepositoryException(
        VehicleFailure.VIN_ALREADY_ASSIGNED, vehicleId,
        "This VIN already belongs to another profile. Select the existing vehicle.",
    )

    private fun validateAlias(raw: String): String {
        val alias = raw.trim()
        if (alias.isEmpty() || alias.length > 60 || alias.any(Char::isISOControl)) {
            throw VehicleRepositoryException(VehicleFailure.INVALID_ALIAS, message = "Enter a vehicle name with 1 to 60 characters on one line.")
        }
        return alias
    }

    private fun validateVin(raw: String?, source: VinSource?, ecu: String?, optional: Boolean): ValidatedVin {
        if (raw.isNullOrBlank() && optional) {
            if (source != null || ecu != null) invalidProvenance()
            return ValidatedVin(null, null, null)
        }
        val vin = raw?.let(Vin::normalize) ?: throw VehicleRepositoryException(
            VehicleFailure.INVALID_VIN, message = "Enter a 17-character VIN using letters and numbers, excluding I, O, and Q.",
        )
        return when (source) {
            VinSource.MANUAL -> {
                if (ecu != null) invalidProvenance()
                ValidatedVin(vin, source, null)
            }
            VinSource.OBD -> {
                val normalizedEcu = ecu?.trim()?.uppercase(Locale.ROOT)
                if (normalizedEcu == null || !ECU_SOURCE.matches(normalizedEcu)) invalidProvenance()
                ValidatedVin(vin, source, normalizedEcu)
            }
            null -> invalidProvenance()
        }
    }

    private fun invalidProvenance(): Nothing = throw VehicleRepositoryException(
        VehicleFailure.INVALID_PROVENANCE,
        message = "VIN provenance is incomplete. Enter it manually or use a complete detected ECU response.",
    )

    private data class ValidatedVin(val vin: String?, val source: VinSource?, val ecu: String?)

    private companion object {
        val ECU_SOURCE = Regex("(?:[0-9A-F]{2}|[0-9A-F]{3}|[0-9A-F]{8})")
    }
}
