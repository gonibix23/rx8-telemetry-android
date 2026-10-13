package com.gonibix.rx8.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.dashboard.DashboardCardSize
import com.gonibix.rx8.dashboard.DashboardReading
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real WindowManager integration with synthetic values; restores the previous special-access mode. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidOverlayHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val speed = row(ObdMetric.SPEED, "7E8", 0.0, ObdReadingStatus.VALID, 500)

    @Test fun compactRowsKeepZeroMissingStaleAndAccessibleSourcesWithoutVisibleChrome() = withHost(listOf(
        speed, row(ObdMetric.SPEED, "7E9", 12.0, ObdReadingStatus.STALE, 6_000),
        row(ObdMetric.COOLANT_TEMPERATURE, "7E8", null, ObdReadingStatus.TIMEOUT, null),
    )) { fixture ->
        main {
            val panel = panel()
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val texts = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("0 km/h", "12 km/h", "— °C", "0.5 s ago", "Stale · last value · 6.0 s ago", "Timed out · Age unknown")))
            assertFalse(texts.any { "ECU" in it || "RX-8" in it || "Drag to move" in it || "Close" in it })
            assertTrue(descendants(panel).filterIsInstance<Button>().isEmpty())
            val sources = descendants(panel).mapNotNull { it.contentDescription?.toString() }
            assertTrue(sources.any { "ECU 7E8, 0 km/h, Current" in it })
            assertTrue(sources.any { "ECU 7E9, 12 km/h, Stale" in it })
            assertWindowWithinBounds(panel)
            assertTrue(panel.createAccessibilityNodeInfo().actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS })
        }
        main {
            assertTrue(panel().performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null))
            assertFalse(isShown())
            assertEquals(1, fixture.dismissals)
            fixture.host.hide()
        }
    }

    @Test fun singleCoolantWindowFitsItsRenderedLabelAndPadding() = withHost(listOf(
        row(ObdMetric.COOLANT_TEMPERATURE, "7E8", 83.0, ObdReadingStatus.VALID, 500),
    )) {
        main {
            val root = panel()
            assertTightUnwrappedWidth(root)
            assertTrue("A single coolant reading must not reserve the old 200dp width",
                root.width < (200 * root.resources.displayMetrics.density).toInt())
            val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertEquals(listOf(ObdMetric.COOLANT_TEMPERATURE.label, "83 °C", "0.5 s ago"), texts)
            assertTrue(descendants(root).any { it.contentDescription?.contains("ECU 7E8, 83 °C, Current") == true })
        }
        captureOverlayFixture()
    }

    @Test fun shortSpeedAndRpmWindowsGrowAndShrinkWithTheReadingWithoutChangingTypography() = withHost(listOf(speed)) { fixture ->
        var speedWidth = 0
        var rpmWidth = 0
        var valueTextSize = 0f
        main {
            val root = panel()
            assertTightUnwrappedWidth(root)
            speedWidth = root.width
            valueTextSize = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == "0 km/h" }.textSize
        }
        update(fixture, listOf(row(ObdMetric.ENGINE_RPM, "7E8", 750.0, ObdReadingStatus.VALID, 500)))
        main { assertTightUnwrappedWidth(panel()); rpmWidth = panel().width }
        update(fixture, listOf(row(ObdMetric.ENGINE_RPM, "7E8", 9_500.0, ObdReadingStatus.VALID, 500)))
        main {
            val root = panel()
            assertTightUnwrappedWidth(root)
            assertTrue("A wider value must expand the window", root.width > rpmWidth)
            assertEquals(valueTextSize, descendants(root).filterIsInstance<TextView>().single { it.text.toString() == "9500 rpm" }.textSize, 0.01f)
        }
        update(fixture, listOf(speed))
        main {
            assertTightUnwrappedWidth(panel())
            assertEquals("Returning to short content releases unused width", speedWidth, panel().width)
        }
        update(fixture, listOf(speed.copy(card = speed.card.copy(size = DashboardCardSize.LARGE))))
        main {
            assertTightUnwrappedWidth(panel())
            assertTrue("Larger card typography must be measured, not assigned a fixed width", panel().width > speedWidth)
        }
    }

    @Test fun expandingAtTheScreenEdgeReclampsTheWindowAndShrinkingReleasesSpace() = withHost(listOf(speed)) { fixture ->
        var smallWidth = 0
        main {
            val root = panel()
            val bounds = displayBounds(root.context)
            smallWidth = root.width
            touch(root, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(root, MotionEvent.ACTION_MOVE, bounds.first * 2f, bounds.second * 2f)
            touch(root, MotionEvent.ACTION_UP, 20f, 20f)
            val params = root.layoutParams as WindowManager.LayoutParams
            assertTrue("The fixture starts at the right edge before resizing", params.x >= bounds.first - root.width - 1)
            assertWindowWithinBounds(root)
        }
        update(fixture, listOf(row(ObdMetric.FUEL_RAIL_PRESSURE_RELATIVE, "7E8", null, ObdReadingStatus.TIMEOUT, null)))
        main {
            val root = panel()
            assertTrue("Longer parameter and quality text must expand the window", root.width > smallWidth)
            assertWindowWithinBounds(root)
            assertAllTextIsReadable(root)
            assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "Timed out · Age unknown" })
        }
        update(fixture, listOf(speed))
        main {
            assertEquals(smallWidth, panel().width)
            assertWindowWithinBounds(panel())
            assertEquals(0, fixture.dismissals)
        }
    }

    @Test fun unchangedReadingsAreRemeasuredWhenTheFontConfigurationChanges() {
        // Override only this host's resources; never change the emulator's global font preference.
        val changingContext = ChangingFontContext(context)
        withHost(listOf(speed), changingContext) { fixture ->
            var originalWidth = 0
            var originalTextSize = 0f
            main {
                originalWidth = panel().width
                originalTextSize = descendants(panel()).filterIsInstance<TextView>().single { it.text.toString() == "0 km/h" }.textSize
                changingContext.setFontScale(2f)
            }
            update(fixture, listOf(speed))
            main {
                assertTightUnwrappedWidth(panel())
                assertTrue("Equal readings still resize after a font configuration change", panel().width > originalWidth)
                assertTrue(descendants(panel()).filterIsInstance<TextView>().single { it.text.toString() == "0 km/h" }.textSize > originalTextSize)
                changingContext.setFontScale(1f)
            }
            update(fixture, listOf(speed))
            main {
                assertTightUnwrappedWidth(panel())
                assertEquals(originalWidth, panel().width)
                assertEquals(originalTextSize, descendants(panel()).filterIsInstance<TextView>().single { it.text.toString() == "0 km/h" }.textSize, 0.01f)
            }
        }
    }

    @Test fun largeFontsAndMultipleSourcesWrapWithinBoundsAndPreserveScrollOnReadingUpdates() {
        val largeContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val rows = (0 until 16).map { index ->
            row(ObdMetric.FUEL_RAIL_PRESSURE_RELATIVE, (0x7E0 + index).toString(16).uppercase(),
                index.toDouble(), ObdReadingStatus.STALE, 6_000)
        }
        withHost(rows, largeContext) { fixture ->
            var savedScroll = 0
            main {
                val root = panel()
                assertWindowWithinBounds(root)
                assertAllTextIsReadable(root)
                val texts = descendants(root).filterIsInstance<TextView>()
                assertTrue("Large-font long labels must wrap instead of clipping", texts.any { it.layout.lineCount > 1 })
                val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                assertTrue("Additional ECU rows remain reachable by scrolling", scroll.canScrollVertically(1))
                assertEquals(16, descendants(root).count { it.contentDescription?.contains("ECU 7E") == true })
                scroll.scrollTo(0, scroll.height)
                savedScroll = scroll.scrollY
                assertTrue(savedScroll > 0)
            }
            update(fixture, rows.map { it.copy(reading = it.reading.copy(ageMillis = 7_000)) })
            main {
                val root = panel()
                val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                assertEquals("Fresh text for the same sources must not jump to the first row", savedScroll, scroll.scrollY)
                assertWindowWithinBounds(root)
                assertAllTextIsReadable(root)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                assertTrue("The last ECU row must remain reachable", scroll.scrollY > savedScroll)
                assertFalse(scroll.canScrollVertically(1))
                assertEquals(0, fixture.dismissals)
            }
        }
    }

    @Test fun stationaryHoldDismissesTheWindowWithoutAVisibleButton() = withHost(listOf(speed)) { fixture ->
        main { touch(panel(), MotionEvent.ACTION_DOWN, 30f, 30f) }
        waitPastHold()
        main { assertFalse(isShown()); assertEquals(1, fixture.dismissals) }
    }

    @Test fun wholeRowDragMovesTheWindowAndNeverTurnsIntoAHold() = withHost(listOf(speed)) { fixture ->
        main {
            val panel = panel()
            val params = panel.layoutParams as WindowManager.LayoutParams
            val initialX = params.x
            val initialY = params.y
            // Start on the value, not the parameter label: a short panel drags from its whole row.
            val value = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "0 km/h" }
            val point = positionInPanel(value, panel)
            val travel = ViewConfiguration.get(context).scaledTouchSlop * 4f
            touch(panel, MotionEvent.ACTION_DOWN, point.first, point.second)
            touch(panel, MotionEvent.ACTION_MOVE, point.first + travel, point.second + travel)
            assertTrue(params.x > initialX)
            assertTrue(params.y > initialY)
            touch(panel, MotionEvent.ACTION_UP, point.first + travel, point.second + travel)
        }
        waitPastHold()
        main { assertTrue(isShown()); assertEquals(0, fixture.dismissals) }
    }

    @Test fun cancellingAndHidingCancelPendingHoldsBeforeAnotherWindowIsShown() = withHost(listOf(speed)) { fixture ->
        main {
            touch(panel(), MotionEvent.ACTION_DOWN, 30f, 30f)
            touch(panel(), MotionEvent.ACTION_CANCEL, 30f, 30f)
        }
        waitPastHold()
        main {
            assertEquals(0, fixture.dismissals)
            touch(panel(), MotionEvent.ACTION_DOWN, 30f, 30f)
            fixture.host.hide()
            fixture.host.show(listOf(speed))
        }
        waitPastHold()
        main { assertTrue(isShown()); assertEquals(0, fixture.dismissals) }
    }

    @Test fun longSourceListsScrollFromValuesAndStillDragFromParameterLabels() = withHost(
        (0 until 16).map { row(ObdMetric.SPEED, (0x7E0 + it).toString(16).uppercase(), it.toDouble(), ObdReadingStatus.VALID, 500) },
    ) { fixture ->
        main {
            val panel = panel()
            val scroll = descendants(panel).filterIsInstance<ScrollView>().single()
            assertTrue(scroll.canScrollVertically(1))
            val params = panel.layoutParams as WindowManager.LayoutParams
            val initial = params.x to params.y
            val value = descendants(panel).filterIsInstance<TextView>().first { it.text.toString() == "0 km/h" }
            val point = positionInPanel(value, panel)
            val travel = ViewConfiguration.get(context).scaledTouchSlop * 4f
            touch(panel, MotionEvent.ACTION_DOWN, point.first, point.second)
            touch(panel, MotionEvent.ACTION_MOVE, point.first, point.second - travel)
            touch(panel, MotionEvent.ACTION_MOVE, point.first, point.second - travel * 2)
            touch(panel, MotionEvent.ACTION_CANCEL, point.first, point.second - travel * 2)
            assertEquals(initial, params.x to params.y)
            assertTrue("Value swipe must scroll the source list", scroll.scrollY > 0)
            scroll.scrollTo(0, 0)
            val label = descendants(panel).filterIsInstance<TextView>().first { it.text.toString() == ObdMetric.SPEED.label }
            val labelPoint = positionInPanel(label, panel)
            touch(panel, MotionEvent.ACTION_DOWN, labelPoint.first, labelPoint.second)
            touch(panel, MotionEvent.ACTION_MOVE, labelPoint.first + travel, labelPoint.second)
            touch(panel, MotionEvent.ACTION_UP, labelPoint.first + travel, labelPoint.second)
            assertTrue(params.x > initial.first)
        }
        waitPastHold()
        main { assertTrue(isShown()); assertEquals(0, fixture.dismissals) }
    }

    private class Fixture {
        lateinit var host: AndroidOverlayHost
        var dismissals = 0
        fun close() { if (::host.isInitialized) host.hide() }
    }
    private class ChangingFontContext(base: Context) : ContextWrapper(base) {
        private var configured = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        override fun getResources(): Resources = configured.resources
        override fun getTheme(): Resources.Theme = configured.theme
        fun setFontScale(scale: Float) {
            configured = baseContext.createConfigurationContext(Configuration(baseContext.resources.configuration).apply { fontScale = scale })
        }
    }
    private fun withHost(rows: List<OverlayRow>, hostContext: Context = context, block: (Fixture) -> Unit) {
        val packageName = context.packageName
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText() }
        val previous = Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
            .find(shell("appops get $packageName SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        val fixture = Fixture()
        try {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            assertTrue(Settings.canDrawOverlays(context))
            main {
                fixture.host = AndroidOverlayHost(hostContext) { fixture.dismissals++; fixture.host.hide() }
                fixture.host.show(rows)
            }
            awaitPanelLayout()
            instrumentation.waitForIdleSync()
            block(fixture)
        } finally {
            main { fixture.close() }
            shell("appops set $packageName SYSTEM_ALERT_WINDOW $previous")
        }
    }
    private fun row(metric: ObdMetric, ecu: String, value: Double?, status: ObdReadingStatus, age: Long?) =
        OverlayRow(DashboardCardConfig(metric, showInOverlay = true), DashboardReading(metric, ecu, value, status, age, true))
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun update(fixture: Fixture, rows: List<OverlayRow>) {
        main { fixture.host.show(rows) }
        awaitPanelLayout()
        instrumentation.waitForIdleSync()
    }
    private fun displayBounds(hostContext: Context): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) hostContext.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
        else hostContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    private fun assertWindowWithinBounds(root: View) {
        val params = root.layoutParams as WindowManager.LayoutParams
        val bounds = displayBounds(root.context)
        assertEquals(params.width, root.width)
        assertTrue("Width stays below the screen-relative limit", root.width <= (bounds.first * 0.8f).toInt())
        assertTrue("Height cap includes the outer vertical padding",
            root.height <= minOf((320 * root.resources.displayMetrics.density).toInt(), (bounds.second * 0.5f).toInt()))
        assertTrue(params.x >= 0 && params.y >= 0)
        assertTrue("Resized window stays inside the right edge", params.x + root.width <= bounds.first)
        assertTrue("Resized window stays inside the bottom edge", params.y + root.height <= bounds.second)
    }
    private fun assertTightUnwrappedWidth(root: View) {
        val texts = descendants(root).filterIsInstance<TextView>()
        assertTrue(texts.isNotEmpty())
        texts.forEach { assertEquals("Short content should fit on one line: ${it.text}", 1, it.layout.lineCount) }
        val widestText = texts.maxOf { ceil(Layout.getDesiredWidth(it.text, it.paint).toDouble()).toInt() + it.compoundPaddingLeft + it.compoundPaddingRight }
        val expected = widestText + root.paddingLeft + root.paddingRight
        assertTrue("Window width ${root.width} should fit rendered text and existing padding $expected",
            kotlin.math.abs(root.width - expected) <= 2)
        assertWindowWithinBounds(root)
        assertAllTextIsReadable(root)
    }
    private fun assertAllTextIsReadable(root: View) {
        descendants(root).filterIsInstance<TextView>().forEach { text ->
            val layout = requireNotNull(text.layout)
            assertEquals("The whole label/value/status remains available", text.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue("Line must fit within its text view: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f)
            }
        }
    }
    private fun panel() = WindowInspector.getGlobalWindowViews().single { it.contentDescription == "Floating live readings" }
    private fun isShown() = WindowInspector.getGlobalWindowViews().any { it.contentDescription == "Floating live readings" }
    private fun positionInPanel(view: View, panel: View): Pair<Float, Float> {
        val child = IntArray(2); val root = IntArray(2)
        view.getLocationOnScreen(child); panel.getLocationOnScreen(root)
        return (child[0] - root[0] + view.width / 2f) to (child[1] - root[1] + view.height / 2f)
    }
    private fun touch(panel: View, action: Int, x: Float, y: Float) {
        val location = IntArray(2); panel.getLocationOnScreen(location)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x + location[0], y + location[1], 0)
        event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
        try { panel.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun waitPastHold() {
        val latch = CountDownLatch(1)
        val delay = ViewConfiguration.getLongPressTimeout().toLong() + 100
        Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, delay)
        assertTrue("Main-thread hold timer must complete", latch.await(delay + 3_000, TimeUnit.MILLISECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun awaitPanelLayout() {
        val laidOut = CountDownLatch(1)
        main {
            val root = panel()
            if (root.isLaidOut && !root.isLayoutRequested) laidOut.countDown()
            else root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (right > left && bottom > top) {
                        view.removeOnLayoutChangeListener(this)
                        laidOut.countDown()
                    }
                }
            })
        }
        assertTrue("Overlay must complete layout before coordinate assertions", laidOut.await(5, TimeUnit.SECONDS))
    }
    private fun captureOverlayFixture() {
        var rendered: Bitmap? = null
        main {
            val root = panel()
            assertTrue("Capture only the laid-out synthetic panel", root.isLaidOut && root.width > 0 && root.height > 0)
            val panelBitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                // Draw this native view only: never capture another app or the underlying screen.
                root.draw(Canvas(panelBitmap))
                assertTrue("Overlay capture must contain panel and reading pixels", hasOverlayContent(panelBitmap))
                val density = context.resources.displayMetrics.density
                val padding = (12 * density).toInt()
                val banner = listOf("UI TEST FIXTURE", "SYNTHETIC OVERLAY VALUES")
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * density }
                val lineHeight = ceil(paint.fontSpacing).toInt()
                val headerHeight = padding * 2 + lineHeight * banner.size
                val width = maxOf(root.width, ceil(banner.maxOf(paint::measureText)).toInt()) + padding * 2
                rendered = Bitmap.createBitmap(width, headerHeight + root.height + padding, Bitmap.Config.ARGB_8888).also { capture ->
                    val canvas = Canvas(capture)
                    canvas.drawColor(Color.rgb(9, 12, 14))
                    banner.forEachIndexed { index, line ->
                        canvas.drawText(line, padding.toFloat(), padding - paint.fontMetrics.top + index * lineHeight, paint)
                    }
                    canvas.drawBitmap(panelBitmap, (width - root.width) / 2f, headerHeight.toFloat(), null)
                }
            } finally { panelBitmap.recycle() }
        }
        val captured = requireNotNull(rendered)
        try {
            val directory = File(context.getExternalFilesDir(null), "screenshots")
            assertTrue(directory.isDirectory || directory.mkdirs())
            val source = File(directory, "overlay.png")
            source.outputStream().use { assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/overlay.png /data/local/tmp/rx8-overlay.png",
            )).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /data/local/tmp/rx8-overlay.png",
            )).use { it.readBytes() }
            assertArrayEquals("The overlay screenshot must survive teardown", source.readBytes(), copied)
        } finally { captured.recycle() }
    }
    private fun hasOverlayContent(bitmap: Bitmap): Boolean {
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
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
