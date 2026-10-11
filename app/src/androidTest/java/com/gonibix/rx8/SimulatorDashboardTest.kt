package com.gonibix.rx8

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulatorDashboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun openSimulator() {
        compose.onNodeWithTag("simulator_tab").performClick()
    }

    @Test
    fun newDashboardPreviewKeepsSyntheticDataSeparateAcrossRecreationAndTabs() {
        compose.onNodeWithTag("preview_dashboard").performClick()
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("SIMULATOR · SYNTHETIC DATA")
        compose.onNodeWithTag("preview_toggle_simulator").performClick()
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Simulation running")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("SIMULATOR · SYNTHETIC DATA")
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Simulation running")
        compose.onNodeWithTag("preview_scenario_timeout").performClick()
        compose.onNodeWithTag("live_dashboard").performScrollToNode(hasTestTag("dashboard_value_SIMULATOR_engine_rpm"))
        compose.onNodeWithTag("dashboard_value_SIMULATOR_engine_rpm").assertTextEquals("—")
        compose.onNodeWithTag("dashboard_status_SIMULATOR_engine_rpm").assertTextEquals("Timed out")

        compose.onNodeWithTag("dashboard_tab").performClick()
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("VEHICLE · OBD")
        compose.onNodeWithTag("dashboard_value_SIMULATOR_engine_rpm").assertDoesNotExist()
        compose.onNodeWithTag("simulator_tab").performClick()
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("SIMULATOR · SYNTHETIC DATA")
        compose.onNodeWithTag("preview_toggle_simulator").performClick()
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Simulation stopped")
        compose.onNodeWithTag("close_dashboard_preview").performClick()
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator stopped")
    }

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

    @Test
    fun adapterAndSimulatorStaySeparate() {
        lateinit var viewModel: SimulatorViewModel
        compose.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[SimulatorViewModel::class.java]
        }
        compose.onNodeWithTag("toggle_simulator").performClick()
        compose.onNodeWithTag("adapter_tab").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithTag("bluetooth_connection_status").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("simulation_status").assertDoesNotExist()
        compose.onNodeWithTag("reading_engine_rpm").assertDoesNotExist()
        val pausedAt = viewModel.uiState.value.elapsedMs
        captureDashboard("connection")
        SystemClock.sleep(1_250L)
        assertEquals("The simulator must pause on the Adapter tab", pausedAt, viewModel.uiState.value.elapsedMs)
        compose.onNodeWithTag("simulator_tab").performClick()
        compose.onNodeWithTag("simulation_status").assertTextEquals("Simulator running")
        compose.waitUntil(timeoutMillis = 5_000L) { viewModel.uiState.value.elapsedMs > pausedAt }
    }

    private fun captureDashboard(name: String = "dashboard") {
        require(name == "dashboard" || name == "connection")
        compose.waitForIdle()
        // Semantic readiness does not guarantee a presented emulator-window frame.
        var rendered: Bitmap? = null
        compose.waitUntil(5_000L) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (hasRenderedScreenContent(bitmap)) {
                rendered = bitmap
                true
            } else {
                bitmap.recycle()
                false
            }
        }
        val captured = requireNotNull(rendered)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        try {
            assertTrue(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { output ->
                assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            // Preserve the artifact before Gradle uninstalls the app and removes its files.
            // Both paths are fixed, test-owned locations; no external input enters the command.
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/$name.png " +
                        "/data/local/tmp/rx8-$name.png",
                ),
            ).use { it.readBytes() }
            val copiedPng = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/rx8-$name.png",
                ),
            ).use { it.readBytes() }
            assertArrayEquals(
                "The dashboard screenshot must survive app teardown unchanged",
                File(directory, "$name.png").readBytes(),
                copiedPng,
            )
        } finally {
            captured.recycle()
        }
    }

    private fun hasRenderedScreenContent(bitmap: Bitmap): Boolean {
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
}
