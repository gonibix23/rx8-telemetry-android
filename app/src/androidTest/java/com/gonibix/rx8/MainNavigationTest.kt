package com.gonibix.rx8

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test fun removedAndUnknownSavedDestinationsResolveToRealLiveData() {
        assertEquals("dashboard", restoredMainDestination("simulator"))
        assertEquals("dashboard", restoredMainDestination("unexpected-old-screen"))
        assertEquals("trips", restoredMainDestination("trips"))
        assertEquals("adapter", restoredMainDestination(null))
    }
}
