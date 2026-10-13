package com.gonibix.rx8.trips

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.ui.theme.Rx8Theme
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.vehicle.VehicleEntity
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic history and profiles only; these tests never connect to or identify a real vehicle. */
@RunWith(AndroidJUnit4::class)
class TripHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pagingAndRefreshHaveSeparateExplicitActions() {
        val calls = mutableListOf<String>()
        show { Content(history(), onNewer = { calls += "newer" }, onOlder = { calls += "older" }, onNewest = { calls += "newest" }, onRefresh = { calls += "refresh" }) }
        assertTrue(calls.isEmpty())
        scroll("newer_trips").performClick()
        compose.onNodeWithTag("older_trips").performClick()
        compose.onNodeWithTag("newest_trips").performClick()
        scroll("refresh_trips").performClick()
        assertEquals(listOf("newer", "older", "newest", "refresh"), calls)
        compose.onAllNodesWithTag("trip_history_navigation").assertCountEquals(1)
        scroll("trip_details_${trip(1).id}").assertIsDisplayed()
        compose.onNodeWithTag("newer_trips_footer").assertDoesNotExist()
        compose.onNodeWithTag("older_trips_footer").assertDoesNotExist()
        compose.onNodeWithTag("newest_trips_footer").assertDoesNotExist()
    }

    @Test fun unavailableDirectionsAreDisabledAndBusyFilterCanCancelLoading() {
        var state by mutableStateOf(history().copy(hasNewerTrips = false))
        val filters = mutableListOf<String?>()
        show { Content(state, onFilter = { filters += it }) }
        scroll("newer_trips").assertIsNotEnabled()
        compose.onNodeWithTag("older_trips").assertIsEnabled()
        compose.onNodeWithTag("newest_trips").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(isLoading = true) }
        compose.onNodeWithTag("older_trips").assertIsNotEnabled()
        compose.onNodeWithTag("newest_trips").assertIsNotEnabled()
        scroll("refresh_trips").assertIsNotEnabled()
        scroll("history_vehicle_filter").assertIsEnabled().performClick()
        compose.onNodeWithTag("history_vehicle_${second.id}").performClick()
        assertEquals(listOf(second.id), filters)
    }

    @Test fun onePageHasAnExplanationAndRefreshWithoutInventingNavigation() {
        var state by mutableStateOf(history().copy(hasNewerTrips = false, hasOlderTrips = false))
        var refreshes = 0
        show { Content(state, onRefresh = { refreshes++ }) }
        scroll("history_page_status").assertTextEquals("All saved trips are shown.")
        compose.onNodeWithTag("trip_history_navigation").assertDoesNotExist()
        compose.onNodeWithTag("newer_trips").assertDoesNotExist()
        compose.onNodeWithTag("older_trips").assertDoesNotExist()
        compose.onNodeWithTag("newest_trips").assertDoesNotExist()
        scroll("refresh_trips").assertIsEnabled().performClick()
        assertEquals(1, refreshes)

        compose.runOnIdle { state = state.copy(trips = (1..50).map(::trip), historyVehicleId = first.id) }
        scroll("history_page_status").assertTextEquals("All saved trips for this vehicle are shown.")
        compose.onNodeWithTag("trip_history_navigation").assertDoesNotExist()
        scroll("history_vehicle_filter").assertIsEnabled()
        compose.runOnIdle { state = state.copy(trips = emptyList()) }
        scroll("empty_trips").assertIsDisplayed()
        compose.onNodeWithTag("history_page_status").assertDoesNotExist()
        compose.onNodeWithTag("trip_history_navigation").assertDoesNotExist()
        scroll("refresh_trips").assertIsEnabled()
    }

    @Test fun pageBoundariesDescribeOnlyTheAvailableDirections() {
        var state by mutableStateOf(history().copy(hasNewerTrips = false))
        show { Content(state) }
        scroll("history_page_status").assertTextEquals("Newest trips · older trips are available.")
        scroll("newest_trips").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(hasNewerTrips = true) }
        scroll("history_page_status").assertTextEquals("More trips are available in both directions.")
        scroll("newer_trips").assertIsEnabled()
        compose.onNodeWithTag("older_trips").assertIsEnabled()
        compose.onNodeWithTag("newest_trips").assertIsEnabled()
        compose.runOnIdle { state = state.copy(hasOlderTrips = false) }
        scroll("history_page_status").assertTextEquals("Oldest trips · no older trips.")
        scroll("older_trips").assertIsNotEnabled()
        compose.onNodeWithTag("newer_trips").assertIsEnabled()
        compose.onNodeWithTag("newest_trips").assertIsEnabled()
    }

    @Test fun loadingOrFailedHistoryDoesNotClaimThatEveryTripWasLoaded() {
        var state by mutableStateOf(history().copy(hasNewerTrips = false, hasOlderTrips = false))
        show { Content(state) }
        scroll("history_page_status").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isLoading = true) }
        scroll("history_loading").assertIsDisplayed()
        compose.onNodeWithTag("history_page_status").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false, historyError = "History unavailable") }
        scroll("history_previous_results").assertIsDisplayed()
        compose.onNodeWithTag("history_page_status").assertDoesNotExist()
        scroll("retry_trip_history").assertIsEnabled()
    }

    @Test fun deletingOrExportingPreventsNavigationWithoutTriggeringCallbacks() {
        var state by mutableStateOf(history().copy(isDeleting = true))
        var navigations = 0
        show { Content(state, onNewer = { navigations++ }, onOlder = { navigations++ }, onNewest = { navigations++ }) }
        scroll("newer_trips").assertIsNotEnabled()
        compose.onNodeWithTag("older_trips").assertIsNotEnabled()
        compose.onNodeWithTag("newest_trips").assertIsNotEnabled()
        scroll("history_vehicle_filter").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(isDeleting = false, isExporting = true) }
        scroll("newer_trips").assertIsNotEnabled()
        compose.onNodeWithTag("older_trips").assertIsNotEnabled()
        compose.onNodeWithTag("newest_trips").assertIsNotEnabled()
        scroll("refresh_trips").assertIsNotEnabled()
        assertEquals(0, navigations)
    }

    @Test fun realHistoryButtonsTraverseRoomPagesThroughTheViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VehicleDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            // Equal start times straddle page boundaries, so a timestamp-only cursor would lose rows.
            runBlocking {
                database.withTransaction {
                    database.vehicles().insert(VehicleEntity(first.id, first.alias, null, null, null, first.createdAtEpochMillis))
                    (1..101).forEach { number ->
                        val started = 10_000L + number / 70 * 1_000L
                        database.trips().insertSession(TripSessionEntity(
                            id = UUID(0, number.toLong()).toString(), vehicleId = first.id,
                            vehicleAlias = "Recorded fixture $number", startedAtEpochMillis = started,
                            endedAtEpochMillis = started + 100L, durationMillis = 100L,
                            status = TripStatus.COMPLETED.name, endReason = "Stopped by user",
                            protocolLabel = null, origin = "REAL_OBD",
                        ))
                    }
                }
            }
            val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == TripsViewModel::class.java)
                    return TripsViewModel(RoomTripRepository(database)) as T
                }
            })[TripsViewModel::class.java]
            show {
                TripsScreen(model, rememberTripExportActions(model), first, TripRecordingState(), false,
                    {}, {}, {}, {}, historyVehicles = listOf(first))
            }
            fun awaitPage(numbers: IntProgression) {
                val expected = numbers.map { UUID(0, it.toLong()).toString() }
                compose.waitUntil(10_000L) {
                    !model.uiState.value.isLoading && model.uiState.value.trips.map { it.id } == expected
                }
                scroll("trip_details_${expected.first()}").assertIsDisplayed()
                assertEquals(expected, model.uiState.value.trips.map { it.id })
            }
            awaitPage(101 downTo 52)
            scroll("history_page_status").assertTextEquals("Newest trips · older trips are available.")
            scroll("older_trips").assertIsEnabled().performClick()
            awaitPage(51 downTo 2)
            scroll("history_page_status").assertTextEquals("More trips are available in both directions.")
            scroll("older_trips").performClick()
            awaitPage(1 downTo 1)
            scroll("history_page_status").assertTextEquals("Oldest trips · no older trips.")
            scroll("older_trips").assertIsNotEnabled()
            compose.onNodeWithTag("newer_trips").performClick()
            awaitPage(51 downTo 2)
            scroll("newest_trips").performClick()
            awaitPage(101 downTo 52)
            scroll("newest_trips").assertIsNotEnabled()
            compose.onAllNodesWithTag("trip_history_navigation").assertCountEquals(1)
            scroll("trip_details_${UUID(0, 52L)}").assertIsDisplayed()
            compose.onNodeWithTag("older_trips_footer").assertDoesNotExist()
        } finally {
            compose.runOnIdle { models.clear() }
            database.close()
        }
    }

    @Test fun identicalCurrentNamesHaveFullIdsAndFilterDoesNotRelabelRecordedTrips() {
        var state by mutableStateOf(history().copy(trips = listOf(trip(1).copy(vehicleAlias = "Name at recording"))))
        var stops = 0
        show {
            Content(state, recording = TripRecordingState(session = trip(90).copy(status = TripStatus.RECORDING)),
                onFilter = { state = state.copy(historyVehicleId = it, historyRevision = state.historyRevision + 1) }, onStop = { stops++ })
        }
        scroll("history_vehicle_filter").performClick()
        compose.onNodeWithText("ID: ${first.id}").assertIsDisplayed()
        compose.onNodeWithText("ID: ${second.id}").assertIsDisplayed()
        compose.onNodeWithTag("history_vehicle_${second.id}").performClick()
        compose.onNodeWithTag("history_vehicle_id").assertTextEquals("Vehicle ID: ${second.id}")
        scroll("trip_details_${trip(1).id}").assertIsDisplayed()
        compose.onNodeWithText("Name at recording").assertIsDisplayed()
        scroll("recording_vehicle").assertTextEquals("Recording for Recorded fixture 90")
        compose.onNodeWithTag("stop_recording").assertIsEnabled().performClick()
        assertEquals(1, stops)
    }

    @Test fun allVehiclesIsDefaultAndChoosingItDoesNotSelectARecordingProfile() {
        var state by mutableStateOf(history())
        val starts = mutableListOf<VehicleProfile>()
        show { Content(state, onFilter = { state = state.copy(historyVehicleId = it) }, onStart = { starts += it }) }
        scroll("history_vehicle_filter").assertTextEquals("History: All vehicles").performClick()
        compose.onNodeWithTag("history_vehicle_${second.id}").performClick()
        scroll("history_vehicle_filter").performClick()
        compose.onNodeWithTag("history_vehicle_all").performClick()
        scroll("start_recording").performClick()
        assertEquals(listOf(first), starts)
    }

    @Test fun historyFailureRetainsLabelledRowsAndRetryWithoutAnEmptyClaim() {
        var retries = 0
        show { Content(history().copy(historyError = "Saved trips could not be loaded."), onRetry = { retries++ }) }
        scroll("history_error").assertTextEquals("Saved trips could not be loaded.")
        scroll("history_previous_results").assertIsDisplayed()
        compose.onNodeWithTag("empty_trips").assertDoesNotExist()
        scroll("trip_details_${trip(1).id}").assertIsEnabled()
        scroll("retry_trip_history").performClick()
        assertEquals(1, retries)
    }

    @Test fun emptyFilterLoadingAndFailureAreDistinct() {
        var state by mutableStateOf(history().copy(trips = emptyList(), historyVehicleId = second.id))
        show { Content(state) }
        scroll("empty_trips").assertTextEquals("No saved trips for this vehicle. Choose All vehicles to browse other recordings.")
        compose.runOnIdle { state = state.copy(isLoading = true) }
        scroll("history_loading").assertTextEquals("Loading saved trips…")
        compose.onNodeWithTag("empty_trips").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false, historyError = "History unavailable") }
        scroll("history_error").assertIsDisplayed()
        compose.onNodeWithTag("empty_trips").assertDoesNotExist()
        compose.onNodeWithTag("history_loading").assertDoesNotExist()
    }

    @Test fun storageFailureDoesNotHideHistoryOrInventAZero() {
        show { Content(history().copy(storageBytes = null, storageError = "Database size could not be read.")) }
        scroll("trip_storage").assertTextEquals("App database: Not available")
        scroll("trip_storage_error").assertIsDisplayed()
        scroll("trip_details_${trip(1).id}").assertIsEnabled()
    }

    @Test fun filterAndPageChangesDismissPendingDeletion() {
        var state by mutableStateOf(history())
        var deletions = 0
        show { Content(state, onDelete = { deletions++ }) }
        scroll("trip_delete_${trip(1).id}").performClick()
        compose.onNodeWithTag("confirm_delete_trip").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(historyVehicleId = second.id) }
        compose.onNodeWithTag("confirm_delete_trip").assertDoesNotExist()
        scroll("trip_delete_${trip(1).id}").performClick()
        compose.runOnIdle { state = state.copy(historyRevision = state.historyRevision + 1) }
        compose.onNodeWithTag("confirm_delete_trip").assertDoesNotExist()
        assertEquals(0, deletions)
    }

    @Test fun detailAndBackKeepTheHistoryScrollAndRefreshDoesNotResetIt() {
        var state by mutableStateOf(history().copy(trips = (1..40).map(::trip)))
        show { Content(state, onSelect = { state = state.copy(selectedTripId = it) }, onBack = { state = state.copy(selectedTripId = null) }) }
        scroll("trip_details_${trip(25).id}").performClick()
        scroll("back_to_trips").performClick()
        compose.onNodeWithTag("trip_details_${trip(25).id}").assertIsDisplayed()
        val position = scrollPosition()
        compose.runOnIdle { state = state.copy(isLoading = true) }
        compose.runOnIdle { state = state.copy(isLoading = false) }
        assertEquals(position, scrollPosition(), 0.01f)
        compose.onNodeWithTag("trip_details_${trip(25).id}").assertIsDisplayed()
    }

    @Test fun saveableHistorySurvivesTabDisposalAndCompositionRestoration() {
        var visible by mutableStateOf(true)
        val state = history().copy(trips = (1..40).map(::trip), historyVehicleId = second.id, historyRevision = 3)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Rx8Theme {
                val tabs = rememberSaveableStateHolder()
                if (visible) tabs.SaveableStateProvider("trips") { Frame { Content(state) } }
                else Text("Synthetic other tab")
            }
        }
        scroll("trip_details_${trip(25).id}").assertIsDisplayed()
        val position = scrollPosition()
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("Synthetic other tab").assertIsDisplayed()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("trip_details_${trip(25).id}").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("trip_details_${trip(25).id}").assertIsDisplayed()
        assertEquals(position, scrollPosition(), 0.01f)
        scroll("history_vehicle_id").assertTextEquals("Vehicle ID: ${second.id}")
    }

    @Test fun explicitPageNavigationReturnsToHistoryControls() {
        var state by mutableStateOf(history().copy(trips = (1..40).map(::trip)))
        show { Content(state) }
        scroll("trip_details_${trip(25).id}").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(trips = (41..80).map(::trip), historyRevision = 1) }
        compose.onNodeWithTag("history_vehicle_filter").assertIsDisplayed()
        compose.onNodeWithTag("trip_details_${trip(25).id}").assertDoesNotExist()
    }

    @Test fun olderHistoryScreenshotContainsSyntheticLabelAndRenderedContent() {
        show {
            Content(history().copy(trips = (101..150).map(::trip), historyVehicleId = first.id),
                recording = TripRecordingState(session = trip(999).copy(status = TripStatus.RECORDING)))
        }
        scroll("history_vehicle_filter").assertIsDisplayed()
        compose.onNodeWithTag("newer_trips").assertIsEnabled()
        compose.onNodeWithTag("older_trips").assertIsEnabled()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC TRIP HISTORY").assertIsDisplayed()
        captureHistory()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { Rx8Theme { Frame(content) } }
    }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.safeDrawingPadding()) {
                Text("UI TEST FIXTURE · SYNTHETIC TRIP HISTORY", style = MaterialTheme.typography.labelMedium)
                content()
            }
        }
    }

    @Composable private fun Content(
        state: TripsUiState,
        recording: TripRecordingState = TripRecordingState(),
        onFilter: (String?) -> Unit = {},
        onNewer: () -> Unit = {},
        onOlder: () -> Unit = {},
        onNewest: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDelete: (String) -> Unit = {},
        onStart: (VehicleProfile) -> Unit = {},
        onStop: () -> Unit = {},
        onSelect: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        TripsContent(
            state, first, recording, true, onStart, onStop, {}, {}, onRefresh, onSelect, onBack, onDelete, { _, _ -> },
            historyVehicles = listOf(first, second), onSelectHistoryVehicle = onFilter,
            onNewerTrips = onNewer, onOlderTrips = onOlder, onNewestTrips = onNewest, onRetryHistory = onRetry,
        )
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("trips_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("trips_screen").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun captureHistory() {
        compose.waitForIdle()
        var rendered: Bitmap? = null
        compose.waitUntil(5_000L) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (hasContent(bitmap)) { rendered = bitmap; true } else { bitmap.recycle(); false }
        }
        val bitmap = requireNotNull(rendered)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        try {
            val source = File(directory, "trip-history.png")
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/trip-history.png /data/local/tmp/rx8-trip-history.png")).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-trip-history.png")).use { it.readBytes() }
            assertArrayEquals(source.readBytes(), copied)
        } finally { bitmap.recycle() }
    }

    private fun hasContent(bitmap: Bitmap): Boolean {
        var dark = 0
        var light = 0
        var samples = 0
        for (y in 0 until bitmap.height step (bitmap.height / 96).coerceAtLeast(1)) {
            for (x in 0 until bitmap.width step (bitmap.width / 96).coerceAtLeast(1)) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
                samples++
                if ((pixel ushr 24 and 255) > 127 && brightness < 96) dark++
                if ((pixel ushr 24 and 255) > 127 && brightness > 160) light++
            }
        }
        return samples > 0 && dark > samples / 10 && light >= 8
    }

    private fun history() = TripsUiState(trips = listOf(trip(1)), isLoading = false, isLoadingStorage = false, storageBytes = 1024, hasNewerTrips = true, hasOlderTrips = true)

    private companion object {
        // Same display name and prefix deliberately exercise complete identity disambiguation.
        val first = VehicleProfile("00000000-0000-4000-8000-000000000031", "Current fixture RX-8", null, null, null, 1_791_676_800_000)
        val second = first.copy(id = "00000000-0000-4000-8000-000000000032")
        fun trip(index: Int) = TripSession("synthetic-history-$index", first.id, "Recorded fixture $index", 1_791_676_800_000L - index * 60_000L,
            endedAtEpochMillis = 1_791_676_830_000L - index * 60_000L, durationMillis = 30_000, status = TripStatus.COMPLETED)
    }
}
