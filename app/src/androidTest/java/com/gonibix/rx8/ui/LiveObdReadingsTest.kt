package com.gonibix.rx8.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveObdReadingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun validZeroIsDistinctFromUnsupportedNoDataAndFailedResponses() {
        show(
            snapshot(
                reading(ObdMetric.ENGINE_RPM, 850.0),
                reading(ObdMetric.SPEED, 0.0),
                reading(ObdMetric.COOLANT_TEMPERATURE, null, ObdReadingStatus.UNSUPPORTED),
                reading(ObdMetric.INTAKE_TEMPERATURE, 0.0, ObdReadingStatus.NO_DATA),
                reading(ObdMetric.THROTTLE_POSITION, 0.0, ObdReadingStatus.PARSE_ERROR),
                reading(ObdMetric.ENGINE_LOAD, null, ObdReadingStatus.TIMEOUT),
            ),
        )
        compose.onNodeWithTag("obd_value_7E8_speed").assertTextEquals("0")
        compose.onNodeWithTag("obd_reading_status_7E8_speed").assertTextEquals("Current")
        compose.onNodeWithTag("obd_value_7E8_coolant_temperature").assertTextEquals("—")
        compose.onNodeWithTag("obd_reading_status_7E8_coolant_temperature").assertTextEquals("Unsupported")
        compose.onNodeWithTag("obd_value_7E8_intake_temperature").assertTextEquals("—")
        compose.onNodeWithTag("obd_reading_status_7E8_intake_temperature").assertTextEquals("No data")
        compose.onNodeWithTag("obd_value_7E8_throttle_position").assertTextEquals("—")
        compose.onNodeWithTag("obd_reading_status_7E8_throttle_position").assertTextEquals("Unreadable response")
        compose.onNodeWithTag("obd_reading_status_7E8_engine_load").assertTextEquals("Timed out")
    }

    @Test
    fun staleReadingRetainsItsValueWithExplicitAgeAndUtcTimestamp() {
        show(snapshot(reading(ObdMetric.ENGINE_RPM, 900.0, ObdReadingStatus.STALE)))
        compose.onNodeWithTag("obd_value_7E8_engine_rpm").assertTextEquals("900")
        compose.onNodeWithTag("obd_reading_status_7E8_engine_rpm").assertTextEquals("Stale · last value")
        compose.onNodeWithTag("obd_age_7E8_engine_rpm").assertTextEquals("Age: 4.0 s")
        compose.onNodeWithTag("obd_time_7E8_engine_rpm").assertTextEquals("10:30:00 UTC")
    }

    @Test
    fun stoppedSessionCannotPresentRetainedValuesAsCurrent() {
        show(snapshot(reading(ObdMetric.ENGINE_RPM, 900.0)).copy(phase = ObdPhase.STOPPED))
        compose.onNodeWithText("Last vehicle readings").assertExists()
        compose.onNodeWithTag("obd_value_7E8_engine_rpm").assertTextEquals("900")
        compose.onNodeWithTag("obd_reading_status_7E8_engine_rpm").assertTextEquals("Stale · last value")
        compose.onNodeWithTag("obd_age_7E8_engine_rpm").assertTextEquals("Age at stop: 4.0 s")
    }

    @Test
    fun eachEcuHasItsOwnValuesAndProtocolIsVisible() {
        show(
            snapshot(
                reading(ObdMetric.ENGINE_RPM, 850.0),
                reading(ObdMetric.ENGINE_RPM, 910.0, ecuId = "7E9"),
            ),
        )
        compose.onNodeWithTag("obd_ecu_7E8").assertTextEquals("ECU 7E8")
        compose.onNodeWithTag("obd_ecu_7E9").assertTextEquals("ECU 7E9")
        compose.onNodeWithTag("obd_value_7E8_engine_rpm").assertTextEquals("850")
        compose.onNodeWithTag("obd_value_7E9_engine_rpm").assertTextEquals("910")
        compose.onNodeWithTag("obd_protocol").assertTextEquals("Protocol: ISO 15765-4 CAN (11 bit, 500 kbit/s)")
    }

    @Test fun expandedSensorsKeepFractionalValuesAndOnlyAdvertisedMetricsAppear() {
        show(snapshot(reading(ObdMetric.MASS_AIR_FLOW, 0.25), reading(ObdMetric.CURRENT_OXYGEN_SENSOR_1_CURRENT, -0.125)))
        compose.onNodeWithTag("obd_value_7E8_mass_air_flow").assertTextEquals("0.25")
        compose.onNodeWithTag("obd_value_7E8_current_oxygen_sensor_1_current").assertTextEquals("-0.125")
        compose.onNodeWithTag("obd_value_7E8_current_oxygen_sensor_1_ratio").assertTextEquals("—")
        compose.onNodeWithTag("obd_value_7E8_engine_rpm").assertDoesNotExist()
    }

    @Test fun invalidAgeOrOutOfRangeValueCannotBePresentedAsCurrent() {
        show(snapshot(reading(ObdMetric.SPEED, -1.0), reading(ObdMetric.ENGINE_RPM, 900.0)).copy(elapsedMillis = 500L))
        compose.onNodeWithTag("obd_value_7E8_speed").assertTextEquals("—")
        compose.onNodeWithTag("obd_reading_status_7E8_speed").assertTextEquals("No data")
        compose.onNodeWithTag("obd_reading_status_7E8_engine_rpm").assertTextEquals("Stale · last value")
        compose.onNodeWithTag("obd_age_7E8_engine_rpm").assertTextEquals("Age unknown")
    }

    @Test
    fun syntheticFixtureCapturesLiveReadingsLayout() {
        show(
            snapshot(
                reading(ObdMetric.ENGINE_RPM, 900.0),
                reading(ObdMetric.SPEED, 0.0),
                reading(ObdMetric.COOLANT_TEMPERATURE, 82.0),
                reading(ObdMetric.INTAKE_TEMPERATURE, 27.0),
                reading(ObdMetric.THROTTLE_POSITION, 2.4),
                reading(ObdMetric.ENGINE_LOAD, 18.0),
            ).copy(elapsedMillis = 1_400L),
            screenshotFixture = true,
        )
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC VALUES").assertIsDisplayed()
        compose.onNodeWithTag("obd_value_7E8_engine_rpm").assertTextEquals("900").assertIsDisplayed()
        compose.onNodeWithTag("obd_observed_rate")
            .assertTextEquals("Observed: 2.7 responses/s · 6 supported PIDs").assertIsDisplayed()
        captureLiveScreen()
    }

    private fun show(snapshot: ObdSessionSnapshot, screenshotFixture: Boolean = false) {
        compose.setContent {
            ReadingFixture {
                if (screenshotFixture) {
                    Text("UI TEST FIXTURE · SYNTHETIC VALUES", color = AccentColor, fontSize = 10.sp)
                }
                LiveObdReadings(snapshot)
            }
        }
    }

    @Composable
    private fun ReadingFixture(content: @Composable () -> Unit) {
        Rx8Theme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }

    private fun reading(
        metric: ObdMetric,
        value: Double?,
        status: ObdReadingStatus = ObdReadingStatus.VALID,
        ecuId: String = "7E8",
    ) = ObdReading(
        metric = metric,
        value = value,
        status = status,
        ecuId = ecuId,
        observedAtEpochMillis = Instant.parse("2026-10-10T10:30:00Z").toEpochMilli(),
        elapsedMillis = 1_000L,
    )

    private fun snapshot(vararg readings: ObdReading) = ObdSessionSnapshot(
        phase = ObdPhase.LIVE,
        statusMessage = "Reading the supported sensors.",
        protocolLabel = "ISO 15765-4 CAN (11 bit, 500 kbit/s)",
        ecus = readings.groupBy { it.ecuId }.map { (ecu, values) -> ObdEcuCapabilities(ecu, values.map { it.metric.pid }.toSet()) },
        readings = readings.toList(),
        supportedPidCount = 6,
        effectiveResponsesPerSecond = 2.7,
        elapsedMillis = 5_000L,
    )

    private fun captureLiveScreen() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        // Capture the rendered Compose root, not a transient system-window buffer.
        var rendered: Bitmap? = null
        compose.waitUntil(5_000L) {
            val candidate = compose.onRoot().captureToImage().asAndroidBitmap()
            if (hasRenderedContent(candidate)) {
                rendered = candidate
                true
            } else {
                candidate.recycle()
                false
            }
        }
        val captured = requireNotNull(rendered)
        try {
            val source = File(directory, "live.png")
            source.outputStream().use { output ->
                assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/live.png /data/local/tmp/rx8-live.png",
                ),
            ).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-live.png"),
            ).use { it.readBytes() }
            assertArrayEquals("The live readings screenshot must survive teardown", source.readBytes(), copied)
        } finally {
            captured.recycle()
        }
    }

    private fun hasRenderedContent(bitmap: Bitmap): Boolean {
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
        // A dark fixture with light text must not pass as a blank white or black frame.
        return samples > 0 && dark > samples / 10 && light >= 8
    }
}
