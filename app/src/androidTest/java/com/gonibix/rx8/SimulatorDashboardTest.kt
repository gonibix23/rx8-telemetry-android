package com.gonibix.rx8

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulatorDashboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsDisconnectedAndIdentifiesSyntheticData() {
        compose.onNodeWithText("SIMULATOR").assertIsDisplayed()
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator stopped")
        compose.onNodeWithTag("reading_engine_rpm").assertTextEquals("—")
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Not connected")
        compose.onNodeWithText("Explore synthetic vehicle data. No car is connected.").assertIsDisplayed()
    }

    @Test
    fun normalSimulationCanStopAndReset() {
        compose.onNodeWithTag("toggle_simulator").performClick()
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator running")
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Simulated")
        captureDashboard()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator running")
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Simulated")

        compose.onNodeWithTag("toggle_simulator").performClick()
        compose.onNodeWithTag("reading_engine_rpm").assertTextEquals("—")
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator stopped")
        compose.onNodeWithTag("reset_simulator").performClick()
        compose.onNodeWithTag("elapsed_time").assertTextEquals("00:00 simulated")
    }

    @Test
    fun timeoutAndMissingDataNeverDisplayAsZero() {
        compose.onNodeWithTag("toggle_simulator").performClick()
        compose.onNodeWithTag("scenario_timeout").performScrollTo().performClick()
        compose.onNodeWithTag("scenario_timeout").assertIsSelected()
        compose.onNodeWithTag("reading_engine_rpm").assertTextEquals("—")
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Timed out")
        compose.onNodeWithTag("reading_speed").assertTextEquals("—")
        compose.onNodeWithTag("status_speed").assertTextEquals("Timed out")

        compose.onNodeWithTag("scenario_missing_data").performScrollTo().performClick()
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Simulated")
        compose.onNodeWithTag("reading_coolant_temperature").assertTextEquals("—")
        compose.onNodeWithTag("status_coolant_temperature").assertTextEquals("Unavailable")
        compose.onNodeWithTag("reading_intake_temperature").assertTextEquals("—")
        compose.onNodeWithTag("status_intake_temperature").assertTextEquals("Unavailable")

        compose.onNodeWithTag("scenario_normal").performScrollTo().performClick()
        compose.onNodeWithTag("status_coolant_temperature").assertTextEquals("Simulated")
        compose.onNodeWithTag("status_intake_temperature").assertTextEquals("Simulated")
    }

    @Test
    fun activityLifecyclePausesAndResumesSimulation() {
        lateinit var viewModel: SimulatorViewModel
        val scenario = compose.activityRule.scenario
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[SimulatorViewModel::class.java]
        }
        compose.onNodeWithTag("toggle_simulator").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            viewModel.uiState.value.elapsedMs >= 1_000L
        }

        scenario.moveToState(Lifecycle.State.CREATED)
        val pausedAt = viewModel.uiState.value.elapsedMs
        try {
            // Cross a full real 1-second tick interval to detect acquisition after onStop.
            // The production ticker uses coroutine time, not the Compose animation clock.
            SystemClock.sleep(1_250L)
            assertEquals("Simulation must pause while the Activity is stopped", pausedAt, viewModel.uiState.value.elapsedMs)
        } finally {
            scenario.moveToState(Lifecycle.State.RESUMED)
        }

        compose.waitUntil(timeoutMillis = 5_000L) {
            viewModel.uiState.value.elapsedMs > pausedAt
        }
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator running")
        compose.onNodeWithTag("status_engine_rpm").assertTextEquals("Simulated")
    }

    private fun captureDashboard() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("The emulator must provide a dashboard screenshot", screenshot)
        val captured = requireNotNull(screenshot)
        try {
            File(directory, "dashboard.png").outputStream().use { output ->
                assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            // Preserve the artifact before Gradle uninstalls the app and removes its files.
            // Both paths are fixed, test-owned locations; no external input enters the command.
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/dashboard.png " +
                        "/data/local/tmp/rx8-dashboard.png",
                ),
            ).use { it.readBytes() }
            val copiedPng = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/rx8-dashboard.png",
                ),
            ).use { it.readBytes() }
            assertArrayEquals(
                "The dashboard screenshot must survive app teardown unchanged",
                File(directory, "dashboard.png").readBytes(),
                copiedPng,
            )
        } finally {
            captured.recycle()
        }
    }
}
