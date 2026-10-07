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
    /** The on-screen keyboard is up and typing goes to the Mac. */
    val keyboard: Boolean = false,
    /** Viewport is pinch-zoomed, so the sidebar offers a one-tap reset. */
    val zoomed: Boolean = false,
    /** Sidebar docks to the right edge instead of the left. */
    val rightSide: Boolean = false,
)

/**
 * State behind the on-screen sidebar, shared with [TouchMapper] and the
 * hardware-key path. Modifiers tapped on the sidebar are one-shot (cleared
 * after the next click or key); a long-press locks them.
 */
class InputControls(rightSide: Boolean = false) {
    private val state = MutableStateFlow(ControlsUi(rightSide = rightSide))
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
    fun setKeyboard(on: Boolean) = state.update { it.copy(keyboard = on) }
    fun setZoomed(on: Boolean) = state.update { it.copy(zoomed = on) }
    fun setRightSide(on: Boolean) = state.update { it.copy(rightSide = on) }

    /** The stream is gone for good: drop everything that only made sense while it ran. */
    fun endSession() = state.update {
        ControlsUi(penOnly = it.penOnly, hud = it.hud, rightSide = it.rightSide)
    }
}
