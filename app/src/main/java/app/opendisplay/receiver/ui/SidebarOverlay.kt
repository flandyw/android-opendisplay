@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.opendisplay.receiver.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opendisplay.receiver.R
import app.opendisplay.receiver.input.ControlsUi
import app.opendisplay.receiver.input.MacKeys
import app.opendisplay.receiver.input.Mods
import app.opendisplay.receiver.input.PalmGuard
import app.opendisplay.receiver.input.RejectedTouches
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType

private val PanelColor = Color(0xE61C1C1E)
private val PanelBorder = Color(0x26FFFFFF)
private val ActiveColor = Color(0xFF0A84FF)
private val LockedColor = Color(0xFF30B0C7)
private val IdleColor = Color(0x33FFFFFF)
private val GlyphColor = Color(0xFFFFFFFF)

/** A drag this far toward the screen centre pulls the tab open. */
private const val TAB_DRAG_OPEN_DP = 10

/**
 * Screen width the open sidebar occupies. The picture is scaled down by this
 * much so the panel sits beside it instead of covering it.
 */
val SidebarWidth = 72.dp

/**
 * Sidecar-style sidebar, kept short: sticky Cmd/Opt/Ctrl/Shift, Esc, Undo, the
 * keyboard and pen-only mode, and a "more" menu for the rest. While open it
 * takes its own strip of the screen (see [SidebarWidth]); collapsed it is a
 * thin tab. It can dock to either edge.
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
    onDim: (Boolean) -> Unit,
    onEraser: (Boolean) -> Unit,
    onPalmReject: (Boolean) -> Unit,
    palm: PalmGuard,
    onKeyboard: (Boolean) -> Unit,
    onZoomReset: () -> Unit,
    onRightSide: (Boolean) -> Unit,
    onExpanded: (Boolean) -> Unit,
    onHaptic: () -> Unit,
) {
    val scroll = rememberScrollState()
    var moreOpen by remember { mutableStateOf(false) }
    val haptic = onHaptic

    val right = ui.rightSide
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val edge = if (right) Alignment.CenterEnd else Alignment.CenterStart
    val towardEdge = if (right) 1 else -1

    BoxWithConstraints(Modifier.fillMaxSize().ignorePalms(palm)) {
        val panelMaxHeight = maxHeight - 24.dp
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

        AnimatedVisibility(
            visible = !ui.expanded,
            modifier = Modifier.align(edge),
            enter = fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { it * towardEdge },
            exit = fadeOut(fadeSpec) + slideOutHorizontally(slideSpec) { it * towardEdge },
        ) {
            SidebarTab(
                right = right,
                armed = (ui.oneShotMods or ui.lockedMods) != 0,
                onOpen = { onExpanded(true) },
            )
        }

        AnimatedVisibility(
            visible = ui.expanded,
            modifier = Modifier.align(edge),
            enter = fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { it * towardEdge },
            exit = fadeOut(fadeSpec) + slideOutHorizontally(slideSpec) { it * towardEdge },
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .heightIn(max = panelMaxHeight)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PanelColor)
                    .border(1.dp, PanelBorder, RoundedCornerShape(18.dp))
                    .consumeAllPointers()
                    .verticalScroll(scroll)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (keyEnabled) {
                    ModButton("⌘", Mods.CMD, R.string.sidebar_mod_cmd, ui, onTapMod, onLockMod, haptic)
                    ModButton("⌥", Mods.OPT, R.string.sidebar_mod_opt, ui, onTapMod, onLockMod, haptic)
                    ModButton("⌃", Mods.CTRL, R.string.sidebar_mod_ctrl, ui, onTapMod, onLockMod, haptic)
                    ModButton("⇧", Mods.SHIFT, R.string.sidebar_mod_shift, ui, onTapMod, onLockMod, haptic)
                    Divider()
                    TextKey("esc", R.string.sidebar_escape, haptic) { onShortcut(MacKeys.ESCAPE, 0) }
                    TextKey("⌘Z", R.string.sidebar_undo, haptic) { onShortcut(MacKeys.Z, Mods.CMD) }
                    IconKey(Glyph.KEYBOARD, R.string.sidebar_keyboard, haptic, active = ui.keyboard) {
                        onKeyboard(!ui.keyboard)
                    }
                }
                TextKey("Pen", R.string.sidebar_pen_only, haptic, active = ui.penOnly, textSp = 12, toggle = true) {
                    onPenOnly(!ui.penOnly)
                }
                if (ui.zoomed) {
                    TextKey("1×", R.string.sidebar_zoom_reset, haptic, textSp = 15) { onZoomReset() }
                }
                Divider()
                Box {
                    IconKey(Glyph.MORE, R.string.sidebar_more, haptic, active = moreOpen) { moreOpen = !moreOpen }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        if (keyEnabled) {
                            MoreItem(R.string.sidebar_mission_control) {
                                onShortcut(MacKeys.ARROW_UP, Mods.CTRL)
                            }
                            MoreItem(R.string.sidebar_spotlight) { onShortcut(MacKeys.SPACE, Mods.CMD) }
                            MoreItem(R.string.sidebar_dock) { onShortcut(MacKeys.D, Mods.CMD or Mods.OPT) }
                        }
                        if (modeEnabled) {
                            MoreItem(R.string.sidebar_mirror, checked = ui.mirror) { onMirror(!ui.mirror) }
                        }
                        MoreItem(R.string.sidebar_eraser, checked = ui.eraser) { onEraser(!ui.eraser) }
                        MoreItem(R.string.sidebar_palm, checked = ui.palmReject) { onPalmReject(!ui.palmReject) }
                        MoreItem(R.string.sidebar_hud, checked = ui.hud) { onHud(!ui.hud) }
                        MoreItem(R.string.sidebar_dim, checked = ui.dim) { onDim(!ui.dim) }
                        MoreItem(if (right) R.string.sidebar_move_left else R.string.sidebar_move_right) {
                            onRightSide(!right)
                        }
                    }
                }
                IconKey(if (right) Glyph.CHEVRON_RIGHT else Glyph.CHEVRON_LEFT, R.string.sidebar_close, haptic) {
                    moreOpen = false
                    onExpanded(false)
                }
            }
        }
    }
}

@Composable
private fun MoreItem(@androidx.annotation.StringRes label: Int, checked: Boolean? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = checked?.let { on -> { if (on) Text("✓") } },
        onClick = onClick,
    )
}

/** The collapsed handle: tap or drag toward the centre to open the panel. */
@Composable
private fun SidebarTab(right: Boolean, armed: Boolean, onOpen: () -> Unit) {
    val description = stringResource(R.string.sidebar_open)
    val shape = if (right) {
        RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    } else {
        RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    }
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(88.dp)
            .clip(shape)
            .background(Color(0x80000000))
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) }
            .pointerInput(right) {
                val threshold = TAB_DRAG_OPEN_DP.dp.toPx()
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        travelled += if (right) -delta else delta
                        if (travelled > threshold) {
                            travelled = Float.NEGATIVE_INFINITY
                            onOpen()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            drawChevron(if (right) Direction.LEFT else Direction.RIGHT, Color(0xCCFFFFFF))
        }
        if (armed) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ActiveColor),
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(26.dp).height(1.dp).background(Color(0x22FFFFFF)))
}

