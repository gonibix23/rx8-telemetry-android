package com.gonibix.rx8.trips

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Intercepts SAF results; no external document app or real destination is opened in these tests. */
@RunWith(AndroidJUnit4::class)
class TripExportActionsTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    @After fun tearDown() { compose.runOnIdle { models.clear() } }

    @Test fun pendingPickerSurvivesTripsTabDisposalAndReceivesCancellation() {
        val repository = ExportRepository()
        val model = viewModel(repository)
        val registry = ControlledRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var showTrips by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Rx8Theme {
                    val actions = rememberTripExportActions(model)
                    Text(if (actions.isPending) "Picker pending" else "No pending picker", Modifier.testTag("picker_state"))
                    if (showTrips) {
                        Button(onClick = { actions.launch(trip, TripExportFormat.CSV) }, modifier = Modifier.testTag("launch_fixture_export")) { Text("Export fixture") }
                    } else Text("Adapter fixture", Modifier.testTag("adapter_fixture"))
                }
            }
        }
        compose.onNodeWithTag("launch_fixture_export").performClick()
        assertEquals("RX8-trip-${trip.id}.csv", registry.fileName)
        compose.onNodeWithTag("picker_state").assertTextEquals("Picker pending")
        compose.runOnIdle { showTrips = false }
        compose.onNodeWithTag("adapter_fixture").assertExists()
        compose.onNodeWithTag("picker_state").assertTextEquals("Picker pending")
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_CANCELED, null)) }
        compose.onNodeWithTag("picker_state").assertTextEquals("No pending picker")
        assertTrue(repository.findRequests.isEmpty())
        assertNull(model.uiState.value.message)
    }

    @Test fun restoredPendingRequestStillTargetsItsOriginalTripAndFormat() {
        val repository = ExportRepository()
        val model = viewModel(repository)
        val registry = ControlledRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        lateinit var actions: TripExportActions
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                actions = rememberTripExportActions(model)
                Text(if (actions.isPending) "Picker pending" else "No pending picker", Modifier.testTag("picker_state"))
            }
        }
        compose.runOnIdle { actions.launch(trip, TripExportFormat.JSON) }
        compose.runOnIdle { model.selectHistoryVehicle("different-fixture-vehicle") }
        compose.onNodeWithTag("picker_state").assertTextEquals("Picker pending")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("picker_state").assertTextEquals("Picker pending")
        compose.runOnIdle {
            // An intentionally absent provider verifies delivery/identity without writing a real document.
            val result = Intent().setData(Uri.parse("content://com.gonibix.rx8.test.unavailable/export.json"))
            assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_OK, result))
        }
        compose.waitUntil(5_000L) { repository.findRequests.isNotEmpty() && !model.uiState.value.isExporting }
        assertEquals(listOf(trip.id), repository.findRequests.toList())
        compose.runOnIdle {
            assertFalse(actions.isPending)
            assertNull(actions.error) // A missing restored trip or format would fail before calling the VM.
        }
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("Export could not be completed"))
    }

    private fun viewModel(repository: TripRepository): TripsViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TripsViewModel::class.java)
            return TripsViewModel(repository) as T
        }
    })[TripsViewModel::class.java]

    private class ControlledRegistry : ActivityResultRegistry() {
        var request = 0
        var fileName: String? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            fileName = input as? String
        }
    }

    private class ExportRepository : TripRepository {
        val findRequests = CopyOnWriteArrayList<String>()
        override suspend fun updateProtocol(tripId: String, protocolLabel: String) = Unit
        override suspend fun recoverInterrupted() = error("History must not recover recordings")
        override suspend fun create(session: TripSession) = error("Not recording")
        override suspend fun append(tripId: String, samples: List<TripSample>) = error("Not recording")
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) = error("Not recording")
        override suspend fun list(limit: Int) = listOf(trip)
        override suspend fun historyPage(request: TripHistoryRequest) = TripHistoryPage(
            listOf(trip).filter { request.vehicleId == null || it.vehicleId == request.vehicleId },
        )
        override suspend fun find(tripId: String): TripSession? { findRequests += tripId; return trip.takeIf { it.id == tripId } }
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = emptyList<TripSample>()
        override suspend fun delete(tripId: String) = error("Not deleting")
        override suspend fun storageBytes() = 0L
    }

    private companion object {
        val trip = TripSession("fixture-export-trip", "fixture-vehicle", "Fake trip", 1_000, 2_000, 1_000, TripStatus.COMPLETED)
    }
}
