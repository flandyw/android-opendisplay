package app.opendisplay.receiver.input

import org.junit.Assert.*
import org.junit.Test

class DesktopPointerTest {
    private val controls = InputControls()
    private val touches = mutableListOf<String>()
    private val clicks = mutableListOf<String>()
    private val scrolls = mutableListOf<Pair<Double, Double>>()
    private val shortcuts = mutableListOf<Pair<Int, Int>>()
    private val pointer = DesktopPointer(controls,
        touch = { phase, _, _, _ -> touches.add(phase) }, hover = { _, _, _ -> },
        click = { button, _, _, _ -> clicks.add(button); true },
        scroll = { dx, dy -> scrolls.add(dx to dy) },
        shortcut = { code, mods -> shortcuts.add(code to mods); true })

    @Test fun relativeMotionUsesSpeedAndStaysInsideDesktop() {
        controls.setPointerSpeed(2f)
        pointer.move(25.0, -10.0, 100, 100)
        assertEquals(1.0, pointer.x, 0.0001)
        assertEquals(0.3, pointer.y, 0.0001)
        pointer.move(200.0, -200.0, 100, 100)
        assertEquals(1.0, pointer.x, 0.0)
        assertEquals(0.0, pointer.y, 0.0)
    }

    @Test fun physicalDragHasOneDownAndOneUpAndFocusLossCancels() {
        pointer.buttons(true, false)
        pointer.buttons(true, false)
        pointer.move(10.0, 0.0, 100, 100)
        pointer.cancel()
        pointer.cancel()
        assertEquals(listOf("began", "moved", "cancelled"), touches)
    }

    @Test fun secondaryClickNeverProducesAPrimaryClick() {
        pointer.buttons(false, true)
        pointer.buttons(false, true)
        pointer.buttons(false, false)
        assertEquals(listOf("right"), clicks)
        assertTrue(touches.isEmpty())
    }

    @Test fun touchpadContactMovesWithoutHoldingAMouseButtonAndTapClicksOnLift() {
        pointer.beginContact(100)
        pointer.contactMove(1, 2.0, 1.0, 100, 100)
        assertTrue(touches.isEmpty())
        pointer.endContact(200, false)
        assertEquals(listOf("began", "ended"), touches)
    }

    @Test fun twoFingerScrollDoesNotClickWhenOneFingerRemains() {
        pointer.beginContact(100)
        pointer.addContact(2)
        pointer.contactMove(2, 10.0, 20.0, 100, 100)
        val x = pointer.x
        pointer.contactMove(1, 10.0, 20.0, 100, 100)
        pointer.endContact(200, false)
        assertEquals(listOf(10.0 to 20.0), scrolls)
        assertEquals(x, pointer.x, 0.0)
        assertTrue(touches.isEmpty())
        assertTrue(clicks.isEmpty())
    }

    @Test fun twoFingerTapIsRightClickAndCancelledContactDoesNothing() {
        pointer.beginContact(100)
        pointer.addContact(2)
        pointer.endContact(200, false)
        assertEquals(listOf("right"), clicks)
        pointer.beginContact(300)
        pointer.endContact(400, true)
        assertTrue(touches.isEmpty())
    }

    @Test fun tapThenHoldDragsAndPhysicalClickDoesNotCreateAnotherTap() {
        pointer.beginContact(100)
        pointer.endContact(200, false)
        pointer.beginContact(300)
        pointer.contactMove(1, 30.0, 0.0, 100, 100)
        pointer.endContact(700, false)
        assertEquals(listOf("began", "ended", "began", "moved", "ended"), touches)
        touches.clear()
        pointer.beginContact(1000)
        pointer.buttons(true, false)
        pointer.buttons(false, false)
        pointer.endContact(1100, false)
        assertEquals(listOf("began", "ended"), touches)
    }

    @Test fun threeFingerSwipeFiresOnceAndUsesExactSystemModifiers() {
        controls.lockMod(Mods.CMD)
        pointer.beginContact(100)
        pointer.addContact(3)
        pointer.contactMove(3, 0.0, -80.0, 100, 100)
        pointer.contactMove(3, 0.0, -100.0, 100, 100)
        pointer.endContact(300, false)
        assertEquals(listOf(MacKeys.ARROW_UP to Mods.CTRL), shortcuts)
        assertTrue(scrolls.isEmpty())
        assertTrue(touches.isEmpty())
    }

    @Test fun movingOutAndBackDoesNotBecomeATap() {
        pointer.beginContact(100)
        pointer.contactMove(1, 20.0, 0.0, 100, 100)
        pointer.contactMove(1, -20.0, 0.0, 100, 100)
        pointer.endContact(200, false)
        assertTrue(touches.isEmpty())
    }

    @Test fun addingASecondFingerCancelsTapDrag() {
        pointer.beginContact(100)
        pointer.endContact(200, false)
        touches.clear()
        pointer.beginContact(300)
        pointer.addContact(2)
        pointer.contactMove(2, 0.0, 20.0, 100, 100)
        pointer.endContact(400, false)
        assertEquals(listOf("began", "cancelled"), touches)
        assertEquals(listOf(0.0 to 20.0), scrolls)
    }

    @Test fun rawButtonStateDoesNotInterruptTapAndHoldDrag() {
        pointer.beginContact(100)
        pointer.endContact(200, false)
        touches.clear()
        pointer.beginContact(300)
        pointer.physicalButtons(false, false)
        pointer.contactMove(1, 20.0, 0.0, 100, 100)
        pointer.physicalButtons(false, false)
        pointer.endContact(600, false)
        assertEquals(listOf("began", "moved", "ended"), touches)
    }

    @Test fun rawButtonReleaseEndsDragWithoutASeparateButtonReleaseEvent() {
        pointer.beginContact(100)
        pointer.physicalButtons(true, false)
        pointer.contactMove(1, 20.0, 0.0, 100, 100)
        pointer.endContact(600, false)
        pointer.physicalButtons(false, false)
        assertEquals(listOf("began", "moved", "ended"), touches)
    }

    @Test fun reverseScrollAffectsBothAxes() {
        controls.setReverseScroll(true)
        pointer.wheel(2.0, -4.0)
        assertEquals(listOf(-2.0 to 4.0), scrolls)
    }
}
