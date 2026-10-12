package com.gonibix.rx8.capabilities

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.connection.BluetoothConnectionPhase
import com.gonibix.rx8.connection.VehicleDiagnosticsPanel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.gonibix.rx8.connection.BluetoothConnectionUiState
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every identifier, capability, and value is a synthetic fixture, not vehicle evidence. */
@RunWith(AndroidJUnit4::class)
class CapabilityReportScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableSnapshotCannotBeCapturedOrExported() {
        show(canCapture = false)
        compose.onNodeWithTag("capability_capture").assertIsNotEnabled()
        compose.onNodeWithTag("capability_export").assertIsNotEnabled()
        compose.onNodeWithTag("capability_capture_unavailable").assertTextEquals("No completed vehicle discovery is available. Connect in Adapter and wait for automatic discovery.")
        scrollToTag("capability_empty").assertTextEquals("No snapshot captured yet.")
        compose.onNodeWithTag("capability_summary").assertDoesNotExist()
    }

    @Test fun capturingRequiresAnExplicitClickAndDoesNotExport() {
        var captures = 0
        var exports = 0
        show(onCapture = { captures++ }, onExport = { exports++ })
        assertEquals(0, captures)
        assertEquals(0, exports)
        compose.onNodeWithTag("capability_capture").assertIsEnabled().performClick()
        assertEquals(1, captures)
        assertEquals(0, exports)
        compose.onNodeWithTag("capability_export").assertIsNotEnabled()
    }

    @Test fun previewShowsUtcActualProtocolDurationAndAggregateResponseRate() {
        show(state = CapabilityReportUiState(report = report()))
        scrollToTag("capability_capture_time").assertTextEquals("11 Oct 2026, 10:30:00 UTC")
        scrollToTag("capability_phase").assertTextEquals("Live acquisition at capture")
        scrollToTag("capability_protocol").assertTextEquals("Protocol: ${ObdProtocol.CAN_11_500.description}")
        scrollToTag("capability_elapsed").assertTextEquals("Acquisition elapsed: 1 min 00 s")
        scrollToTag("capability_rate").assertTextEquals("Successful ECU responses: 2.70/s")
        scrollToTag("capability_snapshot_limits").assertTextEquals("Advertised support does not prove a successful reading. These are the latest displayed states and may include initial No data placeholders. This snapshot is not a full-session, latency or battery test.")
    }

    @Test fun absentProtocolAndRateStayUnknownRatherThanZero() {
        show(state = CapabilityReportUiState(report = report(protocol = null, rate = null)))
        scrollToTag("capability_protocol").assertTextEquals("Protocol: Unknown")
        scrollToTag("capability_rate").assertTextEquals("Successful ECU responses: Not available")
    }

    @Test fun expandedVoltageAndSignedTrimUseTheirCanonicalPrecision() {
        val voltage = ObdMetric.OXYGEN_SENSOR_1_VOLTAGE
        val trim = ObdMetric.SHORT_TERM_FUEL_TRIM_BANK_1
        val ecu = ecu(pids = listOf(trim.pid, voltage.pid), overrides = mapOf(
            voltage to CapabilityReading(0.125, ObdReadingStatus.VALID, 100),
            trim to CapabilityReading(-12.5, ObdReadingStatus.VALID, 100),
        ))
        show(state = CapabilityReportUiState(report = report(ecus = listOf(ecu))))
        scrollToTag("capability_value_7E8_oxygen_sensor_1_voltage").assertTextEquals("0.125 V")
        scrollToTag("capability_value_7E8_short_term_fuel_trim_bank_1").assertTextEquals("-12.5 %")
    }

    @Test fun separateEcusKeepTheirOwnValuesAndUndecodedAdvertisedPids() {
        show(state = CapabilityReportUiState(report = report(ecus = listOf(ecu("7E8"), ecu("7E9", rpm = 1_500.0)))))
        scrollToTag("capability_pids_7E8").assertTextEquals("04  05  0C  0D  0F  11  1C")
        scrollToTag("capability_other_pids_7E8").assertTextEquals("Advertised but not decoded by this app: 1C.")
        scrollToTag("capability_value_7E8_engine_rpm").assertTextEquals("900 rpm")
        scrollToTag("capability_ecu_7E9").assertTextEquals("ECU 7E9")
        scrollToTag("capability_value_7E9_engine_rpm").assertTextEquals("1500 rpm")
    }

    @Test fun scalingMetadataIsDistinguishedFromUndecodedSensors() {
        show(state = CapabilityReportUiState(report = report(ecus = listOf(ecu(pids = listOf(0x10, 0x4F, 0x50, 0x51))))))
        scrollToTag("capability_metadata_pids_7E8").assertTextEquals("Scaling metadata: 4F, 50.")
        scrollToTag("capability_other_pids_7E8").assertTextEquals("Advertised but not decoded by this app: 51.")
    }

    @Test fun zeroErrorsAndStaleValuesRemainDistinct() {
        val readings = mapOf(
            ObdMetric.ENGINE_RPM to CapabilityReading(850.0, ObdReadingStatus.STALE, 6_000),
            ObdMetric.SPEED to CapabilityReading(0.0, ObdReadingStatus.VALID, 100),
            ObdMetric.COOLANT_TEMPERATURE to CapabilityReading(null, ObdReadingStatus.NO_DATA, 100),
            ObdMetric.INTAKE_TEMPERATURE to CapabilityReading(null, ObdReadingStatus.TIMEOUT, null),
            ObdMetric.THROTTLE_POSITION to CapabilityReading(null, ObdReadingStatus.PARSE_ERROR, 200),
            ObdMetric.ENGINE_LOAD to CapabilityReading(null, ObdReadingStatus.ADAPTER_ERROR, 300),
        )
        show(state = CapabilityReportUiState(report = report(ecus = listOf(ecu(overrides = readings)))))
        scrollToTag("capability_value_7E8_engine_rpm").assertTextEquals("850 rpm")
        scrollToTag("capability_status_7E8_engine_rpm").assertTextEquals("Stale · last value")
        scrollToTag("capability_age_7E8_engine_rpm").assertTextEquals("Age at snapshot: 6.0 s")
        scrollToTag("capability_value_7E8_speed").assertTextEquals("0 km/h")
        scrollToTag("capability_status_7E8_speed").assertTextEquals("Valid at capture")
        scrollToTag("capability_value_7E8_coolant_temperature").assertTextEquals("— °C")
        scrollToTag("capability_status_7E8_coolant_temperature").assertTextEquals("No data")
        scrollToTag("capability_status_7E8_intake_temperature").assertTextEquals("Timed out")
        scrollToTag("capability_age_7E8_intake_temperature").assertTextEquals("Reading age unavailable")
        scrollToTag("capability_value_7E8_throttle_position").assertTextEquals("— %")
        scrollToTag("capability_status_7E8_throttle_position").assertTextEquals("Unreadable response")
        scrollToTag("capability_status_7E8_engine_load").assertTextEquals("Adapter error")
    }

    @Test fun advertisedMissingAndUnsupportedReadingsDoNotInventValues() {
        val supported = listOf(ObdMetric.ENGINE_RPM.pid, ObdMetric.SPEED.pid)
        show(state = CapabilityReportUiState(report = report(ecus = listOf(ecu(
            pids = supported,
            overrides = mapOf(ObdMetric.ENGINE_RPM to null, ObdMetric.SPEED to CapabilityReading(null, ObdReadingStatus.UNSUPPORTED, 400)),
        )))))
        scrollToTag("capability_support_7E8_engine_rpm").assertTextEquals("PID 0C · Advertised by ECU")
        scrollToTag("capability_status_7E8_engine_rpm").assertTextEquals("No displayed reading")
        scrollToTag("capability_value_7E8_engine_rpm").assertTextEquals("— rpm")
        scrollToTag("capability_status_7E8_speed").assertTextEquals("Unsupported")
        scrollToTag("capability_value_7E8_speed").assertTextEquals("— km/h")
        scrollToTag("capability_support_7E8_coolant_temperature").assertTextEquals("PID 05 · Not advertised by ECU")
        scrollToTag("capability_status_7E8_coolant_temperature").assertTextEquals("Not advertised")
        compose.onNodeWithTag("capability_age_7E8_coolant_temperature").assertDoesNotExist()
    }

    @Test fun stoppedPreviewRemainsExportableWithoutPretendingValuesAreLive() {
        show(state = CapabilityReportUiState(report = report(phase = ObdPhase.STOPPED, ecus = listOf(ecu(stale = true)))), canCapture = false)
        compose.onNodeWithTag("capability_capture").assertIsNotEnabled()
        compose.onNodeWithTag("capability_export").assertIsEnabled()
        scrollToTag("capability_phase").assertTextEquals("Acquisition stopped · retained readings")
        scrollToTag("capability_status_7E8_speed").assertTextEquals("Stale · last value")
        scrollToTag("capability_age_7E8_speed").assertTextEquals("Age at acquisition stop: 0.2 s")
    }

    @Test fun awaitingDestinationAndWritingDisableNewCaptureAndExport() {
        val state = mutableStateOf(CapabilityReportUiState(report = report(), isAwaitingDestination = true))
        compose.setContent { Fixture { CapabilityReportScreen(state.value, true, {}, {}, {}) } }
        compose.onNodeWithTag("capability_capture").assertIsNotEnabled()
        compose.onNodeWithTag("capability_export").assertIsNotEnabled()
        compose.onNodeWithTag("capability_destination_pending").assertIsDisplayed()
        compose.onNodeWithTag("capability_message").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(isAwaitingDestination = false, isExporting = true) }
        compose.onNodeWithTag("capability_capture").assertIsNotEnabled()
        compose.onNodeWithTag("capability_export").assertIsNotEnabled()
        compose.onNodeWithTag("capability_exporting").assertIsDisplayed()
        compose.onNodeWithTag("capability_destination_pending").assertDoesNotExist()
        compose.onNodeWithTag("capability_message").assertDoesNotExist()
    }

    @Test fun exportFailureKeepsThePreviewAndAllowsExplicitRetryAndBack() {
        var exports = 0
        var backs = 0
        show(state = CapabilityReportUiState(report = report(), error = "The report could not be saved. Try another location."), onExport = { exports++ }, onBack = { backs++ })
        compose.onNodeWithTag("capability_error").assertTextEquals("The report could not be saved. Try another location.")
        assertEquals(0, exports)
        compose.onNodeWithTag("capability_export").assertIsEnabled().performClick()
        assertEquals(1, exports)
        scrollToTag("capability_value_7E8_speed").assertTextEquals("0 km/h")
        scrollToTag("capability_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun liveEntryRequiresCompletedDiscoveryAndOnlyOpensOnClick() {
        var opens = 0
        var readRequests = 0
        val state = mutableStateOf(BluetoothConnectionUiState(availability = BluetoothAvailability.READY, phase = BluetoothConnectionPhase.CONNECTED, isForeground = true))
        compose.setContent {
            Fixture {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VehicleDiagnosticsPanel(state.value, {}, { readRequests++ }, {}, { opens++ })
                }
            }
        }
        compose.onNodeWithTag("open_capabilities").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(obd = ObdSessionSnapshot(phase = ObdPhase.DISCOVERING, ecus = listOf(ObdEcuCapabilities("7E8", setOf(0x0C)))))
        }
        compose.onNodeWithTag("open_capabilities").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(obd = state.value.obd.copy(phase = ObdPhase.LIVE)) }
        assertEquals(0, opens)
        assertEquals(0, readRequests)
        compose.onNodeWithTag("open_capabilities").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opens)
        assertEquals(0, readRequests)
        compose.runOnIdle { state.value = state.value.copy(phase = BluetoothConnectionPhase.DISCONNECTED, obd = state.value.obd.copy(phase = ObdPhase.STOPPED)) }
        compose.onNodeWithTag("open_capabilities").performScrollTo().assertIsDisplayed()
    }

    @Test fun syntheticCapabilityScreenshotContainsRenderedEcuSupportAndValues() {
        show(state = CapabilityReportUiState(report = report()), fixtureBanner = true)
        // Anchor the ECU section, rather than aligning a later metric above the preceding cards.
        compose.onNodeWithTag("capability_report_screen").performScrollToIndex(5)
        compose.onNodeWithTag("capability_ecu_7E8").assertTextEquals("ECU 7E8").assertIsDisplayed()
        compose.onNodeWithTag("capability_pids_7E8").assertTextEquals("04  05  0C  0D  0F  11  1C").assertIsDisplayed()
        compose.onNodeWithTag("capability_other_pids_7E8").assertTextEquals("Advertised but not decoded by this app: 1C.").assertIsDisplayed()
        compose.onNodeWithTag("capability_value_7E8_engine_rpm").assertTextEquals("900 rpm").assertIsDisplayed()
        compose.onNodeWithTag("capability_value_7E8_speed").assertTextEquals("0 km/h").assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC CAPABILITIES").assertIsDisplayed()
        captureFixture()
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("capability_report_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun show(
        state: CapabilityReportUiState = CapabilityReportUiState(),
        canCapture: Boolean = true,
        onCapture: () -> Unit = {},
        onExport: () -> Unit = {},
        onBack: () -> Unit = {},
        fixtureBanner: Boolean = false,
    ) {
        compose.setContent {
            Fixture {
                Column {
                    if (fixtureBanner) Text("UI TEST FIXTURE · SYNTHETIC CAPABILITIES")
                    CapabilityReportScreen(state, canCapture, onCapture, onExport, onBack, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        Rx8Theme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.safeDrawingPadding()) { content() }
            }
        }
    }

    private fun report(
        phase: ObdPhase = ObdPhase.LIVE,
        protocol: String? = ObdProtocol.CAN_11_500.description,
        rate: Double? = 2.7,
        ecus: List<CapabilityEcu> = listOf(ecu()),
    ) = CapabilityReport("00000000-0000-4000-8000-000000000021", Instant.parse("2026-10-11T10:30:00Z").toEpochMilli(), "0.9.0-dev", 36, phase, protocol, 60_000, rate, ecus)

    private fun ecu(
        address: String = "7E8",
        rpm: Double = 900.0,
        pids: List<Int> = listOf(0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x1C),
        overrides: Map<ObdMetric, CapabilityReading?> = emptyMap(),
        stale: Boolean = false,
    ): CapabilityEcu {
        val metrics = ObdMetric.entries.map { metric ->
            val value = when (metric) {
                ObdMetric.ENGINE_RPM -> rpm
                ObdMetric.SPEED -> 0.0
                ObdMetric.COOLANT_TEMPERATURE -> 82.0
                ObdMetric.INTAKE_TEMPERATURE -> 27.0
                ObdMetric.THROTTLE_POSITION -> 2.4
                ObdMetric.ENGINE_LOAD -> 18.0
                else -> null
            }
            val advertised = metric.pid in pids
            val reading = if (!advertised) null else if (metric in overrides) overrides[metric]
                else CapabilityReading(value, when {
                    value == null -> ObdReadingStatus.NO_DATA
                    stale -> ObdReadingStatus.STALE
                    else -> ObdReadingStatus.VALID
                }, 200)
            CapabilityMetric(metric, advertised, reading)
        }
        return CapabilityEcu(address, pids.sorted(), metrics)
    }

    private fun captureFixture() {
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
            val source = File(directory, "capabilities.png")
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/capabilities.png /data/local/tmp/rx8-capabilities.png")).use { it.readBytes() }
            val preserved = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-capabilities.png")).use { it.readBytes() }
            assertArrayEquals(source.readBytes(), preserved)
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
}
