package com.gonibix.rx8.vehicle

import com.gonibix.rx8.telemetry.obd.VinResponse
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = VehicleProfile("saved-id", "My RX-8", null, null, null, 123L)
    private val candidate = VinResponse("7E8", "TEST0000000000001")
    private val repository = FakeRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsSavedIdentityWithoutCreatingAnotherProfile() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        assertEquals(profile.id, model.uiState.value.vehicles.activeVehicleId)
        assertEquals(listOf("load"), repository.calls)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun detectedVinIsNotWrittenUntilExplicitlyAttachedAndKeepsTheId() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        assertTrue(model.offerObdVin(candidate))
        assertEquals(listOf("load"), repository.calls)
        repository.next = VehicleState(listOf(profile.copy(vin = candidate.vin, vinSource = VinSource.OBD, vinEcuId = "7E8")), profile.id)
        model.attachDetectedVin(profile.id)
        runCurrent()
        assertEquals(listOf("load", "attach:saved-id"), repository.calls)
        assertEquals(VinSource.OBD, repository.source)
        assertEquals("7E8", repository.ecu)
        assertEquals(profile.id, model.uiState.value.vehicles.activeVehicleId)
        assertNull(model.uiState.value.pendingVin)
    }

    @Test fun conflictingDetectedVinKeepsTheProfileAndRequiresASeparateChoice() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        model.offerObdVin(candidate)
        repository.failure = VehicleRepositoryException(VehicleFailure.DIFFERENT_VIN, profile.id, "Synthetic conflict")
        model.attachDetectedVin(profile.id)
        runCurrent()
        assertEquals(profile, model.uiState.value.vehicles.profiles.single())
        assertEquals(candidate, model.uiState.value.pendingVin)
        assertTrue(model.uiState.value.message!!.contains("different VIN"))
        assertFalse(model.uiState.value.message!!.contains(candidate.vin))
    }

    @Test fun duplicateVinOffersMatchingProfileWithoutSelectingItAutomatically() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        model.offerObdVin(candidate)
        repository.failure = VehicleRepositoryException(VehicleFailure.VIN_ALREADY_ASSIGNED, "other-id", "Synthetic duplicate")
        model.createDetectedVehicle("Other vehicle")
        runCurrent()
        assertEquals(profile.id, model.uiState.value.vehicles.activeVehicleId)
        assertEquals("other-id", model.uiState.value.matchingVehicleId)
        assertFalse(repository.calls.any { it.startsWith("select") })
        repository.failure = null
        repository.next = VehicleState(listOf(profile.copy(id = "other-id", vin = candidate.vin, vinSource = VinSource.MANUAL)), "other-id")
        model.select("other-id")
        runCurrent()
        assertEquals("other-id", model.uiState.value.vehicles.activeVehicleId)
        assertNull(model.uiState.value.pendingVin)
    }

    @Test fun storageFailureNeverPublishesAnUnsavedProfile() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        repository.failure = IOException("Synthetic storage failure")
        repository.next = VehicleState(listOf(profile.copy(id = "unsaved-id")), "unsaved-id")
        model.create("New vehicle", null)
        runCurrent()
        assertEquals(profile.id, model.uiState.value.vehicles.activeVehicleId)
        assertFalse(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.message!!.contains("could not be saved"))
    }

    @Test fun repeatedSaveAndCandidateChangesAreIgnoredWhileAWriteIsPending() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        repository.gate = CompletableDeferred()
        model.create("New vehicle", null)
        model.create("Duplicate click", null)
        assertFalse(model.offerObdVin(candidate))
        runCurrent()
        assertEquals(listOf("load", "create"), repository.calls)
        assertTrue(model.uiState.value.isSaving)
        repository.gate!!.complete(Unit)
        runCurrent()
        assertFalse(model.uiState.value.isSaving)
    }

    @Test fun vehicleWithoutVinCanBeCreatedWithoutFabricatedProvenance() = runTest(dispatcher) {
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        model.create("My RX-8", null)
        runCurrent()
        assertNull(repository.source)
        assertNull(repository.ecu)
        assertEquals("Vehicle saved.", model.uiState.value.message)
    }

    @Test fun failedLoadCanBeRetriedWithoutCreatingAnIdentity() = runTest(dispatcher) {
        repository.failure = IOException("Synthetic unavailable storage")
        val model = VehicleProfileViewModel(repository, dispatcher)
        runCurrent()
        assertTrue(model.uiState.value.vehicles.profiles.isEmpty())
        repository.failure = null
        model.reload()
        runCurrent()
        assertEquals(profile.id, model.uiState.value.vehicles.activeVehicleId)
        assertEquals(listOf("load", "load"), repository.calls)
    }

    private inner class FakeRepository : VehicleRepository {
        var next = VehicleState(listOf(profile), profile.id)
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        var source: VinSource? = null
        var ecu: String? = null
        private suspend fun respond(call: String): VehicleState {
            calls += call
            gate?.await()
            failure?.let { throw it }
            return next
        }
        override suspend fun load() = respond("load")
        override suspend fun create(alias: String, vin: String?, vinSource: VinSource?, vinEcuId: String?): VehicleState {
            source = vinSource; ecu = vinEcuId
            return respond("create")
        }
        override suspend fun select(vehicleId: String) = respond("select:$vehicleId")
        override suspend fun rename(vehicleId: String, alias: String) = respond("rename:$vehicleId")
        override suspend fun attachVin(vehicleId: String, vin: String, vinSource: VinSource, vinEcuId: String?): VehicleState {
            source = vinSource; ecu = vinEcuId
            return respond("attach:$vehicleId")
        }
    }
}
