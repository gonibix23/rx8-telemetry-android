package com.gonibix.rx8.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayGestureControllerTest {
    private class Fixture {
        data class Scheduled(val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Scheduled>()
        val moves = mutableListOf<Pair<Int, Int>>()
        var dismissals = 0
        val gesture = OverlayGestureController(8f, 500,
            schedule = { delay, action ->
                assertEquals(500L, delay)
                val task = Scheduled(action).also(tasks::add)
                val cancel: () -> Unit = { task.cancelled = true }
                cancel
            }, onMove = { x, y -> moves += x to y }, onDismiss = { dismissals++ })
        fun down(drag: Boolean = true) = gesture.begin(1, 100f, 200f, 20, 30, drag)
        fun fireIncludingCancelledTasks() = tasks.toList().forEach { it.action() }
    }

    @Test fun stationaryHoldDismissesOnceAndSubSlopJitterDoesNotMoveThePanel() {
        val f = Fixture(); f.down()
        f.gesture.move(1, 102f, 201f)
        f.fireIncludingCancelledTasks(); f.fireIncludingCancelledTasks()
        assertEquals(1, f.dismissals)
        assertTrue(f.moves.isEmpty())
    }

    @Test fun dragUsesOriginalScreenPositionAndPermanentlyCancelsTheHold() {
        val f = Fixture(); f.down()
        f.gesture.move(1, 140f, 230f)
        f.gesture.move(1, 100f, 200f) // Returning to the start is still a drag, never a new hold.
        f.fireIncludingCancelledTasks()
        assertEquals(listOf(60 to 60, 20 to 30), f.moves)
        assertEquals(0, f.dismissals)
        assertTrue(f.tasks.single().cancelled)
    }

    @Test fun scrollingMovementCancelsDismissalWithoutMovingTheWindow() {
        val f = Fixture(); f.down(drag = false)
        f.gesture.move(1, 100f, 140f)
        f.fireIncludingCancelledTasks()
        assertTrue(f.moves.isEmpty())
        assertEquals(0, f.dismissals)
    }

    @Test fun cancellationIsIdempotentAndRejectsLateMovementOrTimerCallbacks() {
        val f = Fixture(); f.down()
        f.gesture.cancel(); f.gesture.cancel()
        f.fireIncludingCancelledTasks()
        f.gesture.move(1, 150f, 250f)
        assertEquals(0, f.dismissals)
        assertTrue(f.moves.isEmpty())
    }

    @Test fun obsoleteHoldCannotDismissANewGestureOrANewlyShownWindow() {
        val f = Fixture(); f.down()
        val obsolete = f.tasks.single()
        f.gesture.cancel(); f.down()
        obsolete.action()
        assertEquals(0, f.dismissals)
        f.tasks.last().action()
        assertEquals(1, f.dismissals)
    }

    @Test fun pointerReplacementAndInvalidCoordinatesFailClosed() {
        listOf(2 to (100f to 200f), 1 to (Float.NaN to 200f), 1 to (100f to Float.POSITIVE_INFINITY)).forEach { (pointer, position) ->
            val f = Fixture(); f.down()
            f.gesture.move(pointer, position.first, position.second)
            f.fireIncludingCancelledTasks()
            assertEquals(0, f.dismissals)
            assertTrue(f.moves.isEmpty())
        }
    }

    @Test fun invalidDownDoesNotScheduleADismissal() {
        val f = Fixture()
        f.gesture.begin(1, Float.NaN, 0f, 0, 0, true)
        assertTrue(f.tasks.isEmpty())
    }
}
