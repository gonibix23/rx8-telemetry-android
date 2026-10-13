package com.gonibix.rx8.trips

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.sync.SyncQueueSummary
import com.gonibix.rx8.sync.SyncSettingsUiState
import com.gonibix.rx8.sync.UploadSettings
import com.gonibix.rx8.sync.UploadDeliveryCounts
import com.gonibix.rx8.sync.UploadDeliveryState
import com.gonibix.rx8.ui.theme.Rx8Theme
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All identifiers, trips, and readings here are synthetic UI fixtures. */
@RunWith(AndroidJUnit4::class)
class TripsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aVehicleAndRealAcquisitionAreRequiredBeforeExplicitStart() {
        var starts = 0
        show(selected = null, canStart = true, onStart = { starts++ })
        compose.onNodeWithTag("start_recording").assertIsNotEnabled()
        assertEquals(0, starts)
        compose.onNodeWithTag("trip_sync_status").assertDoesNotExist()
    }

    @Test fun idleAcquisitionCannotStartEvenWithASelectedVehicle() {
        show(canStart = false)
        compose.onNodeWithTag("start_recording").assertIsNotEnabled()
    }

    @Test fun manualResumeDelegatesSelectedProfileOnlyOnClick() {
        val starts = mutableListOf<VehicleProfile>()
        show(canStart = true, onStart = { starts += it })
        assertTrue(starts.isEmpty())
        compose.onNodeWithTag("start_recording").assertIsEnabled().performClick()
        assertEquals(listOf(vehicle), starts)
    }

    @Test fun automaticSessionGuidanceDoesNotInventARecording() {
        show(selected = null, automaticMessage = "Choose a saved vehicle to begin recording.")
        compose.onNodeWithTag("trip_automatic_message").assertTextEquals("Choose a saved vehicle to begin recording.")
        compose.onNodeWithTag("recording_vehicle").assertDoesNotExist()
        compose.onNodeWithTag("start_recording").assertIsNotEnabled()
    }

    @Test fun activeRecordingStopWaitsForServiceRebinding() {
        show(recording = TripRecordingState(session = trip.copy(status = TripStatus.RECORDING)), canStop = false)
        compose.onNodeWithTag("recording_vehicle").assertTextEquals("Recording for Fixture RX-8")
        compose.onNodeWithTag("stop_recording").assertIsNotEnabled()
    }

    @Test fun activeRecordingKeepsItsOriginalVehicleAndHasExplicitStop() {
        var stops = 0
        show(selected = vehicle.copy(id = "other-fake-vehicle", alias = "Other selection"), recording = TripRecordingState(session = trip.copy(status = TripStatus.RECORDING), savedSamples = 12), onStop = { stops++ })
        compose.onNodeWithTag("recording_vehicle").assertTextEquals("Recording for Fixture RX-8")
        compose.onNodeWithText("Changing the selected vehicle does not change this trip.").assertIsDisplayed()
        compose.onNodeWithTag("start_recording").assertDoesNotExist()
        assertEquals(0, stops)
        compose.onNodeWithTag("stop_recording").performClick()
        assertEquals(1, stops)
    }

    @Test fun activeHistoryCannotBeAnalyzedOrDeleted() {
        show(state = TripsUiState(trips = listOf(trip.copy(status = TripStatus.RECORDING)), isLoading = false))
        scrollToTag("trip_details_${trip.id}").assertIsNotEnabled()
        compose.onNodeWithTag("trip_delete_${trip.id}").assertIsNotEnabled()
    }

    @Test fun deletionRequiresConfirmationAndCanBeCancelled() {
        val deletions = mutableListOf<String>()
        show(state = history(), onDelete = { deletions += it })
        scrollToTag("trip_delete_${trip.id}").performClick()
        assertTrue(deletions.isEmpty())
        compose.onNodeWithText("Keep trip").performClick()
        assertTrue(deletions.isEmpty())
        compose.onNodeWithTag("trip_delete_${trip.id}").performClick()
        compose.onNodeWithTag("confirm_delete_trip").performClick()
        assertEquals(listOf(trip.id), deletions)
    }

    @Test fun detailsPreserveARealZeroEstimateAndExposeTheSpeedSource() {
        show(state = detailState())
        scrollToTag("trip_estimated_distance").assertTextEquals("Estimated distance: 0.000 km")
        compose.onNodeWithText("Calculated from valid speed intervals; this is not the odometer.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Speed summary source: ECU TEST_ECU").performScrollTo().assertIsDisplayed()
        scrollToTag("trip_speed_chart").assertIsDisplayed()
    }

    @Test fun exportsUseTheSelectedTripAndExplicitFormat() {
        val requests = mutableListOf<Pair<String, TripExportFormat>>()
        show(state = detailState(), onExport = { session, format -> requests += session.id to format })
        assertTrue(requests.isEmpty())
        scrollToTag("export_trip_json").performClick()
        compose.onNodeWithTag("export_trip_csv").performClick()
        assertEquals(listOf(trip.id to TripExportFormat.JSON, trip.id to TripExportFormat.CSV), requests)
    }

    @Test fun writingAnExportDisablesConflictingActionsAndDoesNotClaimSuccess() {
        show(state = detailState().copy(isExporting = true))
        scrollToTag("export_trip_json").assertIsNotEnabled()
        compose.onNodeWithTag("export_trip_csv").assertIsNotEnabled()
        compose.onNodeWithTag("delete_trip_details").assertIsNotEnabled()
        compose.onNodeWithTag("trips_message").assertDoesNotExist()
    }

    @Test fun recordingAndStorageErrorsAreVisible() {
        show(state = history().copy(error = "The history could not be loaded."), recording = TripRecordingState(error = "Recording stopped because storage failed."))
        compose.onNodeWithTag("recording_error").assertTextEquals("Recording stopped because storage failed.")
        scrollToTag("trips_error").assertTextEquals("The history could not be loaded.")
    }

    @Test fun initialHistoryFailureDoesNotClaimTheDatabaseIsEmpty() {
        show(state = TripsUiState(isLoading = false, historyError = "Saved trips could not be loaded."))
        scrollToTag("history_error").assertIsDisplayed()
        scrollToTag("refresh_trips").assertIsDisplayed()
        compose.onNodeWithTag("empty_trips").assertDoesNotExist()
    }

    @Test fun loadingUploadStatusDoesNotInventCountsOrHideHistory() {
        show(state = history().copy(syncStatus = TripSyncStatus()))
        scrollToTag("trip_sync_loading").assertTextEquals("Reading local status…")
        compose.onNodeWithTag("trip_sync_waiting").assertDoesNotExist()
        compose.onNodeWithTag("trip_sync_acknowledged").assertDoesNotExist()
        scrollToTag("trip_details_${trip.id}").assertIsEnabled()
    }

    @Test fun unavailableUploadStatusDoesNotClaimZeroOrDisableLocalActions() {
        val error = "Upload status could not be read. Refresh Trips to try again."
        show(state = history().copy(syncStatus = TripSyncStatus(isLoading = false, error = error)))
        scrollToTag("trip_sync_error").assertTextEquals(error)
        compose.onNodeWithTag("trip_sync_waiting").assertDoesNotExist()
        compose.onNodeWithTag("trip_sync_blocked").assertDoesNotExist()
        compose.onNodeWithTag("trip_sync_acknowledged").assertDoesNotExist()
        scrollToTag("trip_delete_${trip.id}").assertIsEnabled()
    }

    @Test fun confirmedCountRequiresAcknowledgedRecords() {
        show(state = history().copy(syncStatus = TripSyncStatus(summary = SyncQueueSummary(acknowledged = 4), isLoading = false)))
        scrollToTag("trip_sync_acknowledged").assertTextEquals("Confirmed by server: 4 samples")
        compose.onNodeWithText("Saved sample queue").assertIsDisplayed()
    }

    @Test fun uploadSettingsFailureDoesNotDisableRecordingOrLocalHistory() {
        show(
            state = history(),
            canStart = true,
            syncSettings = SyncSettingsUiState(isLoading = false, error = "Upload settings could not be loaded."),
        )
        compose.onNodeWithTag("start_recording").assertIsEnabled()
        scrollToTag("upload_settings_error").assertTextEquals("Upload settings could not be loaded.")
        scrollToTag("trip_details_${trip.id}").assertIsEnabled()
        scrollToTag("trip_delete_${trip.id}").assertIsEnabled()
    }

    @Test fun emptySampleQueueDoesNotClaimMetadataOrWholeTripDelivery() {
        show(state = history().copy(syncStatus = TripSyncStatus(summary = SyncQueueSummary(), isLoading = false)))
        scrollToTag("trip_sync_status").assertIsDisplayed()
        compose.onNodeWithTag("trip_sync_waiting").assertTextEquals("Waiting locally: 0 samples")
        compose.onNodeWithText("These counts describe samples, not complete trips or vehicle details. A zero waiting count does not confirm that everything was uploaded.").assertIsDisplayed()
        compose.onNodeWithTag("trip_sync_acknowledged").assertDoesNotExist()
    }

    @Test fun tripDeliveryIsForwardedOnceInsideUploadsAndKeepsHistoryUsable() {
        show(
            state = history(),
            syncSettings = SyncSettingsUiState(settings = UploadSettings("https://example.test", true), isLoading = false),
            uploadDelivery = UploadDeliveryState(UploadDeliveryCounts(1, 1, 0, 0)),
        )
        scrollToTag("upload_feedback_title").assertTextEquals("Trips uploaded successfully")
            .assert(hasAnyAncestor(hasTestTag("upload_settings")))
        compose.onAllNodesWithTag("upload_feedback").assertCountEquals(1)
        compose.onNodeWithTag("upload_server_url").assertDoesNotExist()
        scrollToTag("trip_details_${trip.id}").assertIsEnabled()
    }

    @Test fun deliveryDoesNotCreateABannerOutsideTheUploadsPanel() {
        show(state = history(), uploadDelivery = UploadDeliveryState(UploadDeliveryCounts(1, 1, 0, 0)))
        compose.onNodeWithTag("upload_feedback").assertDoesNotExist()
        scrollToTag("trip_details_${trip.id}").assertIsEnabled()
        compose.onNodeWithTag("upload_feedback").assertDoesNotExist()
    }

    @Test fun syntheticQueueScreenshotShowsWaitingAttentionAndUnconfiguredServer() {
        show(
            state = history().copy(syncStatus = TripSyncStatus(summary = SyncQueueSummary(pending = 12, sending = 3, blocked = 2), isLoading = false)),
            recording = TripRecordingState(session = trip.copy(status = TripStatus.RECORDING), savedSamples = 17),
            fixtureBanner = true,
            fixtureLabel = "UI TEST FIXTURE · SYNTHETIC QUEUE",
            syncSettings = SyncSettingsUiState(settings = UploadSettings(), isLoading = false),
            uploadDelivery = UploadDeliveryState(UploadDeliveryCounts(3, 0, 2, 1)),
        )
        scrollToTag("upload_settings").assertIsDisplayed()
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Server not configured").assertIsDisplayed()
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("1 trip needs attention").assertIsDisplayed()
        compose.onNodeWithText("Saved sample queue").assertIsDisplayed()
        compose.onNodeWithTag("trip_sync_waiting").assertTextEquals("Waiting locally: 15 samples").assertIsDisplayed()
        scrollToTag("trip_sync_blocked").assertTextEquals("Needs attention: 2 samples").assertIsDisplayed()
        compose.onNodeWithTag("trip_sync_acknowledged").assertDoesNotExist()
        scrollToTag("trip_sync_snapshot").assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC QUEUE").assertIsDisplayed()
        captureFixture("sync")
    }

    @Test fun fakeTripSummaryScreenshotContainsRenderedContent() {
        show(state = detailState(), fixtureBanner = true)
        scrollToTag("trip_estimated_distance").assertIsDisplayed()
        scrollToTag("trip_speed_chart").assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · FAKE TRIP").assertIsDisplayed()
        captureFixture("trips")
    }

    private fun captureFixture(name: String) {
        require(name == "trips" || name == "sync")
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
            val source = File(directory, "$name.png")
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/$name.png /data/local/tmp/rx8-$name.png")).use { it.readBytes() }
            val preserved = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-$name.png")).use { it.readBytes() }
            assertArrayEquals(source.readBytes(), preserved)
        } finally { bitmap.recycle() }
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        // Lazy items outside the viewport may not have a semantics node yet. Let the list find them first.
        compose.onNodeWithTag("trips_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun show(
        state: TripsUiState = TripsUiState(isLoading = false),
        selected: VehicleProfile? = vehicle,
        recording: TripRecordingState = TripRecordingState(),
        canStart: Boolean = false,
        onStart: (VehicleProfile) -> Unit = {},
        onStop: () -> Unit = {},
        onDelete: (String) -> Unit = {},
        onExport: (TripSession, TripExportFormat) -> Unit = { _, _ -> },
        fixtureBanner: Boolean = false,
        fixtureLabel: String = "UI TEST FIXTURE · FAKE TRIP",
        automaticMessage: String? = null,
        canStop: Boolean = true,
        syncSettings: SyncSettingsUiState? = null,
        uploadDelivery: UploadDeliveryState = UploadDeliveryState(),
    ) {
        compose.setContent {
            Rx8Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.safeDrawingPadding()) {
                        if (fixtureBanner) Text(fixtureLabel)
                        TripsContent(state, selected, recording, canStart, onStart, onStop, {}, {}, {}, {}, {}, onDelete, onExport, Modifier.weight(1f),
                            automaticMessage = automaticMessage, canStop = canStop, syncSettings = syncSettings, uploadDelivery = uploadDelivery)
                    }
                }
            }
        }
    }

    private fun history() = TripsUiState(trips = listOf(trip), storageBytes = 262_144L, isLoading = false, isLoadingStorage = false)
    private fun detailState(): TripsUiState {
        val detail = analysis()
        val key = TripSeriesKey(ObdMetric.SPEED, "TEST_ECU")
        return history().copy(
            selectedTripId = trip.id, detail = detail, selectedSeries = key,
            series = TripSeriesAnalysis(key, 4, 3, 0.0, 0.0, 1_000, detail.chart),
        )
    }
    private fun analysis() = TripAnalysis(
        session = trip, sampleCount = 4, validSampleCount = 3, estimatedDistanceKm = 0.0,
        speedEcuId = "TEST_ECU", coveredMillis = 1_000, maxRpm = 850.0, maxSpeedKph = 0.0,
        seriesOptions = listOf(TripSeriesKey(ObdMetric.SPEED, "TEST_ECU")),
        chart = listOf(TripChartPoint(0, 0.0, "TEST_ECU"), TripChartPoint(1_000, 0.0, "TEST_ECU"), TripChartPoint(2_000, null, "TEST_ECU"), TripChartPoint(5_000, 0.0, "TEST_ECU")),
    )

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

    private companion object {
        val vehicle = VehicleProfile("00000000-0000-4000-8000-000000000011", "Fixture RX-8", null, null, null, 1_791_676_800_000L)
        val trip = TripSession("00000000-0000-4000-8000-000000000012", vehicle.id, vehicle.alias, 1_791_676_800_000L, 1_791_676_805_000L, 5_000, TripStatus.COMPLETED, "User stopped recording", "TEST PROTOCOL")
    }
}
