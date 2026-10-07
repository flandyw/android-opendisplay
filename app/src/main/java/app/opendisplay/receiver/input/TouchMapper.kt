package app.opendisplay.receiver.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.opendisplay.receiver.net.ReceiverServer
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Maps MotionEvents to OpenDisplay touch/scroll JSON (WIRE.md) and local
 * pinch-to-zoom of the video viewport.
 *
 * - At 1×: 1 finger = Mac click/drag; 2 fingers = Mac scroll
 * - Zoomed: 1 finger = pan viewport; pinch = zoom; double-tap = reset
 * - Pinch keeps content under the focus point; pan is never gated on
 *   ScaleGestureDetector (which otherwise blocks two-finger translate).
 *
 * Sidecar-style extras:
 * - 3 fingers: swipe left/right = undo/redo, pinch in/out = copy/paste
 * - 4 fingers: swipe up = Mission Control, down = App Exposé, left/right = Spaces
 * - Two-finger tap = right-click; mouse secondary button = right-click
 * - Pen-only mode: fingers scroll instead of click
 * - Large contacts (palms) are ignored
 * Shortcuts and right-click need the Mac to advertise `key` / `click`
 * (see [app.opendisplay.receiver.protocol.WireCaps]); otherwise they no-op.
 *
 * Zoom is also reported to the Mac (`viewport`) so capture can crop to the
 * visible rect and re-encode it at full stream resolution — local scale alone
 * only magnifies compressed pixels.
 */
