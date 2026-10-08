package app.opendisplay.receiver.input

/** Owns each forwarded press, so remapping changes and focus loss cannot strand keys. */
class HardwareKeyForwarder(
    private val controls: InputControls,
    private val send: (code: Int, down: Boolean, mods: Int, chars: String?, repeat: Boolean) -> Boolean,
) {
    private data class Press(val code: Int, val mods: Int)
    private val held = linkedMapOf<Pair<Int, Int>, Press>()

    fun key(deviceId: Int, androidCode: Int, down: Boolean, metaMods: Int, chars: String?, repeat: Boolean): Boolean {
        val identity = deviceId to androidCode
        val press = held[identity]
        // Orphan releases after focus loss belong to us, but must not reach a new session.
        val code = press?.code ?: MacKeys.mappedCode(androidCode, controls.ui.value.controlIsCommand) ?: return false
        if (!down && press == null) return true
        val hardware = Mods.remapControl(metaMods, controls.ui.value.controlIsCommand)
        val bit = MacKeys.modifierFor(code)
        val otherModifiers = held.filterKeys { it != identity }.values.fold(0) { m, p -> m or MacKeys.modifierFor(p.code) }
        val sidebarMods = press?.mods ?: controls.ui.value.let { it.oneShotMods or it.lockedMods }
        val mods = (if (down) hardware or bit else hardware and bit.inv()) or otherModifiers or sidebarMods
        val typing = chars.takeIf { bit == 0 && mods and (Mods.CMD or Mods.CTRL) == 0 }
        if (!send(code, down, mods, if (down) typing else null, repeat)) return false
        if (down) held[identity] = Press(code, sidebarMods) else {
            held.remove(identity)
            if (bit == 0) controls.consumeOneShot()
        }
        controls.hardwareMods = held.values.fold(0) { m, p -> m or MacKeys.modifierFor(p.code) }
        return true
    }

    fun releaseAll() {
        // Release ordinary keys first, then modifiers, ending with no flags set.
        held.values.sortedBy { MacKeys.modifierFor(it.code) != 0 }.forEach { send(it.code, false, 0, null, false) }
        held.clear()
        controls.hardwareMods = 0
    }
}
