package com.gonibix.rx8.dashboard

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.DemoTelemetrySource
import com.gonibix.rx8.telemetry.SimulationScenario
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveDashboardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun validZeroAndMissingReadingsStayDistinctAndUnadvertisedCardsAreAbsent() {
        show(snapshot(
            reading(ObdMetric.SPEED, 0.0),
            reading(ObdMetric.COOLANT_TEMPERATURE, null, ObdReadingStatus.NO_DATA),
        ).copy(ecus = listOf(ObdEcuCapabilities("7E8", setOf(ObdMetric.SPEED.pid, ObdMetric.COOLANT_TEMPERATURE.pid)))))
        compose.onNodeWithTag("dashboard_value_7E8_speed").assertTextEquals("0")
        compose.onNodeWithTag("dashboard_status_7E8_speed").assertTextEquals("Current")
        compose.onNodeWithTag("dashboard_value_7E8_coolant_temperature").assertTextEquals("—")
        compose.onNodeWithTag("dashboard_status_7E8_coolant_temperature").assertTextEquals("No data")
        compose.onNodeWithTag("dashboard_value_7E8_engine_rpm").assertDoesNotExist()
    }

    @Test fun stoppingMarksRetainedReadingsStaleAndExplicitlyFreezesAge() {
        val state = mutableStateOf(snapshot(reading(ObdMetric.SPEED, 18.0)).copy(elapsedMillis = 3_000L))
        compose.setContent {
            Rx8Theme { LiveDashboard(state.value, emptySet(), {}, {}) }
        }
        compose.onNodeWithTag("dashboard_status_7E8_speed").assertTextEquals("Current")
        compose.runOnIdle { state.value = state.value.copy(phase = ObdPhase.STOPPED) }
        compose.onNodeWithTag("dashboard_value_7E8_speed").assertTextEquals("18")
        compose.onNodeWithTag("dashboard_status_7E8_speed").assertTextEquals("Stale · last value")
        compose.onNodeWithTag("dashboard_age_7E8_speed").assertTextEquals("Age at stop: 2.0 s")
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Readings stopped")
    }

    @Test fun supportedFavoriteCanBeSelectedAndUnsupportedSavedFavoriteCanBeRemoved() {
        val favorites = mutableStateOf(setOf(ObdMetric.THROTTLE_POSITION))
        val data = snapshot(reading(ObdMetric.INTAKE_TEMPERATURE, 27.0)).copy(
            ecus = listOf(ObdEcuCapabilities("7E8", setOf(ObdMetric.INTAKE_TEMPERATURE.pid))),
        )
        compose.setContent {
            Rx8Theme {
                LiveDashboard(data, favorites.value, { metric ->
                    favorites.value = if (metric in favorites.value) favorites.value - metric else favorites.value + metric
                }, {})
            }
        }
        scrollTo("dashboard_choose_sensors").performClick()
        scrollTo("dashboard_favorite_intake_temperature").assertIsOff().performClick()
        scrollTo("dashboard_favorite_intake_temperature").assertIsOn()
        scrollTo("dashboard_favorite_throttle_position").assertIsOn()
        compose.onNodeWithText("Not supported in this session. Uncheck to remove.").assertExists()
        compose.onNodeWithTag("dashboard_value_7E8_throttle_position").assertDoesNotExist()
        scrollTo("dashboard_favorite_throttle_position").performClick()
        compose.runOnIdle { assertEquals(setOf(ObdMetric.INTAKE_TEMPERATURE), favorites.value) }
        scrollTo("dashboard_value_7E8_intake_temperature").assertTextEquals("27")
    }

    @Test fun multipleEcusRemainSeparateEvenWhenTheyReportTheSameMetric() {
        show(snapshot(reading(ObdMetric.SPEED, 0.0), reading(ObdMetric.SPEED, 12.0, ecu = "7E9")).copy(
            ecus = listOf("7E8", "7E9").map { ObdEcuCapabilities(it, setOf(ObdMetric.SPEED.pid)) },
        ))
        compose.onNodeWithTag("dashboard_value_7E8_speed").assertTextEquals("0")
        scrollTo("dashboard_value_7E9_speed").assertTextEquals("12")
        compose.onNodeWithTag("dashboard_ecu_7E9").assertTextEquals("Vehicle ECU · 7E9")
    }

    @Test fun bluetoothStatusIsIndependentOfEcuStatusAndMetadataIsExplicitlyFromAdapter() {
        var opened = 0
        compose.setContent {
            Rx8Theme {
                LiveDashboard(
                    ObdSessionSnapshot(protocolLabel = "Synthetic protocol fixture"), emptySet(), {}, { opened++ },
                    connectionStatus = "Bluetooth connected",
                )
            }
        }
        compose.onNodeWithTag("dashboard_bluetooth_status").assertTextEquals("Bluetooth connected")
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Readings not started · ECU not checked")
        compose.onNodeWithTag("dashboard_session_details").performClick()
        compose.onNodeWithTag("dashboard_protocol").assertTextEquals("Protocol reported by adapter: Synthetic protocol fixture")
        compose.onNodeWithTag("dashboard_open_adapter").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun simulatorTimeoutAndDisconnectRemainClearlySyntheticWithoutRetainedCurrentValues() {
        val source = DemoTelemetrySource()
        source.connect()
        val state = mutableStateOf(simulatorDashboardSnapshot(source.setScenario(SimulationScenario.TIMEOUT)))
        compose.setContent {
            Rx8Theme { LiveDashboard(state.value, emptySet(), {}, {}, isSimulated = true) }
        }
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("SIMULATOR · SYNTHETIC DATA")
        compose.onNodeWithTag("dashboard_status_SIMULATOR_speed").assertTextEquals("Timed out")
        compose.onNodeWithTag("dashboard_value_SIMULATOR_speed").assertTextEquals("—")
        compose.runOnIdle { state.value = simulatorDashboardSnapshot(source.disconnect()) }
        compose.onNodeWithTag("dashboard_session_status").assertTextEquals("Simulation stopped")
        compose.onNodeWithTag("dashboard_status_SIMULATOR_speed").assertTextEquals("Awaiting reading")
        compose.onNodeWithTag("dashboard_value_SIMULATOR_speed").assertTextEquals("—")
    }

    @Test fun syntheticFavoritesFixtureCapturesCompactDashboard() {
        val source = DemoTelemetrySource()
        source.connect()
        show(simulatorDashboardSnapshot(source.advanceBy(2_000L)), setOf(ObdMetric.INTAKE_TEMPERATURE), simulated = true, screenshotFixture = true)
        compose.onNodeWithTag("dashboard_origin").assertTextEquals("SIMULATOR · SYNTHETIC DATA")
        compose.onNodeWithTag("live_dashboard").performScrollToIndex(1)
        compose.onNodeWithTag("dashboard_value_SIMULATOR_speed").assertTextEquals("0").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_value_SIMULATOR_intake_temperature").assertTextEquals("28").assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC DASHBOARD").assertIsDisplayed()
        captureFavoritesScreen()
    }

    private fun show(snapshot: ObdSessionSnapshot, favorites: Set<ObdMetric> = emptySet(), simulated: Boolean = false, screenshotFixture: Boolean = false) {
        compose.setContent {
            Rx8Theme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column {
                        if (screenshotFixture) Text("UI TEST FIXTURE · SYNTHETIC DASHBOARD")
                        else if (!simulated) Text("UI TEST FIXTURE · SYNTHETIC VALUES")
                        LiveDashboard(snapshot, favorites, {}, {}, modifier = Modifier.weight(1f), isSimulated = simulated)
                    }
                }
            }
        }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("live_dashboard")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

    private fun reading(metric: ObdMetric, value: Double?, status: ObdReadingStatus = ObdReadingStatus.VALID, ecu: String = "7E8") =
        ObdReading(metric, value, status, ecu, 123_000L, 1_000L)

    private fun snapshot(vararg readings: ObdReading) = ObdSessionSnapshot(
        phase = ObdPhase.LIVE,
        statusMessage = "Synthetic UI test fixture.",
        protocolLabel = "Synthetic protocol fixture",
        ecus = readings.map { it.ecuId }.distinct().map { ObdEcuCapabilities(it, ObdMetric.entries.map { metric -> metric.pid }.toSet()) },
        readings = readings.toList(),
        elapsedMillis = 1_500L,
    )

    private fun captureFavoritesScreen() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        // Capture rendered Compose content instead of racing the emulator window presentation.
        var rendered: Bitmap? = null
        compose.waitUntil(5_000L) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (hasDashboardContent(bitmap)) { rendered = bitmap; true } else { bitmap.recycle(); false }
        }
        val captured = requireNotNull(rendered)
        try {
            val source = File(directory, "favourites.png")
            source.outputStream().use { assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/favourites.png /data/local/tmp/rx8-favourites.png",
            )).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/rx8-favourites.png",
            )).use { it.readBytes() }
            assertArrayEquals("The dashboard screenshot must survive teardown", source.readBytes(), copied)
        } finally { captured.recycle() }
    }

    private fun hasDashboardContent(bitmap: Bitmap): Boolean {
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