class TouchMapper(
    context: Context,
    private val server: ReceiverServer,
    private val controls: InputControls = InputControls(),
    private val onHaptic: () -> Unit = {},
    private val onViewportChanged: (Viewport) -> Unit = {},
) {
    private val density = context.resources.displayMetrics.density
    private val palmMajorPx = PALM_MAJOR_DP * density
    private val swipePx = SWIPE_DP * density
    private val tapSlopPx = TAP_SLOP_DP * density

    data class Viewport(
        val scale: Float = 1f,
        /** Pan in view pixels (translation after scale around view center). */
        val panX: Float = 0f,
        val panY: Float = 0f,
        /**
         * Visible region of the full desktop in normalized [0,1] video space
         * (top-left + size). Full frame when scale == 1.
         */
        val contentX: Float = 0f,
        val contentY: Float = 0f,
        val contentW: Float = 1f,
        val contentH: Float = 1f,
    )

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var viewWidth = 1
    private var viewHeight = 1

    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    /** Two-finger gesture active — suppress residual one-finger Mac clicks. */
    private var multiFinger = false

    /** One-finger pan of the zoomed viewport (not Mac drag). */
    private var panningViewport = false
    private var lastPanX = 0f
    private var lastPanY = 0f

    /** Two-finger midpoint tracking for pan / Mac scroll. */
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var midInitialized = false

    /** True once this gesture sent Mac scroll (avoid phantom click on lift). */
    private var didMacScroll = false

    /** One-finger Mac drag in progress. */
    private var macDragging = false

    /** Contact judged to be a palm: ignore until every pointer lifts. */
    private var palmGesture = false

    /** Pen-only mode: one finger scrolls the Mac instead of clicking. */
    private var fingerScrolling = false
    private var lastScrollX = 0f
    private var lastScrollY = 0f

    /** Mouse right-click was sent as a `click`; swallow the rest of that press. */
    private var swallowMouse = false

    /** 3+ finger shortcut gesture; blocks scroll/zoom until all fingers lift. */
    private var comboActive = false
    private var comboFired = false
    private var comboCount = 0
    private var comboStartX = 0f
    private var comboStartY = 0f
    private var comboStartSpan = 1f

    /** Two-finger tap → right-click. */
    private var twoTapEligible = false
    private var twoTapStartMs = 0L
    private var twoTapStartX = 0f
    private var twoTapStartY = 0f
    private var twoTapScale = 1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastViewportSentMs = 0L
    private var pendingViewportSend: Runnable? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                multiFinger = true
                macDragging = false
                panningViewport = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                // Ignore tiny scale noise so pure pans don't fight the zoom.
                if (abs(factor - 1f) < 0.01f) return true

                val old = scale
                val next = (old * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (next == old) return true

                val focusX = detector.focusX
                val focusY = detector.focusY
                val cx = viewWidth / 2f
                val cy = viewHeight / 2f
                val contentX = (focusX - cx - panX) / old + cx
                val contentY = (focusY - cy - panY) / old + cy
                scale = next
                panX = focusX - cx - (contentX - cx) * scale
                panY = focusY - cy - (contentY - cy) * scale
                clampPan()
                publishViewport()
                // Midpoint may jump during scale — resync.
                midInitialized = false
                return true
            }
        },
    ).also {
        // Span slop defaults are fine; quick scale helps tablets.
        it.isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetViewport()
                return true
            }
        },
    )

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0) videoWidth = width
        if (height > 0) videoHeight = height
    }

    fun resetViewport() {
        scale = 1f
        panX = 0f
        panY = 0f
        publishViewport()
    }

    fun viewport(): Viewport = Viewport(scale, panX, panY)

    /** Tell a freshly connected Mac about the zoom we are still showing. */
    fun resendViewport() {
        if (isZoomed) publishViewport()
    }

    fun onTouch(event: MotionEvent, viewW: Int, viewH: Int): Boolean {
        if (viewW <= 0 || viewH <= 0) return false
        viewWidth = viewW
        viewHeight = viewH

        if (handlePen(event)) return true

        // Palm rejection: ignore fingers while the pen is down.
        if (penDown) return true
        if (rejectPalm(event)) return true
        if (handleMouseButton(event)) return true

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 3 && !comboActive) {
            // Shortcut gestures own the touch: stop zoom/scroll tracking.
            comboActive = true
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            scaleDetector.onTouchEvent(cancel)
            cancel.recycle()
        }
        if (!comboActive) {
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiFinger = false
                didMacScroll = false
                panningViewport = false
                macDragging = false
                midInitialized = false
                fingerScrolling = false
                comboActive = false
                comboFired = false
                twoTapEligible = false

                if (isZoomed) {
                    // One-finger drag pans the local viewport.
                    panningViewport = true
                    lastPanX = event.x
                    lastPanY = event.y
                } else if (controls.penOnly && event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) {
                    fingerScrolling = true
                    lastScrollX = event.x
                    lastScrollY = event.y
                } else {
                    macDragging = true
                    sendMappedTouch(event, "began")
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiFinger = true
                macDragging = false
                panningViewport = false
                fingerScrolling = false
                midInitialized = true
                lastMidX = midpointX(event)
                lastMidY = midpointY(event)
                if (event.pointerCount == 2) {
                    twoTapEligible = true
                    twoTapStartMs = event.eventTime
                    twoTapStartX = lastMidX
                    twoTapStartY = lastMidY
                    twoTapScale = scale
                } else {
                    twoTapEligible = false
                    startCombo(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    event.pointerCount >= 3 -> handleCombo(event)
                    comboActive -> Unit // fingers lifting out of a shortcut gesture
                    event.pointerCount >= 2 -> {
                        if (twoTapEligible &&
                            hypot(midpointX(event) - twoTapStartX, midpointY(event) - twoTapStartY) > tapSlopPx
                        ) {
                            twoTapEligible = false
                        }
                        handleTwoFingerMove(event, viewW, viewH)
                    }
                    fingerScrolling -> {
                        val dx = event.x - lastScrollX
                        val dy = event.y - lastScrollY
                        lastScrollX = event.x
                        lastScrollY = event.y
                        if (abs(dx) >= 0.5f || abs(dy) >= 0.5f) {
                            server.sendScroll((dx / viewW * videoWidth).toDouble(), (dy / viewH * videoHeight).toDouble())
                        }
                    }
                    isZoomed && panningViewport -> {
                        val dx = event.x - lastPanX
                        val dy = event.y - lastPanY
                        lastPanX = event.x
                        lastPanY = event.y
                        if (abs(dx) >= 0.5f || abs(dy) >= 0.5f) {
                            panX += dx
                            panY += dy
                            clampPan()
                            publishViewport()
                        }
                    }
                    macDragging && !multiFinger -> {
                        sendMappedTouch(event, "moved")
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (twoTapEligible && event.pointerCount == 2) tryRightClickTap(event)
                twoTapEligible = false
                // Still multi until all fingers up — avoid accidental click.
                multiFinger = event.pointerCount > 2
                midInitialized = false
                if (event.pointerCount == 2 && isZoomed) {
                    // One finger remains while zoomed → continue pan with it.
                    val idx = if (event.actionIndex == 0) 1 else 0
                    panningViewport = true
                    lastPanX = event.getX(idx)
                    lastPanY = event.getY(idx)
                }
            }

            MotionEvent.ACTION_UP -> {
                when {
                    macDragging && !multiFinger && !didMacScroll -> {
                        sendMappedTouch(event, "ended")
                    }
                    macDragging && multiFinger -> {
                        // Was cancelled by a second finger; no end event needed.
                    }
                }
                multiFinger = false
                panningViewport = false
                macDragging = false
                didMacScroll = false
                midInitialized = false
                fingerScrolling = false
                comboActive = false
                comboFired = false
                twoTapEligible = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (macDragging && !multiFinger && !didMacScroll) {
                    sendMappedTouch(event, "cancelled")
                }
                multiFinger = false
                panningViewport = false
                macDragging = false
                didMacScroll = false
                midInitialized = false
                fingerScrolling = false
                comboActive = false
                comboFired = false
                twoTapEligible = false
            }
        }
        return true
    }

    /** Drop a palm-sized contact (and the whole gesture it belongs to). */
    private fun rejectPalm(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = event.actionIndex
            if (event.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER &&
                event.getTouchMajor(idx) > palmMajorPx
            ) {
                palmGesture = true
                abortFingerGesture(event)
            }
        }
        if (!palmGesture) return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) palmGesture = false
        return true
    }

    /** Cancel an in-flight finger gesture on the Mac and reset local tracking. */
    private fun abortFingerGesture(event: MotionEvent) {
        if (macDragging && !multiFinger) sendMappedTouch(event, "cancelled")
        multiFinger = false
        panningViewport = false
        macDragging = false
        didMacScroll = false
        midInitialized = false
        fingerScrolling = false
        comboActive = false
        comboFired = false
        twoTapEligible = false
    }

    /**
     * Mouse / trackpad secondary button → Mac right-click. Everything else
     * from a mouse flows through the normal one-finger path.
     * @return true if the event was consumed here
     */
    private fun handleMouseButton(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return false
        val action = event.actionMasked
        if (swallowMouse) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowMouse = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN && (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
            val (nx, ny) = screenToNormalized(event.x, event.y)
            if (server.sendClick("right", nx, ny, controls.activeMods)) {
                controls.consumeOneShot()
                swallowMouse = true
                return true
            }
        }
        return false
    }

    /** Pen or mouse hovering above the screen (generic-motion events). */
    fun onHover(event: MotionEvent, viewW: Int, viewH: Int): Boolean {
        if (viewW <= 0 || viewH <= 0) return false
        viewWidth = viewW
        viewHeight = viewH
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                val (nx, ny) = screenToNormalized(event.x, event.y)
                val pen = if (isPen(event, 0)) penState(event, 0, 0f, 0f, 0f) else null
                server.sendHover(nx, ny, pen, controls.activeMods)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                // Mouse wheel / trackpad scroll: ~48px per detent, wheel-up = content down.
                val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * WHEEL_PX
                val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * WHEEL_PX
                if (dx != 0f || dy != 0f) server.sendScroll(dx.toDouble(), dy.toDouble())
                return true
            }
        }
        return false
    }

    private fun startCombo(event: MotionEvent) {
        if (comboFired) return
        comboCount = event.pointerCount
        comboStartX = centroidX(event)
        comboStartY = centroidY(event)
        comboStartSpan = span(event).coerceAtLeast(1f)
    }

    private fun handleCombo(event: MotionEvent) {
        if (comboFired) return
        if (event.pointerCount != comboCount) {
            startCombo(event)
            comboCount = event.pointerCount
            return
        }
        val dx = centroidX(event) - comboStartX
        val dy = centroidY(event) - comboStartY
        val ratio = span(event) / comboStartSpan
        val mods = Mods.CMD
        val sent = if (comboCount >= 4) {
            when {
                abs(dy) >= swipePx && abs(dy) > abs(dx) ->
                    server.sendShortcut(if (dy < 0) MacKeys.ARROW_UP else MacKeys.ARROW_DOWN, Mods.CTRL)
                abs(dx) >= swipePx ->
                    // Natural direction: swiping left moves to the Space on the right.
                    server.sendShortcut(if (dx < 0) MacKeys.ARROW_RIGHT else MacKeys.ARROW_LEFT, Mods.CTRL)
                else -> null
            }
        } else {
            when {
                ratio < PINCH_IN -> server.sendShortcut(MacKeys.C, mods)
                ratio > PINCH_OUT -> server.sendShortcut(MacKeys.V, mods)
                abs(dx) >= swipePx && abs(dx) > abs(dy) ->
                    server.sendShortcut(MacKeys.Z, if (dx < 0) mods else mods or Mods.SHIFT)
                else -> null
            }
        }
        if (sent != null) {
            comboFired = true
            if (sent) onHaptic()
        }
    }

    private fun tryRightClickTap(event: MotionEvent) {
        if (event.eventTime - twoTapStartMs > TWO_TAP_MAX_MS) return
        if (abs(scale - twoTapScale) > 0.01f) return
        val (nx, ny) = screenToNormalized(twoTapStartX, twoTapStartY)
        if (server.sendClick("right", nx, ny, controls.activeMods)) {
            controls.consumeOneShot()
            onHaptic()
        }
    }

    /** Stylus pointer currently down (id), or -1. */
    private var penPointerId = -1
    private val penDown: Boolean get() = penPointerId >= 0

    private fun isPen(event: MotionEvent, index: Int): Boolean {
        val t = event.getToolType(index)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /**
     * Stylus always acts as a precise pointer (even while zoomed — fingers
     * pan/zoom). Sends pressure/tilt/barrel alongside the normal touch phases.
     * Returns true if the event was consumed by the pen path.
     */
    private fun handlePen(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (!penDown) {
            val idx = event.actionIndex
            val starts = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) &&
                isPen(event, idx)
            if (!starts) return false
            // Abort any in-flight finger gesture before the pen takes over.
            abortFingerGesture(event)
            penPointerId = event.getPointerId(idx)
            sendPen(event, idx, "began")
            return true
        }
        val idx = event.findPointerIndex(penPointerId)
        when (action) {
            MotionEvent.ACTION_MOVE -> if (idx >= 0) {
                // Include batched historical samples for smooth strokes.
                for (h in 0 until event.historySize) sendPenHistorical(event, idx, h)
                sendPen(event, idx, "moved")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (idx >= 0 && (action == MotionEvent.ACTION_UP || idx == event.actionIndex)) {
                    sendPen(event, idx, "ended")
                    penPointerId = -1
                }
            MotionEvent.ACTION_CANCEL -> {
                if (idx >= 0) sendPen(event, idx, "cancelled")
                penPointerId = -1
            }
        }
        return true
    }

    private fun penState(event: MotionEvent, idx: Int, pressure: Float, tilt: Float, azimuth: Float) =
        ReceiverServer.PenState(
            pressure = pressure.coerceIn(0f, 1f),
            tilt = tilt,
            azimuth = azimuth,
            barrel = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0,
            eraser = event.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER,
            barrel2 = (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0,
        )

    private fun sendPen(event: MotionEvent, idx: Int, phase: String) {
        val (nx, ny) = screenToNormalized(event.getX(idx), event.getY(idx))
        val pen = penState(
            event, idx,
            event.getPressure(idx),
            event.getAxisValue(MotionEvent.AXIS_TILT, idx),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx),
        )
        server.sendTouch(phase, nx, ny, pen, controls.activeMods)
        if (phase == "ended") controls.consumeOneShot()
    }

    private fun sendPenHistorical(event: MotionEvent, idx: Int, h: Int) {
        val (nx, ny) = screenToNormalized(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h))
        val pen = penState(
            event, idx,
            event.getHistoricalPressure(idx, h),
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
            event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, h),
        )
        server.sendTouch("moved", nx, ny, pen, controls.activeMods)
    }

    private fun handleTwoFingerMove(event: MotionEvent, viewW: Int, viewH: Int) {
        val mx = midpointX(event)
        val my = midpointY(event)
        if (!midInitialized) {
            lastMidX = mx
            lastMidY = my
            midInitialized = true
            return
        }
        val dx = mx - lastMidX
        val dy = my - lastMidY
        lastMidX = mx
        lastMidY = my
        if (abs(dx) < 0.5f && abs(dy) < 0.5f) return

        if (isZoomed) {
            // Always pan with two-finger translate when zoomed (independent of scale detector).
            panX += dx
            panY += dy
            clampPan()
            publishViewport()
        } else {
            // At 1×: two-finger drag = Mac scroll.
            didMacScroll = true
            val dxPx = dx / viewW * videoWidth
            val dyPx = dy / viewH * videoHeight
            if (dxPx != 0f || dyPx != 0f) {
                server.sendScroll(dxPx.toDouble(), dyPx.toDouble())
            }
        }
    }

    private val isZoomed: Boolean
        get() = scale > 1.01f

    private fun sendMappedTouch(event: MotionEvent, phase: String) {
        val (nx, ny) = screenToNormalized(event.x, event.y)
        server.sendTouch(phase, nx, ny, mods = controls.activeMods)
        if (phase == "ended") controls.consumeOneShot()
    }

    /**
     * Inverse of viewport transform: screen → content-normalized [0,1].
     * View applies: pivot=center, scale, then translation (pan).
     */
    fun screenToNormalized(screenX: Float, screenY: Float): Pair<Double, Double> {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val contentX = (screenX - cx - panX) / scale + cx
        val contentY = (screenY - cy - panY) / scale + cy
        val nx = (contentX / viewWidth).toDouble().coerceIn(0.0, 1.0)
        val ny = (contentY / viewHeight).toDouble().coerceIn(0.0, 1.0)
        return nx to ny
    }

    private fun clampPan() {
        if (scale <= 1.01f) {
            scale = 1f
            panX = 0f
            panY = 0f
            return
        }
        // With pivot at center: max translation exposes content edges.
        val maxPanX = viewWidth * (scale - 1f) / 2f
        val maxPanY = viewHeight * (scale - 1f) / 2f
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    /**
     * Visible content rect in normalized video space for the current
     * scale + pan (inverse of the view transform).
     */
    fun visibleContentRect(): FloatArray {
        if (scale <= 1.01f || viewWidth <= 0 || viewHeight <= 0) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        val (x0, y0) = screenToNormalized(0f, 0f)
        val (x1, y1) = screenToNormalized(viewWidth.toFloat(), viewHeight.toFloat())
        val left = minOf(x0, x1).toFloat().coerceIn(0f, 1f)
        val top = minOf(y0, y1).toFloat().coerceIn(0f, 1f)
        val right = maxOf(x0, x1).toFloat().coerceIn(0f, 1f)
        val bottom = maxOf(y0, y1).toFloat().coerceIn(0f, 1f)
        val w = (right - left).coerceAtLeast(0.02f)
        val h = (bottom - top).coerceAtLeast(0.02f)
        return floatArrayOf(left, top, w, h)
    }

    private fun publishViewport() {
        val rect = visibleContentRect()
        onViewportChanged(
            Viewport(
                scale = scale,
                panX = panX,
                panY = panY,
                contentX = rect[0],
                contentY = rect[1],
                contentW = rect[2],
                contentH = rect[3],
            ),
        )
        scheduleViewportSend(rect)
    }

    /** Debounce Mac updates during a continuous pinch/pan (~30 Hz). */
    private fun scheduleViewportSend(rect: FloatArray) {
        pendingViewportSend?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            lastViewportSentMs = SystemClock.uptimeMillis()
            server.sendViewport(
                rect[0].toDouble(),
                rect[1].toDouble(),
                rect[2].toDouble(),
                rect[3].toDouble(),
                scale.toDouble(),
            )
        }
        pendingViewportSend = task
        val elapsed = SystemClock.uptimeMillis() - lastViewportSentMs
        val delay = if (elapsed >= VIEWPORT_MIN_INTERVAL_MS) 0L else VIEWPORT_MIN_INTERVAL_MS - elapsed
        mainHandler.postDelayed(task, delay)
    }

    private fun centroidX(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getX(i)
        return sum / e.pointerCount
    }

    private fun centroidY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    /** Mean distance of the pointers from their centroid (pinch size). */
    private fun span(e: MotionEvent): Float {
        val cx = centroidX(e)
        val cy = centroidY(e)
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return sum / e.pointerCount
    }

    private fun midpointX(e: MotionEvent): Float =
        if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x

    private fun midpointY(e: MotionEvent): Float =
        if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        private const val VIEWPORT_MIN_INTERVAL_MS = 33L
        private const val PALM_MAJOR_DP = 110f
        private const val SWIPE_DP = 72f
        private const val TAP_SLOP_DP = 12f
        private const val TWO_TAP_MAX_MS = 300L
        private const val PINCH_IN = 0.72f
        private const val PINCH_OUT = 1.38f
        private const val WHEEL_PX = 48f
    }
}
