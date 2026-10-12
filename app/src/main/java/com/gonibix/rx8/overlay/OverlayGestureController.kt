package com.gonibix.rx8.overlay

/** A hold can dismiss only the same stationary, single-pointer gesture that started it. */
internal class OverlayGestureController(
    private val touchSlop: Float,
    private val holdMillis: Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onMove: (Int, Int) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var generation = 0L
    private var pointer: Int? = null
    private var cancelHold: (() -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var originX = 0
    private var originY = 0
    private var canDrag = false
    private var moved = false

    fun begin(pointerId: Int, rawX: Float, rawY: Float, x: Int, y: Int, drag: Boolean) {
        cancel()
        if (!rawX.isFinite() || !rawY.isFinite()) return
        pointer = pointerId
        startX = rawX; startY = rawY; originX = x; originY = y
        canDrag = drag
        moved = false
        val expectedGeneration = generation
        cancelHold = schedule(holdMillis) {
            if (pointer != null && generation == expectedGeneration && !moved) {
                cancel()
                onDismiss()
            }
        }
    }

    fun move(pointerId: Int, rawX: Float, rawY: Float) {
        if (pointer == null) return
        if (pointer != pointerId || !rawX.isFinite() || !rawY.isFinite()) { cancel(); return }
        val dx = rawX - startX
        val dy = rawY - startY
        if (!moved && dx * dx + dy * dy > touchSlop * touchSlop) {
            moved = true
            cancelHold?.invoke(); cancelHold = null
        }
        if (moved && canDrag) onMove(originX + dx.toInt(), originY + dy.toInt())
    }

    /** Used on up, cancel, additional pointers, hiding and window detachment. */
    fun cancel() {
        generation++
        pointer = null
        cancelHold?.invoke(); cancelHold = null
    }
}
