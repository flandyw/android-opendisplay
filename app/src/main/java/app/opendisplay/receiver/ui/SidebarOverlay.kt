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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
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
private val ActiveColor = Color(0xFF0A84FF)
private val LockedColor = Color(0xFF30B0C7)
private val IdleColor = Color.Transparent
private val GlyphColor = Color(0xFFFFFFFF)

/** A drag this far toward the screen centre pulls the tab open. */
private const val TAB_DRAG_OPEN_DP = 10

/**
 * Screen width the open sidebar occupies. The picture is scaled down by this
 * much so the panel sits beside it instead of covering it.
 */
val SidebarWidth = 64.dp

/**
 * A full-height Sidecar-style rail: display shortcuts at the top, sticky
 * modifiers in the middle, and undo/keyboard/hide at the bottom. Long-press
 * hide to open the remaining controls. Collapsed, it is a thin edge tab.
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
    onDisconnect: () -> Unit,
) {
    val scroll = rememberScrollState()
    var moreOpen by remember { mutableStateOf(false) }
    val haptic = onHaptic
    LaunchedEffect(ui.expanded) {
        if (!ui.expanded) moreOpen = false
    }

    val right = ui.rightSide
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val edge = if (right) Alignment.CenterEnd else Alignment.CenterStart
    val towardEdge = if (right) 1 else -1

    BoxWithConstraints(Modifier.fillMaxSize().ignorePalms(palm)) {
        // Keep every 48dp touch target reachable on short landscape screens.
        // On tablets the two flexible gaps reproduce the reference grouping.
        val railHeight = maxHeight.coerceAtLeast(560.dp)
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
                    .width(SidebarWidth)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .consumeAllPointers()
                    .verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .width(SidebarWidth)
                        .height(railHeight)
                        .padding(top = 16.dp, bottom = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (keyEnabled) {
                        IconKey(R.drawable.ic_sidebar_square_arrow_up, R.string.sidebar_menu_bar, haptic) {
                            onShortcut(MacKeys.F2, Mods.CTRL)
                        }
                        IconKey(R.drawable.ic_sidebar_square_arrow_down, R.string.sidebar_dock, haptic) {
                            onShortcut(MacKeys.D, Mods.CMD or Mods.OPT)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (keyEnabled) {
                        ModButton(R.drawable.ic_sidebar_command, Mods.CMD, R.string.sidebar_mod_cmd, ui, onTapMod, onLockMod, haptic)
                        ModButton(R.drawable.ic_sidebar_option, Mods.OPT, R.string.sidebar_mod_opt, ui, onTapMod, onLockMod, haptic)
                        ModButton(R.drawable.ic_sidebar_chevron_up, Mods.CTRL, R.string.sidebar_mod_ctrl, ui, onTapMod, onLockMod, haptic)
                        ModButton(R.drawable.ic_sidebar_arrow_big_up, Mods.SHIFT, R.string.sidebar_mod_shift, ui, onTapMod, onLockMod, haptic)
                    }
                    Spacer(Modifier.weight(1f))
                    if (keyEnabled) {
                        IconKey(R.drawable.ic_sidebar_undo_2, R.string.sidebar_undo, haptic) {
                            onShortcut(MacKeys.Z, Mods.CMD)
                        }
                        IconKey(R.drawable.ic_sidebar_keyboard, R.string.sidebar_keyboard, haptic, active = ui.keyboard, toggle = true) {
                            onKeyboard(!ui.keyboard)
                        }
                    }
                    Box {
                        IconKey(
                            if (right) R.drawable.ic_sidebar_panel_right_close else R.drawable.ic_sidebar_panel_left_close,
                            R.string.sidebar_close,
                            haptic,
                            active = moreOpen,
                            onLongPress = { moreOpen = true },
                            longPressLabel = stringResource(R.string.sidebar_more),
                        ) {
                            moreOpen = false
                            onExpanded(false)
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (keyEnabled) {
                                MoreItem(R.string.sidebar_escape) { onShortcut(MacKeys.ESCAPE, 0) }
                                MoreItem(R.string.sidebar_mission_control) {
                                    onShortcut(MacKeys.ARROW_UP, Mods.CTRL)
                                }
                                MoreItem(R.string.sidebar_spotlight) { onShortcut(MacKeys.SPACE, Mods.CMD) }
                            }
                            MoreItem(R.string.sidebar_pen_only, checked = ui.penOnly) { onPenOnly(!ui.penOnly) }
                            if (ui.zoomed) {
                                MoreItem(R.string.sidebar_zoom_reset) { onZoomReset() }
                            }
                            if (modeEnabled) {
                                MoreItem(R.string.sidebar_mirror, checked = ui.mirror) { onMirror(!ui.mirror) }
                            }
                            MoreItem(R.string.sidebar_eraser, checked = ui.eraser) { onEraser(!ui.eraser) }
                            MoreItem(R.string.sidebar_palm, checked = ui.palmReject) { onPalmReject(!ui.palmReject) }
                            MoreItem(R.string.sidebar_hud, checked = ui.hud) { onHud(!ui.hud) }
                            MoreItem(R.string.sidebar_dim, checked = ui.dim) { onDim(!ui.dim) }
                            MoreItem(if (right) R.string.sidebar_move_left else R.string.sidebar_move_right) {
                                moreOpen = false
                                onRightSide(!right)
                            }
                        }
                    }
                    TextButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(48.dp).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.disconnect_session), color = GlyphColor, fontSize = 12.sp)
                    }
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
private fun ModButton(
    @androidx.annotation.DrawableRes icon: Int,
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
        Icon(painterResource(icon), contentDescription = null, tint = GlyphColor, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun IconKey(
    @androidx.annotation.DrawableRes icon: Int,
    @androidx.annotation.StringRes name: Int,
    onHaptic: () -> Unit,
    active: Boolean = false,
    toggle: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String? = null,
    onClick: () -> Unit,
) {
    PanelButton(
        description = stringResource(name),
        state = if (toggle) stringResource(if (active) R.string.sidebar_state_on else R.string.sidebar_state_off) else null,
        color = if (active) ActiveColor else IdleColor,
        onClick = { onHaptic(); onClick() },
        onLongPress = onLongPress?.let { action -> { onHaptic(); action() } },
        longPressLabel = longPressLabel,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = GlyphColor, modifier = Modifier.size(22.dp))
    }
}

/** An unfilled 48dp touch target; armed and locked controls keep their state fill. */
@Composable
private fun PanelButton(
    description: String,
    color: Color,
    state: String? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String? = null,
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
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (state != null) stateDescription = state
                onClick { click(); true }
                if (hasLongPress) onLongClick(label = longPressLabel) { longPress?.invoke(); true }
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

private enum class Direction { LEFT, RIGHT }

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
