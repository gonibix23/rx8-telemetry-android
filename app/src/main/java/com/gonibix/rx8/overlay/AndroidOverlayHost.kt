package com.gonibix.rx8.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.gonibix.rx8.dashboard.DashboardCardSize
import com.gonibix.rx8.dashboard.metricDisplayValue
import com.gonibix.rx8.dashboard.metricStatusLabel
import com.gonibix.rx8.obd.ObdReadingStatus
import java.util.Locale

/** A compact touchable window; outside touches and keyboard focus stay with the other app. */
internal class AndroidOverlayHost(private val context: Context, private val onClose: () -> Unit) : OverlayHost {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var panel: OverlayTouchSurface? = null
    private var content: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private val dragLabels = mutableListOf<View>()
    private var rendered = emptyList<OverlayRow>()
    private var panelHeight = 0
    private var x = 16
    private var y = 100
    override fun permissionGranted() = Settings.canDrawOverlays(context)

    override fun show(rows: List<OverlayRow>) {
        check(permissionGranted())
        if (panel == null) create()
        if (rows == rendered) return
        rendered = rows
        val root = requireNotNull(panel)
        val body = requireNotNull(content)
        body.removeAllViews()
        dragLabels.clear()
        rows.forEach { row ->
            val reading = row.reading
            val current = reading.status == ObdReadingStatus.VALID
            val value = "${metricDisplayValue(reading)} ${reading.metric.unit}"
            val age = reading.ageMillis?.let { String.format(Locale.ROOT, "%.1f s ago", it / 1_000.0) } ?: "Age unknown"
            val status = metricStatusLabel(reading.status)
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(3), 0, dp(5))
                // Each ECU remains a separate row and accessible source, without visible source chrome.
                contentDescription = "${reading.metric.label}, ECU ${reading.ecuId}, $value, $status, $age"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                addDismissAction(this)
            }
            item.addView(label(reading.metric.label, 11f, MUTED).also { dragLabels += it })
            item.addView(label(value, if (row.card.size == DashboardCardSize.LARGE) 28f else 22f,
                if (current) Color.WHITE else AMBER).apply { typeface = Typeface.MONOSPACE })
            item.addView(label(if (current) age else "$status · $age", 10f, if (current) MINT else AMBER))
            body.addView(item)
        }
        val bounds = displayBounds()
        val width = minOf(dp(if (rows.any { it.card.size == DashboardCardSize.LARGE }) 240 else 200), (bounds.first * 0.8f).toInt())
        params?.width = width
        // Measure wrapped labels/values at the actual font scale instead of assigning a fixed row height.
        body.measure(View.MeasureSpec.makeMeasureSpec((width - root.paddingLeft - root.paddingRight).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(body.measuredHeight, dp(320), (bounds.second * 0.5f).toInt())
        scroller?.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        panelHeight = height + root.paddingTop + root.paddingBottom
        moveTo(x, y)
    }

    private fun create() {
        val root = OverlayTouchSurface(context,
            position = { x to y },
            canDrag = { rawX, rawY ->
                val scroll = scroller
                // Whole rows drag when the list fits. In a long list, labels drag and values scroll.
                scroll == null || (!scroll.canScrollVertically(-1) && !scroll.canScrollVertically(1)) ||
                    dragLabels.any { label ->
                        val visible = Rect()
                        val location = IntArray(2)
                        label.getLocationOnScreen(location)
                        label.getLocalVisibleRect(visible) && visible.apply { offset(location[0], location[1]) }.contains(rawX.toInt(), rawY.toInt())
                    }
            },
            onMove = { nextX, nextY -> runCatching { moveTo(nextX, nextY) }.onFailure { hide(); onClose() } },
        ).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Floating live readings"
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply { setColor(Color.rgb(22, 27, 30)); cornerRadius = dp(12).toFloat() }
            elevation = dp(8).toFloat()
            setOnLongClickListener { onClose(); true }
            addDismissAction(this)
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(body); isFillViewport = false }
        root.addView(scroll)
        val layout = WindowManager.LayoutParams(dp(200), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@AndroidOverlayHost.x; y = this@AndroidOverlayHost.y }
        manager.addView(root, layout)
        panel = root; content = body; scroller = scroll; params = layout
    }

    private fun addDismissAction(view: View) {
        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_DISMISS, "Dismiss floating readings") { _, _ ->
            panel?.cancelGesture()
            onClose()
            true
        }
    }

    private fun moveTo(nextX: Int, nextY: Int) {
        val root = panel ?: return
        val layout = params ?: return
        val bounds = displayBounds()
        x = nextX.coerceIn(0, (bounds.first - layout.width).coerceAtLeast(0))
        y = nextY.coerceIn(0, (bounds.second - panelHeight.coerceAtLeast(1)).coerceAtLeast(0))
        layout.x = x; layout.y = y
        manager.updateViewLayout(root, layout)
    }

    private fun displayBounds(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // The row reads its value, quality, age and ECU together.
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    override fun hide() {
        panel?.let { root -> root.cancelGesture(); runCatching { manager.removeViewImmediate(root) } }
        panel = null; content = null; scroller = null; params = null; rendered = emptyList(); dragLabels.clear()
    }

    private companion object {
        val MUTED = Color.rgb(170, 182, 188)
        val AMBER = Color.rgb(255, 183, 99)
        val MINT = Color.rgb(109, 222, 196)
    }
}

/** Observes scrolling gestures too, so any movement cancels a pending hold-to-dismiss. */
@SuppressLint("ViewConstructor") // Service-owned window with explicit callbacks; never inflated from XML or a layout editor.
internal class OverlayTouchSurface(
    context: Context,
    private val position: () -> Pair<Int, Int>,
    private val canDrag: (Float, Float) -> Boolean,
    onMove: (Int, Int) -> Unit,
) : LinearLayout(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gesture = OverlayGestureController(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        ViewConfiguration.getLongPressTimeout().toLong(),
        schedule = { delay, action ->
            val task = Runnable(action)
            mainHandler.postDelayed(task, delay)
            val cancel: () -> Unit = { mainHandler.removeCallbacks(task) }
            cancel
        }, onMove = onMove, onDismiss = { performLongClick() })
    private var dragging = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = canDrag(event.rawX, event.rawY)
                val (x, y) = position()
                gesture.begin(event.getPointerId(0), event.rawX, event.rawY, x, y, dragging)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) gesture.move(event.getPointerId(0), event.rawX, event.rawY) else gesture.cancel()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture.cancel()
        }
        if (!dragging) super.dispatchTouchEvent(event)
        if (dragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }

    // Our gesture controller owns timing; View's separate long-click timer must not survive a move.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun cancelGesture() { gesture.cancel() }
    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
}
