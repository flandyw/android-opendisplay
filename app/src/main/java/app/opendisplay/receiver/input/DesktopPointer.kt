package app.opendisplay.receiver.input

import kotlin.math.hypot

/** Mouse state shared by absolute mouse input and captured relative trackpad input. */
class DesktopPointer(
    private val controls: InputControls,
    private val touch: (String, Double, Double, Int) -> Unit,
    private val hover: (Double, Double, Int) -> Unit,
    private val click: (String, Double, Double, Int) -> Boolean,
    private val scroll: (Double, Double) -> Unit,
    private val shortcut: (Int, Int) -> Boolean,
    private val echo: (Double, Double) -> Unit = { _, _ -> },
) {
    var x = 0.5; private set
    var y = 0.5; private set
    private var primary = false
    private var secondary = false
    private var gestureStart = 0L
    private var fingers = 0
    private var travelX = 0.0
    private var travelY = 0.0
    private var distance = 0.0
    private var gestureFired = false
    private var physicalClick = false
    private var tapDragging = false
    private var lastTap = Long.MIN_VALUE

    fun position(nx: Double, ny: Double) {
        x = nx.coerceIn(0.0, 1.0)
        y = ny.coerceIn(0.0, 1.0)
    }

    fun move(dx: Double, dy: Double, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val speed = controls.ui.value.pointerSpeed
        position(x + dx * speed / width, y + dy * speed / height)
        motion()
    }

    fun motion() {
        if (primary) touch("moved", x, y, controls.activeMods) else hover(x, y, controls.activeMods)
        echo(x, y)
    }

    fun buttons(left: Boolean, right: Boolean) {
        if (left != primary) {
            touch(if (left) "began" else "ended", x, y, controls.activeMods)
            if (!left) controls.consumeOneShot()
        }
        if (right && !secondary && click("right", x, y, controls.activeMods)) controls.consumeOneShot()
        if (left || right) physicalClick = true
        primary = left
        secondary = right
    }

    /** Raw contacts can coexist with a synthetic tap-and-hold drag. */
    fun physicalButtons(left: Boolean, right: Boolean) {
        if (tapDragging && !left && !right) return
        if (left || right) tapDragging = false
        buttons(left, right)
    }

    fun wheel(dx: Double, dy: Double) {
        val direction = if (controls.ui.value.reverseScroll) -1 else 1
        if (dx != 0.0 || dy != 0.0) scroll(dx * direction, dy * direction)
    }

    fun beginContact(time: Long) {
        gestureStart = time
        fingers = 1
        travelX = 0.0
        travelY = 0.0
        distance = 0.0
        gestureFired = false
        physicalClick = primary || secondary
        // Tap, then touch again and hold to drag without pressing the cover's button.
        tapDragging = lastTap != Long.MIN_VALUE && time - lastTap in 0..250
        lastTap = Long.MIN_VALUE
        if (tapDragging) buttons(true, false)
    }

    fun contactMove(count: Int, dx: Double, dy: Double, width: Int, height: Int) {
        fingers = maxOf(fingers, count)
        travelX += dx
        travelY += dy
        distance += hypot(dx, dy)
        when {
            primary -> move(dx, dy, width, height)
            fingers >= 3 -> {
                if (!gestureFired && hypot(travelX, travelY) >= 72) {
                    val code = if (kotlin.math.abs(travelY) > kotlin.math.abs(travelX)) {
                        if (travelY < 0) MacKeys.ARROW_UP else MacKeys.ARROW_DOWN
                    } else if (travelX < 0) MacKeys.ARROW_RIGHT else MacKeys.ARROW_LEFT
                    gestureFired = shortcut(code, Mods.CTRL)
                }
            }
            count == 2 -> wheel(dx, dy)
            count == 1 && fingers == 1 -> move(dx, dy, width, height)
        }
    }

    fun addContact(count: Int) {
        fingers = maxOf(fingers, count)
        if (tapDragging) {
            cancel()
            tapDragging = false
        }
        fingers = maxOf(fingers, count)
        // Do not let movement from a one-finger pointer gesture trigger a swipe.
        travelX = 0.0
        travelY = 0.0
    }

    fun endContact(time: Long, cancelled: Boolean) {
        if (cancelled) cancel() else if (tapDragging) buttons(false, false) else if (!physicalClick &&
            !gestureFired && time - gestureStart in 0..250 && distance < 8
        ) {
            when (fingers) {
                1 -> {
                    buttons(true, false)
                    buttons(false, false)
                    lastTap = time
                }
                2 -> if (click("right", x, y, controls.activeMods)) controls.consumeOneShot()
            }
        }
        fingers = 0
        tapDragging = false
    }

    fun cancel() {
        if (primary) touch("cancelled", x, y, controls.activeMods)
        primary = false
        secondary = false
        tapDragging = false
        fingers = 0
        lastTap = Long.MIN_VALUE
    }
}
