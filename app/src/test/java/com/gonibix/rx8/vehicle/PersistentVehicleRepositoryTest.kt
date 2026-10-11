package com.gonibix.rx8.vehicle

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentVehicleRepositoryTest {
    @Test
    fun `loading an empty store never creates a persistent identity`() = runTest {
        val store = MemoryVehicleStore()
        var generated = 0
        val repository = PersistentVehicleRepository(store, newId = { generated++; UUID.randomUUID().toString() })

        assertEquals(VehicleState(), repository.load())
        assertEquals(VehicleState(), repository.load())
        assertEquals(0, generated)
    }

    @Test
    fun `explicit creation assigns a UUID once and activates the profile without a VIN`() = runTest {
        val store = MemoryVehicleStore()
        val repository = repository(store)
        val created = repository.create("  Test RX-8  ")
        val profile = created.profiles.single()

        assertEquals(profile.id, UUID.fromString(profile.id).toString())
        assertEquals(profile.id, created.activeVehicleId)
        assertEquals("Test RX-8", profile.alias)
        assertNull(profile.vin)
        assertNull(profile.vinSource)
        assertNull(profile.vinEcuId)
        assertEquals(1_700_000_000_000L, profile.createdAtEpochMillis)
        assertEquals(created, PersistentVehicleRepository(store).load())
    }

    @Test
    fun `select and rename preserve each independent UUID and the other active profile`() = runTest {
        val repository = repository()
        val first = repository.create("First").profiles.single()
        val secondState = repository.create("Second")
        val second = secondState.profiles.single { it.id != first.id }
        assertNotEquals(first.id, second.id)

        val renamed = repository.rename(first.id, "Renamed")
        assertEquals(second.id, renamed.activeVehicleId)
        assertEquals(first.copy(alias = "Renamed"), renamed.profiles.single { it.id == first.id })
        assertEquals(first.id, repository.select(first.id).activeVehicleId)
    }

    @Test
    fun `adding a normalized manual VIN preserves UUID and creation time`() = runTest {
        val repository = repository()
        val original = repository.create("Test vehicle").profiles.single()
        val attached = repository.attachVin(original.id, "  ${VIN_A.lowercase()}  ", VinSource.MANUAL)
        val profile = attached.profiles.single()

        assertEquals(original.id, profile.id)
        assertEquals(original.createdAtEpochMillis, profile.createdAtEpochMillis)
        assertEquals(VIN_A, profile.vin)
        assertEquals(VinSource.MANUAL, profile.vinSource)
        assertNull(profile.vinEcuId)
        assertEquals(original.id, attached.activeVehicleId)
    }

    @Test
    fun `OBD provenance retains the canonical responding ECU`() = runTest {
        val repository = repository()
        val profile = repository.create("Detected vehicle", VIN_A, VinSource.OBD, " 7e8 ").profiles.single()

        assertEquals(VinSource.OBD, profile.vinSource)
        assertEquals("7E8", profile.vinEcuId)
        assertEquals(VIN_A, profile.vin)
    }

    @Test
    fun `duplicate VIN creation returns matching profile and preserves active selection`() = runTest {
        val repository = repository()
        val first = repository.create("First", VIN_A, VinSource.MANUAL).profiles.single()
        val before = repository.create("Second")

        val failure = expectFailure(VehicleFailure.VIN_ALREADY_ASSIGNED) { repository.create("Duplicate", VIN_A.lowercase(), VinSource.MANUAL) }
        assertEquals(first.id, failure.vehicleId)
        assertEquals(before, repository.load())
    }

    @Test
    fun `duplicate attachment cannot merge or replace profiles`() = runTest {
        val repository = repository()
        val first = repository.create("First", VIN_A, VinSource.MANUAL).profiles.single()
        val before = repository.create("Second")
        val second = before.profiles.single { it.id != first.id }

        val failure = expectFailure(VehicleFailure.VIN_ALREADY_ASSIGNED) { repository.attachVin(second.id, VIN_A, VinSource.OBD, "7E8") }
        assertEquals(first.id, failure.vehicleId)
        assertEquals(before, repository.load())
    }

    @Test
    fun `a different VIN can never overwrite an existing identity`() = runTest {
        val repository = repository()
        val before = repository.create("First", VIN_A, VinSource.MANUAL)
        val target = before.profiles.single()

        val failure = expectFailure(VehicleFailure.DIFFERENT_VIN) { repository.attachVin(target.id, VIN_B, VinSource.OBD, "7E8") }
        assertEquals(target.id, failure.vehicleId)
        assertEquals(before, repository.load())
    }

    @Test
    fun `identical VIN attachment is a no-op and preserves original provenance`() = runTest {
        val repository = repository()
        val before = repository.create("First", VIN_A, VinSource.MANUAL)

        assertEquals(before, repository.attachVin(before.activeVehicleId!!, VIN_A.lowercase(), VinSource.OBD, "7E8"))
        assertEquals(before, repository.load())
    }

    @Test
    fun `invalid aliases and VINs cannot leave partial profiles`() = runTest {
        val repository = repository()
        for (alias in listOf("", "   ", "x".repeat(61), "Two\nlines")) {
            expectFailure(VehicleFailure.INVALID_ALIAS) { repository.create(alias) }
        }
        for (vin in listOf("SHORT", "0000000000000000I", "0000000000000000Q")) {
            expectFailure(VehicleFailure.INVALID_VIN) { repository.create("Test", vin, VinSource.MANUAL) }
        }
        assertEquals(VehicleState(), repository.load())
        assertEquals("x".repeat(60), repository.create("x".repeat(60)).profiles.single().alias)
    }

    @Test
    fun `missing or contradictory provenance is never stored`() = runTest {
        val repository = repository()
        expectFailure(VehicleFailure.INVALID_PROVENANCE) { repository.create("Test", VIN_A) }
        expectFailure(VehicleFailure.INVALID_PROVENANCE) { repository.create("Test", vinSource = VinSource.MANUAL) }
        expectFailure(VehicleFailure.INVALID_PROVENANCE) { repository.create("Test", VIN_A, VinSource.MANUAL, "7E8") }
        expectFailure(VehicleFailure.INVALID_PROVENANCE) { repository.create("Test", VIN_A, VinSource.OBD) }
        expectFailure(VehicleFailure.INVALID_PROVENANCE) { repository.create("Test", VIN_A, VinSource.OBD, "AA:BB:CC:DD:EE:FF") }
        assertEquals(VehicleState(), repository.load())
    }

    @Test
    fun `missing profile operations leave active selection intact`() = runTest {
        val repository = repository()
        val before = repository.create("Existing")
        expectFailure(VehicleFailure.NOT_FOUND) { repository.select("missing") }
        expectFailure(VehicleFailure.NOT_FOUND) { repository.rename("missing", "New name") }
        expectFailure(VehicleFailure.NOT_FOUND) { repository.attachVin("missing", VIN_A, VinSource.MANUAL) }
        assertEquals(before, repository.load())
    }

    @Test
    fun `failed active selection rolls back a newly inserted profile and surfaces storage error`() = runTest {
        val store = MemoryVehicleStore()
        val repository = repository(store)
        val before = repository.create("Existing")
        store.failSetActive = IllegalStateException("Synthetic disk failure")

        val failure = expectFailure(VehicleFailure.STORAGE_ERROR) { repository.create("Uncommitted") }
        assertTrue(failure.cause is IllegalStateException)
        store.failSetActive = null
        assertEquals(before, repository.load())
    }

    @Test
    fun `read failures do not turn into empty successful state`() = runTest {
        val store = MemoryVehicleStore()
        val repository = repository(store)
        val before = repository.create("Existing")
        store.failRead = true

        expectFailure(VehicleFailure.STORAGE_ERROR) { repository.load() }
        store.failRead = false
        assertEquals(before, repository.load())
    }

    @Test
    fun `transaction cancellation remains cancellation and rolls back creation`() = runTest {
        val store = MemoryVehicleStore()
        val repository = repository(store)
        val before = repository.create("Existing")
        store.failSetActive = CancellationException("Synthetic cancellation")

        assertTrue(runCatching { repository.create("Cancelled") }.exceptionOrNull() is CancellationException)
        store.failSetActive = null
        assertEquals(before, repository.load())
    }

    @Test
    fun `concurrent requests for one VIN result in one permanent profile`() = runTest {
        val store = MemoryVehicleStore()
        val first = PersistentVehicleRepository(store)
        val second = PersistentVehicleRepository(store)
        val attempts = listOf(first, second).mapIndexed { index, repository ->
            async { runCatching { repository.create("Attempt $index", VIN_A, VinSource.MANUAL) } }
        }.awaitAll()

        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(VehicleFailure.VIN_ALREADY_ASSIGNED, (attempts.single { it.isFailure }.exceptionOrNull() as VehicleRepositoryException).reason)
        val saved = first.load()
        assertEquals(1, saved.profiles.size)
        assertEquals(saved.profiles.single().id, saved.activeVehicleId)
    }

    private fun repository(store: MemoryVehicleStore = MemoryVehicleStore()): VehicleRepository {
        var sequence = 0L
        return PersistentVehicleRepository(store, newId = { UUID(0L, ++sequence).toString() }, epochMillis = { 1_700_000_000_000L })
    }

    private suspend fun expectFailure(reason: VehicleFailure, action: suspend () -> Unit): VehicleRepositoryException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a repository failure, got $failure", failure is VehicleRepositoryException)
        return (failure as VehicleRepositoryException).also { assertEquals(reason, it.reason) }
    }

    private class MemoryVehicleStore : VehicleStore, VehicleTransaction {
        private val mutex = Mutex()
        private var profiles = linkedMapOf<String, VehicleProfile>()
        private var active: String? = null
        var failSetActive: Exception? = null
        var failRead = false

        override suspend fun <T> transaction(block: suspend VehicleTransaction.() -> T): T = mutex.withLock {
            val before = LinkedHashMap(profiles)
            val selected = active
            try {
                block(this)
            } catch (failure: Throwable) {
                profiles = before
                active = selected
                throw failure
            }
        }

        override suspend fun profiles(): List<VehicleProfile> {
            if (failRead) error("Synthetic read failure")
            return profiles.values.toList()
        }
        override suspend fun activeVehicleId(): String? = active
        override suspend fun find(vehicleId: String): VehicleProfile? = profiles[vehicleId]
        override suspend fun findVin(vin: String): VehicleProfile? = profiles.values.singleOrNull { it.vin == vin }
        override suspend fun insert(profile: VehicleProfile) { check(profile.id !in profiles); profiles[profile.id] = profile }
        override suspend fun update(profile: VehicleProfile) { check(profile.id in profiles); profiles[profile.id] = profile }
        override suspend fun setActive(vehicleId: String) {
            failSetActive?.let { throw it }
            check(vehicleId in profiles)
            active = vehicleId
        }
    }

    private companion object {
        // Synthetic fixtures, not a real vehicle identity.
        const val VIN_A = "TESTCAR0000000001"
        const val VIN_B = "TESTCAR0000000002"
    }
}
