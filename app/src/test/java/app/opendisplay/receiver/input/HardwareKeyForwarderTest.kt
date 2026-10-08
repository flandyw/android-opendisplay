package app.opendisplay.receiver.input

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class HardwareKeyForwarderTest {
    private data class Sent(val code: Int, val down: Boolean, val mods: Int, val chars: String?, val repeat: Boolean)
    private val controls = InputControls()
    private val sent = mutableListOf<Sent>()
    private val keys = HardwareKeyForwarder(controls) { code, down, mods, chars, repeat ->
        sent.add(Sent(code, down, mods, chars, repeat)); true
    }
    private fun key(code: Int, down: Boolean, mods: Int = 0, chars: String? = null, repeat: Boolean = false) =
        keys.key(1, code, down, mods, chars, repeat)

    @Test fun commandTabKeepsCommandHeldUntilRelease() {
        key(KeyEvent.KEYCODE_META_LEFT, true, Mods.CMD)
        key(KeyEvent.KEYCODE_TAB, true, Mods.CMD)
        key(KeyEvent.KEYCODE_TAB, false, Mods.CMD)
        assertEquals(Mods.CMD, controls.hardwareMods)
        assertEquals(listOf(55, 48, 48), sent.map { it.code })
        assertTrue(sent.all { it.mods == Mods.CMD })
        key(KeyEvent.KEYCODE_META_LEFT, false)
        assertEquals(Sent(55, false, 0, null, false), sent.last())
        assertEquals(0, controls.hardwareMods)
    }

    @Test fun remappedControlAppliesToCodeAndFlagsAndSuppressesTyping() {
        controls.setControlIsCommand(true)
        key(KeyEvent.KEYCODE_CTRL_LEFT, true, Mods.CTRL)
        key(KeyEvent.KEYCODE_C, true, Mods.CTRL, "c")
        assertEquals(Sent(55, true, Mods.CMD, null, false), sent.first())
        assertEquals(Sent(8, true, Mods.CMD, null, false), sent.last())
    }

    @Test fun releaseKeepsTheOriginalMappingAfterPreferenceChanges() {
        controls.setControlIsCommand(true)
        key(KeyEvent.KEYCODE_CTRL_LEFT, true, Mods.CTRL)
        controls.setControlIsCommand(false)
        key(KeyEvent.KEYCODE_CTRL_LEFT, false)
        assertEquals(55, sent.last().code)
        assertEquals(0, sent.last().mods)
    }

    @Test fun overlappingKeysDoNotRearmAReleasedModifier() {
        key(KeyEvent.KEYCODE_META_LEFT, true, Mods.CMD)
        key(KeyEvent.KEYCODE_C, true, Mods.CMD)
        key(KeyEvent.KEYCODE_META_LEFT, false)
        key(KeyEvent.KEYCODE_C, false)
        assertEquals(0, sent.last().mods)
    }

    @Test fun releasingOneShiftLeavesTheOtherHeld() {
        key(KeyEvent.KEYCODE_SHIFT_LEFT, true, Mods.SHIFT)
        key(KeyEvent.KEYCODE_SHIFT_RIGHT, true, Mods.SHIFT)
        key(KeyEvent.KEYCODE_SHIFT_LEFT, false, Mods.SHIFT)
        assertEquals(Mods.SHIFT, sent.last().mods)
        assertEquals(Mods.SHIFT, controls.hardwareMods)
    }

    @Test fun modifierPressDoesNotConsumeSidebarOneShot() {
        controls.tapMod(Mods.CMD)
        key(KeyEvent.KEYCODE_SHIFT_LEFT, true, Mods.SHIFT)
        key(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        assertEquals(Mods.CMD, controls.activeMods)
        key(KeyEvent.KEYCODE_C, true, chars = "c")
        key(KeyEvent.KEYCODE_C, false)
        assertNull(sent[sent.lastIndex - 1].chars)
        assertEquals(0, controls.activeMods)
    }

    @Test fun layoutTextAndAutoRepeatArePreserved() {
        key(KeyEvent.KEYCODE_E, true, chars = "é")
        key(KeyEvent.KEYCODE_E, true, chars = "é", repeat = true)
        assertEquals("é", sent.last().chars)
        assertTrue(sent.last().repeat)
    }

    @Test fun focusLossReleasesKeysBeforeModifiersAndOrphanUpsAreSwallowed() {
        key(KeyEvent.KEYCODE_META_LEFT, true, Mods.CMD)
        key(KeyEvent.KEYCODE_TAB, true, Mods.CMD)
        keys.releaseAll()
        assertEquals(listOf(48, 55), sent.takeLast(2).map { it.code })
        assertTrue(sent.takeLast(2).all { !it.down && it.mods == 0 })
        val count = sent.size
        assertTrue(key(KeyEvent.KEYCODE_TAB, false))
        assertEquals(count, sent.size)
        assertEquals(0, controls.hardwareMods)
        assertFalse(key(KeyEvent.KEYCODE_VOLUME_UP, true))
    }

    @Test fun failedTransmissionDoesNotConsumeAModifierOrOwnTheKey() {
        controls.tapMod(Mods.CMD)
        val failing = HardwareKeyForwarder(controls) { _, _, _, _, _ -> false }
        assertFalse(failing.key(1, KeyEvent.KEYCODE_C, true, 0, "c", false))
        assertEquals(Mods.CMD, controls.activeMods)
    }
}
