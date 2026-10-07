package app.opendisplay.receiver.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ControlsUi(
    /** Modifiers armed for the next click / key only. */
    val oneShotMods: Int = 0,
    /** Modifiers held until toggled off. */
    val lockedMods: Int = 0,
    /** Fingers scroll instead of click; only the pen draws (Sidecar's pencil-only mode). */
    val penOnly: Boolean = false,
    val hud: Boolean = false,
    val mirror: Boolean = false,
    val expanded: Boolean = false,
    /** Panel brightness turned right down (battery / OLED) while the stream keeps running. */
    val dim: Boolean = false,
)

/**
 * State behind the on-screen sidebar, shared with [TouchMapper] and the
 * hardware-key path. Modifiers tapped on the sidebar are one-shot (cleared
 * after the next click or key); a long-press locks them.
 */
class InputControls {
    private val state = MutableStateFlow(ControlsUi())
    val ui: StateFlow<ControlsUi> = state

    val activeMods: Int get() = state.value.let { it.oneShotMods or it.lockedMods }
    val penOnly: Boolean get() = state.value.penOnly

    fun tapMod(bit: Int) = state.update {
        when {
            it.lockedMods and bit != 0 -> it.copy(lockedMods = it.lockedMods and bit.inv())
            else -> it.copy(oneShotMods = it.oneShotMods xor bit)
        }
    }

    fun lockMod(bit: Int) = state.update {
        if (it.lockedMods and bit != 0) {
            it.copy(lockedMods = it.lockedMods and bit.inv())
        } else {
            it.copy(lockedMods = it.lockedMods or bit, oneShotMods = it.oneShotMods and bit.inv())
        }
    }

    /** Call after a click or key has been sent with [activeMods]. */
    fun consumeOneShot() = state.update { if (it.oneShotMods == 0) it else it.copy(oneShotMods = 0) }

    fun setPenOnly(on: Boolean) = state.update { it.copy(penOnly = on) }
    fun setHud(on: Boolean) = state.update { it.copy(hud = on) }
    fun setMirror(on: Boolean) = state.update { it.copy(mirror = on) }
    fun setDim(on: Boolean) = state.update { it.copy(dim = on) }
    fun setExpanded(on: Boolean) = state.update { it.copy(expanded = on) }
}
