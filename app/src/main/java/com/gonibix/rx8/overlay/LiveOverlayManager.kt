package com.gonibix.rx8.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MainThread
import com.gonibix.rx8.R
import com.gonibix.rx8.dashboard.DashboardCardConfig
import com.gonibix.rx8.dashboard.DashboardCardSize
import com.gonibix.rx8.dashboard.metricDisplayValue
import com.gonibix.rx8.dashboard.metricStatusLabel
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owned by the existing connected-device service. Never starts acquisition or a second service. */
@MainThread
class LiveOverlayManager(context: Context, private val scope: CoroutineScope) {
    private val application = context.applicationContext
    private val host = AndroidOverlayHost(application) { setEnabled(false) }
    private val controller = OverlayController(host, SystemClock::elapsedRealtime)
    val state = controller.state
    private var ticker: Job? = null
    private var closed = false
    private val visibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshPermission() }
    }
    init {
        ContextCompat.registerReceiver(application, visibilityReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshScreen()
    }
    private fun refreshScreen() {
        val unlocked = application.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
        val interactive = application.getSystemService(PowerManager::class.java)?.isInteractive == true
        controller.setScreenAvailable(unlocked && interactive)
    }

    fun update(snapshot: ObdSessionSnapshot, connected: Boolean, cards: List<DashboardCardConfig>) {
        if (closed) return
        refreshScreen()
        controller.update(snapshot, connected, cards)
        updateTicker()
    }
    fun setEnabled(enabled: Boolean): Boolean {
        if (closed) return false
        refreshScreen()
        return controller.setEnabled(enabled).also { updateTicker() }
    }
    fun setAppVisible(visible: Boolean) { controller.setAppVisible(visible) }
    fun refreshPermission() { if (closed) return; refreshScreen(); controller.refresh(); updateTicker() }
    fun close() {
        if (closed) return
        closed = true
        ticker?.cancel(); ticker = null
        controller.close()
        runCatching { application.unregisterReceiver(visibilityReceiver) }
    }
    private fun updateTicker() {
        if (!state.value.enabled) { ticker?.cancel(); ticker = null }
        else if (ticker?.isActive != true) ticker = scope.launch(Dispatchers.Main.immediate) {
            while (state.value.enabled) { delay(1_000); refreshScreen(); controller.refresh() }
        }
    }
}

/** A small touchable panel; outside touches and keyboard focus remain with the other app. */
internal class AndroidOverlayHost(private val context: Context, private val onClose: () -> Unit) : OverlayHost {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var content: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var rendered = emptyList<OverlayRow>()
    private var x = 16
    private var y = 100
    override fun permissionGranted() = Settings.canDrawOverlays(context)

    override fun show(rows: List<OverlayRow>) {
        check(permissionGranted())
        if (panel == null) create()
        if (rows == rendered) return
        rendered = rows
        val body = requireNotNull(content)
        body.removeAllViews()
        rows.forEach { row ->
            val reading = row.reading
            val large = row.card.size == DashboardCardSize.LARGE
            val current = reading.status == ObdReadingStatus.VALID
            body.addView(label("${reading.metric.label} · ECU ${reading.ecuId}", 12f, MUTED))
            body.addView(label("${metricDisplayValue(reading)} ${reading.metric.unit}", if (large) 32f else 24f,
                if (current) Color.WHITE else AMBER).apply { typeface = Typeface.MONOSPACE })
            val age = reading.ageMillis?.let { String.format(Locale.ROOT, " · %.1f s", it / 1_000.0) }.orEmpty()
            body.addView(label(metricStatusLabel(reading.status) + age, 11f, if (current) MINT else AMBER).apply {
                setPadding(0, 0, 0, dp(10))
            })
        }
        val bounds = displayBounds()
        val large = rows.any { it.card.size == DashboardCardSize.LARGE }
        params?.apply { width = minOf(dp(if (large) 310 else 250), (bounds.first * 0.8f).toInt()) }
        scroller?.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            minOf(dp(rows.size * 92), dp(360), (bounds.second * 0.55f).toInt()))
        moveTo(x, y)
    }

    @SuppressLint("ClickableViewAccessibility") // Drag handle has a descriptive label; Close is a normal accessible button.
    private fun create() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Floating live readings"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(22, 27, 30)); cornerRadius = dp(18).toFloat() }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = label("RX-8 · Drag to move", 13f, MINT).apply {
            contentDescription = "Floating readings. Drag to move the panel."
            setPadding(0, dp(8), 0, dp(8))
        }
        var startX = 0f; var startY = 0f; var originX = 0; var originY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originX = x; originY = y; true }
                MotionEvent.ACTION_MOVE -> {
                    runCatching { moveTo(originX + (event.rawX - startX).toInt(), originY + (event.rawY - startY).toInt()) }
                        .onFailure { hide(); onClose() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        header.addView(handle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(context).apply {
            text = context.getString(R.string.overlay_close); contentDescription = "Close floating readings"; textSize = 12f
            minWidth = 0; minimumWidth = 0; setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(76), dp(48)))
        root.addView(header)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
        root.addView(scroll)
        val layout = WindowManager.LayoutParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@AndroidOverlayHost.x; y = this@AndroidOverlayHost.y }
        manager.addView(root, layout)
        panel = root; content = body; scroller = scroll; params = layout
    }

    private fun moveTo(nextX: Int, nextY: Int) {
        val root = panel ?: return
        val layout = params ?: return
        val bounds = displayBounds()
        x = nextX.coerceIn(0, (bounds.first - layout.width).coerceAtLeast(0))
        y = nextY.coerceIn(0, (bounds.second - root.height.coerceAtLeast(dp(80))).coerceAtLeast(0))
        layout.x = x; layout.y = y
        manager.updateViewLayout(root, layout)
    }
    private fun displayBounds(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    private fun label(value: String, size: Float, color: Int) = TextView(context).apply { text = value; textSize = size; setTextColor(color) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    override fun hide() {
        panel?.let { runCatching { manager.removeViewImmediate(it) } }
        panel = null; content = null; scroller = null; params = null; rendered = emptyList()
    }
    private companion object {
        val MUTED = Color.rgb(170, 182, 188)
        val AMBER = Color.rgb(255, 183, 99)
        val MINT = Color.rgb(109, 222, 196)
    }
}
