package com.gonibix.rx8.overlay

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.dashboard.DashboardReading
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real WindowManager integration with synthetic values; restores the previous special-access mode. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidOverlayHostTest {
    @Test fun panelUsesAnUnfocusedOverlayWindowAndAccessibleCloseRemovesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val packageName = context.packageName
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText() }
        val previous = Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
            .find(shell("appops get $packageName SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        var host: AndroidOverlayHost? = null
        try {
            shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            assertTrue(Settings.canDrawOverlays(context))
            instrumentation.runOnMainSync {
                val instance = AndroidOverlayHost(context) { host?.hide() }
                host = instance
                instance.show(listOf(OverlayRow(DashboardCardConfig(ObdMetric.SPEED, showInOverlay = true),
                    DashboardReading(ObdMetric.SPEED, "7E8", 0.0, ObdReadingStatus.VALID, 500, true))))
                val panel = WindowInspector.getGlobalWindowViews().single { it.contentDescription == "Floating live readings" }
                val params = panel.layoutParams as WindowManager.LayoutParams
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.text.toString() == "0 km/h" })
                assertTrue(descendants(panel).filterIsInstance<TextView>().any { "ECU 7E8" in it.text })
                descendants(panel).filterIsInstance<Button>().single { it.contentDescription == "Close floating readings" }.performClick()
                assertFalse(WindowInspector.getGlobalWindowViews().any { it.contentDescription == "Floating live readings" })
                instance.hide() // Repeated cleanup is harmless even while the owning service stays bound.
            }
        } finally {
            instrumentation.runOnMainSync { host?.hide() }
            shell("appops set $packageName SYSTEM_ALERT_WINDOW $previous")
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
