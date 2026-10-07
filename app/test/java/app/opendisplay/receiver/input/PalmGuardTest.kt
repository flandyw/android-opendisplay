package app.opendisplay.receiver.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGuardTest {
    @Test
    fun fingerWithNoPenAroundIsAFinger() {
        val g = PalmGuard()
        assertFalse(g.isPalm(majorMm = 9f, now = 10_000))
    }

    @Test
    fun fingerShortlyAfterPenActivityIsAPalm() {
        val g = PalmGuard()
        g.penActive(1_000)
        assertTrue(g.isPalm(9f, 1_200))
        assertTrue(g.isPalm(9f, 1_499))
        assertFalse(g.isPalm(9f, 1_500))
    }

    @Test
    fun hoveringKeepsItArmedAndLeavingReleasesAtOnce() {
        val g = PalmGuard()
        g.penActive(1_000)
        g.penActive(1_400) // still hovering
        assertTrue(g.isPalm(9f, 1_800))
        g.penLeft()
        assertFalse(g.isPalm(9f, 1_801))
    }

    @Test
    fun aWideContactIsAHandEvenWithoutAPen() {
        val g = PalmGuard()
        assertTrue(g.isPalm(30f, 5_000))
        assertFalse(g.isPalm(22f, 5_000))
    }

    @Test
    fun disabledGuardLetsEverythingThrough() {
        val g = PalmGuard()
        g.enabled = false
        g.penActive(1_000)
        assertFalse(g.isPalm(40f, 1_100))
    }

    @Test
    fun aClockThatRunsBackwardsDoesNotRejectForever() {
        val g = PalmGuard()
        g.penActive(5_000)
        assertFalse(g.isPalm(9f, 1_000))
    }

    @Test
    fun rejectedTouchStaysRejectedUntilItLifts() {
        val g = PalmGuard()
        val r = RejectedTouches(g)
        g.penActive(1_000)
        assertTrue(r.shouldConsume(7, isTouch = true, isPen = false, pressed = true, now = 1_100))
        g.penLeft()
        // Pen gone, but this palm has not lifted yet.
        assertTrue(r.shouldConsume(7, true, false, true, 1_900))
        assertTrue(r.shouldConsume(7, true, false, false, 2_000)) // the lift itself is still consumed
        assertFalse(r.shouldConsume(7, true, false, true, 2_100)) // a new contact with that id is fresh
    }

    @Test
    fun penEventsArmTheGuardAndAreNeverConsumed() {
        val g = PalmGuard()
        val r = RejectedTouches(g)
        assertFalse(r.shouldConsume(1, isTouch = false, isPen = true, pressed = true, now = 3_000))
        assertTrue(g.penRecent(3_100))
    }
}
