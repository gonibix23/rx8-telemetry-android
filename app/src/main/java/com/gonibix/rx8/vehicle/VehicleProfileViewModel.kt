package com.gonibix.rx8.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.telemetry.vehicle.Vin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VehicleProfileUiState(
    val vehicles: VehicleState = VehicleState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
    val matchingVehicleId: String? = null,
    val pendingVin: VinResponse? = null,
)

/** Every identity change is explicit and published only after persistent storage succeeds. */
class VehicleProfileViewModel(
    private val repository: VehicleRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VehicleProfileUiState())
    val uiState: StateFlow<VehicleProfileUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { reload() }

    fun reload() {
        if (mutableState.value.isSaving || loadJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(isLoading = true, message = null)
        loadJob = viewModelScope.launch(dispatcher) {
            try {
                val loaded = repository.load()
                mutableState.value = mutableState.value.copy(vehicles = loaded, isLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    message = "Saved vehicles could not be loaded. Try opening Vehicles again. No profile was replaced.",
                )
            }
        }
    }

    fun create(alias: String, vin: String?) = save("Vehicle saved.") {
        repository.create(alias, vin, if (vin.isNullOrBlank()) null else VinSource.MANUAL)
    }

    fun select(id: String) = save("Vehicle selected.") { repository.select(id) }

    fun rename(id: String, alias: String) = save("Vehicle name saved.") { repository.rename(id, alias) }

    fun attachManualVin(id: String, vin: String) = save("VIN saved. The vehicle ID is unchanged.") {
        repository.attachVin(id, vin, VinSource.MANUAL)
    }

    /** Called only when the user chooses a current candidate from the Adapter screen. */
    fun offerObdVin(candidate: VinResponse): Boolean {
        if (mutableState.value.isLoading || mutableState.value.isSaving || Vin.normalize(candidate.vin) != candidate.vin) return false
        mutableState.value = mutableState.value.copy(pendingVin = candidate, message = null, matchingVehicleId = null)
        return true
    }

    fun attachDetectedVin(id: String) {
        val candidate = mutableState.value.pendingVin ?: return
        save("Detected VIN saved. The vehicle ID is unchanged.") {
            repository.attachVin(id, candidate.vin, VinSource.OBD, candidate.ecu)
        }
    }

    fun createDetectedVehicle(alias: String) {
        val candidate = mutableState.value.pendingVin ?: return
        save("Vehicle saved with the detected VIN.") {
            repository.create(alias, candidate.vin, VinSource.OBD, candidate.ecu)
        }
    }

    fun dismissDetectedVin() {
        if (!mutableState.value.isSaving) {
            mutableState.value = mutableState.value.copy(pendingVin = null, matchingVehicleId = null, message = null)
        }
    }

    private fun save(message: String, operation: suspend () -> VehicleState) {
        if (mutableState.value.isLoading || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, matchingVehicleId = null)
        viewModelScope.launch(dispatcher) {
            try {
                val saved = operation()
                val activeVin = saved.profiles.firstOrNull { it.id == saved.activeVehicleId }?.vin
                mutableState.value = mutableState.value.copy(
                    vehicles = saved,
                    isSaving = false,
                    message = message,
                    pendingVin = mutableState.value.pendingVin?.takeUnless { it.vin == activeVin },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val known = failure as? VehicleRepositoryException
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    matchingVehicleId = known?.takeIf { it.reason == VehicleFailure.VIN_ALREADY_ASSIGNED }?.vehicleId,
                    message = when (known?.reason) {
                        VehicleFailure.INVALID_ALIAS -> "Enter a vehicle name between 1 and 60 characters."
                        VehicleFailure.INVALID_VIN -> "Enter a 17-character VIN using letters and numbers, excluding I, O and Q."
                        VehicleFailure.VIN_ALREADY_ASSIGNED -> "That VIN already belongs to a saved vehicle. Select it explicitly to use it."
                        VehicleFailure.DIFFERENT_VIN -> "This vehicle already has a different VIN. Select the matching vehicle or create another profile."
                        VehicleFailure.NOT_FOUND -> "This vehicle could not be found. Reopen Vehicles and try again."
                        VehicleFailure.INVALID_PROVENANCE -> "The VIN source could not be verified. Read it again or enter it manually."
                        else -> "The vehicle could not be saved. Your existing profiles have not been replaced. Try again."
                    },
                )
            }
        }
    }
}