@Composable
private fun ModButton(
    label: String,
    bit: Int,
    @androidx.annotation.StringRes name: Int,
    ui: ControlsUi,
    onTap: (Int) -> Unit,
    onLock: (Int) -> Unit,
    onHaptic: () -> Unit,
) {
    val locked = ui.lockedMods and bit != 0
    val armed = ui.oneShotMods and bit != 0
    val state = when {
        locked -> stringResource(R.string.sidebar_mod_locked)
        armed -> stringResource(R.string.sidebar_mod_next_click)
        else -> stringResource(R.string.sidebar_state_off)
    }
    PanelButton(
        description = stringResource(name),
        state = state,
        color = when {
            locked -> LockedColor
            armed -> ActiveColor
            else -> IdleColor
        },
        onClick = { onHaptic(); onTap(bit) },
        onLongPress = { onHaptic(); onLock(bit) },
    ) {
        Text(label, color = GlyphColor, fontSize = 20.sp)
    }
}

@Composable
private fun TextKey(
    label: String,
    @androidx.annotation.StringRes name: Int,
    onHaptic: () -> Unit,
    active: Boolean = false,
    textSp: Int = 13,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    PanelButton(
        description = stringResource(name),
        state = if (toggle) stringResource(if (active) R.string.sidebar_state_on else R.string.sidebar_state_off) else null,
        color = if (active) ActiveColor else IdleColor,
        onClick = { onHaptic(); onClick() },
    ) {
        Text(label, color = GlyphColor, fontSize = textSp.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IconKey(
    glyph: Glyph,
    @androidx.annotation.StringRes name: Int,
    onHaptic: () -> Unit,
    active: Boolean = false,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    PanelButton(
        description = stringResource(name),
        state = if (toggle) stringResource(if (active) R.string.sidebar_state_on else R.string.sidebar_state_off) else null,
        color = if (active) ActiveColor else IdleColor,
        onClick = { onHaptic(); onClick() },
    ) {
        Canvas(Modifier.size(22.dp)) { drawGlyph(glyph, GlyphColor) }
    }
}

/** A 44dp key that springs in when pressed. */
@Composable
private fun PanelButton(
    description: String,
    color: Color,
    state: String? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    // Callbacks change on every recomposition; restarting the gesture detector
    // mid-press would cancel the tap and leave the key looking pressed.
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    val hasLongPress = onLongPress != null
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "key press",
    )
    val fill by animateColorAsState(color, label = "key fill")
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .pointerInput(hasLongPress) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { click() },
                    onLongPress = if (hasLongPress) ({ longPress?.invoke() }) else null,
                )
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private enum class Glyph { KEYBOARD, MORE, CHEVRON_LEFT, CHEVRON_RIGHT }

private enum class Direction { LEFT, RIGHT }

/** Hand-drawn so they render identically on every device (no font coverage gaps). */
private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    when (glyph) {
        Glyph.KEYBOARD -> {
            drawRoundRect(
                color, Offset(w * 0.04f, h * 0.2f), Size(w * 0.92f, h * 0.6f),
                CornerRadius(3.dp.toPx()), stroke,
            )
            val dot = 1.1.dp.toPx()
            for (row in listOf(0.37f, 0.52f)) for (x in listOf(0.22f, 0.4f, 0.6f, 0.78f)) {
                drawCircle(color, dot, Offset(w * x, h * row))
            }
            drawLine(color, Offset(w * 0.3f, h * 0.67f), Offset(w * 0.7f, h * 0.67f), stroke.width, StrokeCap.Round)
        }
        Glyph.MORE -> {
            for (x in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color, 2.2.dp.toPx(), Offset(w * x, h * 0.5f))
        }
        Glyph.CHEVRON_LEFT -> drawChevron(Direction.LEFT, color)
        Glyph.CHEVRON_RIGHT -> drawChevron(Direction.RIGHT, color)
    }
}

private fun DrawScope.drawChevron(direction: Direction, color: Color) {
    val w = size.width
    val h = size.height
    val tipX = if (direction == Direction.RIGHT) 0.72f else 0.28f
    val tailX = if (direction == Direction.RIGHT) 0.3f else 0.7f
    val width = 2.2.dp.toPx()
    drawLine(color, Offset(w * tailX, h * 0.18f), Offset(w * tipX, h * 0.5f), width, StrokeCap.Round)
    drawLine(color, Offset(w * tipX, h * 0.5f), Offset(w * tailX, h * 0.82f), width, StrokeCap.Round)
}

/**
 * Consume finger contacts that are a resting palm (the stylus is at work)
 * before the sidebar's buttons see them. Pen and mouse always get through.
 */
private fun Modifier.ignorePalms(palm: PalmGuard): Modifier = pointerInput(palm) {
    val touches = RejectedTouches(palm)
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { change ->
                val consume = touches.shouldConsume(
                    id = change.id.value,
                    isTouch = change.type == PointerType.Touch,
                    isPen = change.type == PointerType.Stylus || change.type == PointerType.Eraser,
                    pressed = change.pressed,
                    now = change.uptimeMillis,
                )
                if (consume) change.consume()
            }
        }
    }
}

/** Keep touches on the panel's gaps from reaching the video view underneath. */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
