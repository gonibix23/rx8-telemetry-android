package com.gonibix.rx8.trips

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saved-source fixtures only. These values do not establish physical vehicle compatibility. */
@RunWith(AndroidJUnit4::class)
class TripSensorChartTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anEmptyTripHasNoInventedSensorOrSupportClaim() {
        show(options = emptyList(), selected = null, series = null)
        compose.onNodeWithTag("empty_trip_series").assertTextEquals("No sensor readings were recorded in this trip.")
        compose.onNodeWithTag("trip_sensor_chart").assertDoesNotExist()
        compose.onNodeWithTag("trip_metric_speed").assertDoesNotExist()
        compose.onNodeWithTag("trip_series_range").assertDoesNotExist()
    }

    @Test fun sixRecordedMetricsAreSelectableAndPreferTheCurrentRecordedEcu() {
        val calls = mutableListOf<TripSeriesKey>()
        val selected = TripSeriesKey(ObdMetric.SPEED, "7E9")
        val options = ObdMetric.entries.map { TripSeriesKey(it, "7E8") } + selected + TripSeriesKey(ObdMetric.COOLANT_TEMPERATURE, "7E9")
        show(options = options, selected = selected, series = null, onSelect = { calls += it })
        assertTrue(calls.isEmpty())
        ObdMetric.entries.forEach { metric -> compose.onNodeWithTag("trip_metric_${metric.name.lowercase(Locale.ROOT)}").assertIsEnabled() }
        compose.onNodeWithTag("trip_metric_speed").assertIsSelected().performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("trip_metric_coolant_temperature").performClick()
        compose.onNodeWithTag("trip_source_7E8").performClick()
        assertEquals(listOf(TripSeriesKey(ObdMetric.COOLANT_TEMPERATURE, "7E9"), TripSeriesKey(ObdMetric.SPEED, "7E8")), calls)
        compose.onNodeWithTag("trip_source_7E9").assertIsSelected()
    }

    @Test fun metricWithoutTheCurrentEcuSelectsItsRecordedSourceOnly() {
        val calls = mutableListOf<TripSeriesKey>()
        val rpm = TripSeriesKey(ObdMetric.ENGINE_RPM, "7E9")
        show(options = listOf(coolant, rpm), selected = rpm, series = null, onSelect = { calls += it })
        compose.onNodeWithTag("trip_metric_coolant_temperature").performClick()
        assertEquals(listOf(coolant), calls)
        compose.onNodeWithTag("trip_metric_intake_temperature").assertDoesNotExist()
    }

    @Test fun negativeTemperaturesAndStatisticsUseTheOriginalSelectedStream() {
        val series = fixtureSeries().copy(minValue = -12.0, maxValue = 110.0, sampleCount = 40, validSampleCount = 30, coveredMillis = 3_000)
        show(series = series)
        compose.onNodeWithTag("trip_series_source").assertTextEquals("${ObdMetric.COOLANT_TEMPERATURE.label} · ECU 7E8 · °C")
        compose.onNodeWithTag("trip_series_counts").assertTextEquals("40 saved readings · 30 valid")
        compose.onNodeWithTag("trip_series_range").assertTextEquals("Observed range: -12 to 110 °C")
        compose.onNodeWithTag("trip_series_coverage").assertTextEquals("Valid interval coverage: 0:03 · uncovered: 0:07")
        compose.onNodeWithTag("trip_sensor_chart").performScrollTo().assertIsDisplayed().assertContentDescriptionEquals("Recorded ${ObdMetric.COOLANT_TEMPERATURE.label.lowercase(Locale.ENGLISH)} chart from ECU 7E8. 30 valid of 40 saved readings. Observed minimum -12 °C, maximum 110 °C. Gaps are not joined.")
    }

    @Test fun aConstantZeroTemperatureHasANondegenerateAxisAndKeepsTheRealRange() {
        show(series = TripSeriesAnalysis(coolant, 2, 2, 0.0, 0.0, 1_000, listOf(point(0, 0.0), point(1_000, 0.0))))
        compose.onNodeWithTag("trip_series_range").assertTextEquals("Observed range: 0 to 0 °C")
        compose.onNodeWithTag("trip_series_axis").assertTextEquals("Scale: -1 to 1 °C")
        compose.onNodeWithTag("trip_sensor_chart").performScrollTo().assertIsDisplayed()
    }

    @Test fun allInvalidReadingsKeepCountsWithoutAnInventedZeroChart() {
        show(series = TripSeriesAnalysis(coolant, 3, 0, null, null, 0, listOf(point(0, null), point(1_000, null), point(2_000, null))))
        compose.onNodeWithTag("trip_series_counts").assertTextEquals("3 saved readings · 0 valid")
        compose.onNodeWithTag("trip_series_range").assertTextEquals("Observed range: Not available")
        compose.onNodeWithTag("empty_trip_chart").assertTextEquals("No valid recorded values for this sensor and ECU.")
        compose.onNodeWithTag("trip_sensor_chart").assertDoesNotExist()
    }

    @Test fun loadingHidesPriorStatisticsButAllowsSelectingAnotherMetric() {
        val calls = mutableListOf<TripSeriesKey>()
        val rpm = TripSeriesKey(ObdMetric.ENGINE_RPM, "7E8")
        show(options = listOf(coolant, rpm), loading = true, onSelect = { calls += it })
        compose.onNodeWithTag("trip_series_loading").assertIsDisplayed()
        compose.onNodeWithTag("trip_series_range").assertDoesNotExist()
        compose.onNodeWithTag("trip_sensor_chart").assertDoesNotExist()
        compose.onNodeWithTag("trip_metric_engine_rpm").assertIsEnabled().performClick()
        assertEquals(listOf(rpm), calls)
    }

    @Test fun failureHasAnExplicitRetryWithoutShowingOldSeriesAsCurrent() {
        var retries = 0
        show(error = "Saved chart could not be loaded. Try again.", onRetry = { retries++ })
        compose.onNodeWithTag("trip_series_error").assertTextEquals("Saved chart could not be loaded. Try again.")
        compose.onNodeWithTag("trip_sensor_chart").assertDoesNotExist()
        assertEquals(0, retries)
        compose.onNodeWithTag("retry_trip_series").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test fun aSeriesFromAnotherEcuIsNeverDisplayedUnderTheSelectedSource() {
        show(series = fixtureSeries().copy(key = coolant.copy(ecuId = "7E9")))
        compose.onNodeWithTag("trip_series_prompt").assertIsDisplayed()
        compose.onNodeWithTag("trip_series_range").assertDoesNotExist()
        compose.onNodeWithTag("trip_sensor_chart").assertDoesNotExist()
    }

    @Test fun canvasSegmentsBreakAtGapsNonfiniteForeignAndRepeatedPoints() {
        val series = fixtureSeries().copy(points = listOf(
            point(0, -5.0), point(1_000, null), point(2_000, -2.0), point(3_000, 90.0).copy(ecuId = "7E9"),
            point(4_000, 0.0), point(4_000, 1.0), point(5_000, Double.NaN), point(6_000, 3.0), point(11_000, 10.0),
        ))
        assertEquals(listOf(listOf(-5.0), listOf(-2.0), listOf(0.0), listOf(1.0), listOf(3.0)), tripChartSegments(series, 10_000).map { it.map { point -> point.value } })
    }

    @Test fun reducedPointSpacingDoesNotInventGapsAndAxesHandleConstantValues() {
        val series = fixtureSeries().copy(points = listOf(point(0, -5.0), point(20_000, 0.0)))
        assertEquals(1, tripChartSegments(series, 20_000).size)
        assertEquals(TripChartAxis(-6.0, -4.0), tripChartAxis(ObdMetric.INTAKE_TEMPERATURE, -5.0, -5.0))
        assertEquals(TripChartAxis(0.0, 1.0), tripChartAxis(ObdMetric.SPEED, 0.0, 0.0))
        assertEquals(TripChartAxis(0.0, 100.0), tripChartAxis(ObdMetric.ENGINE_LOAD, 0.0, 100.0))
    }

    @Test fun syntheticSensorScreenshotShowsSelectedSourceNegativeRangeAndGaps() {
        show(options = listOf(TripSeriesKey(ObdMetric.ENGINE_RPM, "7E8"), TripSeriesKey(ObdMetric.SPEED, "7E8"), coolant, coolant.copy(ecuId = "7E9")), fixtureBanner = true)
        compose.onNodeWithTag("trip_sensor_chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("trip_series_source").assertIsDisplayed()
        compose.onNodeWithTag("trip_series_range").assertTextEquals("Observed range: -5 to 10 °C").assertIsDisplayed()
        compose.onNodeWithTag("trip_source_7E8").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("UI TEST FIXTURE · SYNTHETIC TRIP SENSORS").assertIsDisplayed()
        captureFixture()
    }

    private fun show(
        options: List<TripSeriesKey> = listOf(coolant),
        selected: TripSeriesKey? = coolant,
        series: TripSeriesAnalysis? = fixtureSeries(),
        loading: Boolean = false,
        error: String? = null,
        onSelect: (TripSeriesKey) -> Unit = {},
        onRetry: () -> Unit = {},
        fixtureBanner: Boolean = false,
    ) {
        compose.setContent {
            Rx8Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.safeDrawingPadding()) {
                        if (fixtureBanner) Text("UI TEST FIXTURE · SYNTHETIC TRIP SENSORS")
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                            TripSensorChart(options, selected, series, loading, error, 10_000, onSelect, onRetry)
                        }
                    }
                }
            }
        }
    }

    private fun fixtureSeries() = TripSeriesAnalysis(coolant, 6, 5, -5.0, 10.0, 7_000, listOf(point(0, -5.0), point(2_000, -2.0), point(3_000, 0.0), point(4_000, null), point(6_000, 3.0), point(10_000, 10.0)))
    private fun point(time: Long, value: Double?) = TripChartPoint(time, value, "7E8")

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
            val source = File(directory, "trip-sensors.png")
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/trip-sensors.png /data/local/tmp/rx8-trip-sensors.png")).use { it.readBytes() }
            val preserved = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-trip-sensors.png")).use { it.readBytes() }
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

    private companion object {
        val coolant = TripSeriesKey(ObdMetric.COOLANT_TEMPERATURE, "7E8")
    }
}
