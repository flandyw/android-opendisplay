package app.opendisplay.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opendisplay.receiver.input.ControlsUi
import app.opendisplay.receiver.input.MacKeys
import app.opendisplay.receiver.input.Mods

private val PanelColor = Color(0xCC1C1C1E)
private val ActiveColor = Color(0xFF0A84FF)
private val LockedColor = Color(0xFF30B0C7)
private val IdleColor = Color(0x33FFFFFF)

/**
 * Sidecar-style sidebar: sticky Cmd/Opt/Ctrl/Shift, Esc, undo/redo, plus
 * pen-only, mirror/extend and HUD toggles. Collapsed to a thin tab by default
 * so it never covers the picture.
 *
 * Modifiers and shortcuts need the Mac to support `key` ([keyEnabled]); the
 * mirror toggle needs `mode` ([modeEnabled]).
 */
@Composable
fun SidebarOverlay(
    ui: ControlsUi,
    hud: String,
    keyEnabled: Boolean,
    modeEnabled: Boolean,
    onTapMod: (Int) -> Unit,
    onLockMod: (Int) -> Unit,
    onShortcut: (code: Int, mods: Int) -> Unit,
    onPenOnly: (Boolean) -> Unit,
    onMirror: (Boolean) -> Unit,
    onHud: (Boolean) -> Unit,
    onExpanded: (Boolean) -> Unit,
    onHaptic: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (ui.hud && hud.isNotEmpty()) {
            Text(
                text = hud,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        if (!ui.expanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(22.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                    .background(Color(0x66000000))
                    .tapOnly { onExpanded(true) },
                contentAlignment = Alignment.Center,
            ) {
                Text("›", color = Color(0xCCFFFFFF), fontSize = 18.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelColor)
                    .consumeAllPointers()
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (keyEnabled) {
                    ModButton("⌘", Mods.CMD, ui, onTapMod, onLockMod, onHaptic)
                    ModButton("⌥", Mods.OPT, ui, onTapMod, onLockMod, onHaptic)
                    ModButton("⌃", Mods.CTRL, ui, onTapMod, onLockMod, onHaptic)
                    ModButton("⇧", Mods.SHIFT, ui, onTapMod, onLockMod, onHaptic)
                    KeyButton("esc", false, 13) { onShortcut(MacKeys.ESCAPE, 0) }
                    KeyButton("↶", false, 20) { onShortcut(MacKeys.Z, Mods.CMD) }
                    KeyButton("↷", false, 20) { onShortcut(MacKeys.Z, Mods.CMD or Mods.SHIFT) }
                }
                KeyButton("✎", ui.penOnly, 20) { onPenOnly(!ui.penOnly) }
                if (modeEnabled) {
                    KeyButton(if (ui.mirror) "⧉" else "▭▭", ui.mirror, 16) { onMirror(!ui.mirror) }
                }
                KeyButton("HUD", ui.hud, 11) { onHud(!ui.hud) }
                KeyButton("‹", false, 20) { onExpanded(false) }
            }
        }
    }
}

@Composable
private fun ModButton(
    label: String,
    bit: Int,
    ui: ControlsUi,
    onTap: (Int) -> Unit,
    onLock: (Int) -> Unit,
    onHaptic: () -> Unit,
) {
    val color = when {
        ui.lockedMods and bit != 0 -> LockedColor
        ui.oneShotMods and bit != 0 -> ActiveColor
        else -> IdleColor
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .pointerInput(bit) {
                detectTapGestures(
                    onTap = { onHaptic(); onTap(bit) },
                    onLongPress = { onHaptic(); onLock(bit) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 20.sp)
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean, textSp: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ActiveColor else IdleColor)
            .tapOnly(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = textSp.sp, fontWeight = FontWeight.Medium)
    }
}

private fun Modifier.tapOnly(onTap: () -> Unit): Modifier =
    pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }

/** Keep touches on the panel's gaps from reaching the video view underneath. */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
