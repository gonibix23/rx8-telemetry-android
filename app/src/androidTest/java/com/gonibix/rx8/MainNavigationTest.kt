package com.gonibix.rx8

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.sync.ACKNOWLEDGED
import com.gonibix.rx8.sync.ApiV1Json
import com.gonibix.rx8.sync.ApiV1Mapper
import com.gonibix.rx8.sync.SyncRuntime
import com.gonibix.rx8.sync.SyncWorkEntity
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripSessionEntity
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.vehicle.VehicleEntity
import com.gonibix.rx8.vehicle.VehicleProfile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourRealDestinationsRemainAvailableAndLiveSurvivesRecreation() {
        listOf("adapter", "dashboard", "trips", "vehicles").forEach { id ->
            compose.onNodeWithTag("${id}_tab").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("simulator_tab").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_tab").performScrollTo().performClick()
        compose.onNodeWithTag("live_dashboard").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_edit_mode").assertTextEquals("Edit").performClick()
        compose.onNodeWithTag("dashboard_mode_title").assertTextEquals("Edit Live")
        compose.onNodeWithTag("dashboard_choose_sensors").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("dashboard_tab").assertIsSelected()
        compose.onNodeWithTag("dashboard_mode_title").assertTextEquals("Edit Live")
        compose.onNodeWithTag("dashboard_choose_sensors").assertExists()
        compose.onNodeWithTag("preview_dashboard").assertDoesNotExist()
        compose.onNodeWithText("SIMULATOR").assertDoesNotExist()
        compose.onNodeWithTag("trips_tab").performScrollTo().performClick()
        compose.onNodeWithTag("trips_screen").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_tab").performScrollTo().performClick()
        compose.onNodeWithTag("dashboard_mode_title").assertTextEquals("Edit Live")
        compose.onNodeWithTag("dashboard_edit_mode").assertTextEquals("Done").performClick()
        compose.onNodeWithTag("dashboard_edit_mode").assertTextEquals("Edit")
        compose.onNodeWithTag("dashboard_choose_sensors").assertDoesNotExist()
        compose.onNodeWithTag("adapter_tab").performScrollTo().performClick()
        compose.onNodeWithTag("start_obd").assertDoesNotExist()
    }

    @Test fun confirmedDeliveryAppearsOnlyInsideTripsUploadsAcrossRealActivityTabs() {
        val context = compose.activity.applicationContext
        val database = VehicleDatabase.open(context)
        val runtime = SyncRuntime.get(context)
        val vehicle = VehicleProfile(UUID.randomUUID().toString(), "Synthetic upload placement", null, null, null, 0)
        val trip = TripSession(
            UUID.randomUUID().toString(), vehicle.id, vehicle.alias, 0, 1_000, 1_000,
            TripStatus.COMPLETED, "Synthetic UI fixture", origin = "SIMULATED",
        )
        var inserted = false
        try {
            // Publish only this fixture, already complete and acknowledged, in one transaction.
            // No active profile, upload setting, existing work row or network transport is changed.
            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    database.vehicles().insert(VehicleEntity(vehicle.id, vehicle.alias, null, null, null, 0))
                    database.trips().insertSession(TripSessionEntity(
                        trip.id, vehicle.id, vehicle.alias, trip.startedAtEpochMillis, trip.endedAtEpochMillis,
                        trip.durationMillis, trip.status.name, trip.endReason, null, trip.origin,
                    ))
                    database.sync().insertWork(SyncWorkEntity(
                        id = UUID.randomUUID().toString(), kind = "VEHICLE", resourceId = vehicle.id,
                        vehicleId = vehicle.id, sessionId = null, revision = 1,
                        payloadJson = ApiV1Json.vehicle(ApiV1Mapper.vehicle(vehicle, 1)), state = ACKNOWLEDGED,
                    ))
                    database.sync().insertWork(SyncWorkEntity(
                        id = UUID.randomUUID().toString(), kind = "SESSION", resourceId = trip.id,
                        vehicleId = vehicle.id, sessionId = trip.id, revision = 1,
                        payloadJson = ApiV1Json.session(ApiV1Mapper.session(trip, 1)), state = ACKNOWLEDGED,
                    ))
                }
            }
            inserted = true

            compose.onNodeWithTag("trips_tab").performScrollTo().performClick()
            compose.waitUntil(10_000L) { (runtime.delivery.value.counts?.confirmed ?: 0) > 0 }
            assertUploadsOwnsTheOnlyFeedback()
            listOf("adapter", "dashboard", "vehicles").forEach { tab ->
                compose.onNodeWithTag("${tab}_tab").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("upload_feedback").assertDoesNotExist()
            }
            compose.onNodeWithTag("trips_tab").performScrollTo().performClick()
            assertUploadsOwnsTheOnlyFeedback()
        } finally {
            if (inserted) runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    database.trips().deleteSession(trip.id)
                    // This ID was generated and inserted above; never clear a shared table or profile selection.
                    database.openHelper.writableDatabase.execSQL("DELETE FROM vehicle_profiles WHERE id = ?", arrayOf(vehicle.id))
                }
            }
        }
    }

    private fun assertUploadsOwnsTheOnlyFeedback() {
        compose.onNodeWithTag("trips_screen").performScrollToNode(hasTestTag("upload_feedback_title"))
        compose.onNodeWithTag("upload_feedback_title").performScrollTo().assertIsDisplayed()
            .assertTextEquals("Trips uploaded successfully")
            .assert(hasAnyAncestor(hasTestTag("upload_settings")))
        compose.onAllNodesWithTag("upload_feedback").assertCountEquals(1)
    }

    @Test fun removedAndUnknownSavedDestinationsResolveToRealLiveData() {
        assertEquals("dashboard", restoredMainDestination("simulator"))
        assertEquals("dashboard", restoredMainDestination("unexpected-old-screen"))
        assertEquals("trips", restoredMainDestination("trips"))
        assertEquals("adapter", restoredMainDestination(null))
    }
}
