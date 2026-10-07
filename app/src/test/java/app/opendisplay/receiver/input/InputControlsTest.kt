package app.opendisplay.receiver.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputControlsTest {
    @Test
    fun oneShotModifierClearsAfterUse() {
        val c = InputControls()
        c.tapMod(Mods.CMD)
        assertEquals(Mods.CMD, c.activeMods)
        c.consumeOneShot()
        assertEquals(0, c.activeMods)
    }

    @Test
    fun lockedModifierSurvivesConsumeAndTapUnlocks() {
        val c = InputControls()
        c.lockMod(Mods.SHIFT)
        c.consumeOneShot()
        assertEquals(Mods.SHIFT, c.activeMods)
        c.tapMod(Mods.SHIFT)
        assertEquals(0, c.activeMods)
    }

    @Test
    fun tappingTwiceDisarmsOneShot() {
        val c = InputControls()
        c.tapMod(Mods.OPT)
        c.tapMod(Mods.OPT)
        assertEquals(0, c.activeMods)
    }

    @Test
    fun dimToggles() {
        val c = InputControls()
        c.setDim(true)
        assertEquals(true, c.ui.value.dim)
        c.setDim(false)
        assertEquals(false, c.ui.value.dim)
    }

    @Test
    fun macKeyMapCoversCommonKeys() {
        assertEquals(0, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_A))
        assertEquals(6, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_Z))
        assertEquals(29, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_0))
        assertEquals(18, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_1))
        assertEquals(51, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_DEL))
        assertEquals(126, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertNull(MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun numpadAndKeypadKeysMapToMacCodes() {
        assertEquals(82, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(89, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_NUMPAD_7))
        assertEquals(91, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_NUMPAD_8))
        assertEquals(92, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_NUMPAD_9))
        assertEquals(69, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_NUMPAD_ADD))
    }

    @Test
    fun softKeyboardCharactersResolveToAnsiKeys() {
        assertEquals(MacKeys.CharKey(8, false), MacKeys.fromChar('c'))
        assertEquals(MacKeys.CharKey(8, true), MacKeys.fromChar('C'))
        assertEquals(MacKeys.CharKey(18, true), MacKeys.fromChar('!'))
        assertEquals(MacKeys.CharKey(MacKeys.SPACE, false), MacKeys.fromChar(' '))
        assertEquals(MacKeys.CharKey(42, false), MacKeys.fromChar('\\'))
        assertEquals(MacKeys.CharKey(42, true), MacKeys.fromChar('|'))
        assertNull(MacKeys.fromChar('é'))
    }

    @Test
    fun everyAsciiCharacterHasASingleOwner() {
        // The two layout strings must stay the same length or codes drift.
        for (c in ' '..'~') assertEquals("$c", true, MacKeys.fromChar(c) != null)
    }

    @Test
    fun keyboardAndZoomStateAreTracked() {
        val c = InputControls()
        c.setKeyboard(true)
        c.setZoomed(true)
        assertEquals(true, c.ui.value.keyboard)
        assertEquals(true, c.ui.value.zoomed)
    }

    @Test
    fun streamEndClearsTransientState() {
        val c = InputControls()
        c.setKeyboard(true)
        c.setExpanded(true)
        c.setZoomed(true)
        c.setDim(true)
        c.lockMod(Mods.CMD)
        c.endSession()
        val ui = c.ui.value
        assertEquals(false, ui.keyboard)
        assertEquals(false, ui.expanded)
        assertEquals(false, ui.zoomed)
        assertEquals(false, ui.dim)
        assertEquals(0, c.activeMods)
    }
}
