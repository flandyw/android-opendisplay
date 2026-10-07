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
    fun macKeyMapCoversCommonKeys() {
        assertEquals(0, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_A))
        assertEquals(6, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_Z))
        assertEquals(29, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_0))
        assertEquals(18, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_1))
        assertEquals(51, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_DEL))
        assertEquals(126, MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertNull(MacKeys.fromAndroid(android.view.KeyEvent.KEYCODE_VOLUME_UP))
    }
}
