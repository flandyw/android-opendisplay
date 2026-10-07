package app.opendisplay.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.opendisplay.receiver.compat.DeviceReport
import app.opendisplay.receiver.input.InputControls
import app.opendisplay.receiver.input.MacKeys
import app.opendisplay.receiver.input.Mods
import app.opendisplay.receiver.input.TouchMapper
import app.opendisplay.receiver.net.DiscoveryProbe
import app.opendisplay.receiver.net.MacHostBrowser
import app.opendisplay.receiver.net.NsdAdvertiser
import app.opendisplay.receiver.net.PanelInfo
import app.opendisplay.receiver.net.ReceiverServer
import app.opendisplay.receiver.net.ReceiverUiState
import app.opendisplay.receiver.net.StreamLocks
import app.opendisplay.receiver.net.WifiNetworkHolder
import app.opendisplay.receiver.protocol.WireCaps
import app.opendisplay.receiver.protocol.WireProtocol
import app.opendisplay.receiver.ui.ConnectionScreen
import app.opendisplay.receiver.ui.OpenDisplayTheme
import app.opendisplay.receiver.ui.SidebarOverlay
import app.opendisplay.receiver.ui.UpdatesUi
import app.opendisplay.receiver.ui.CursorOverlayView
import app.opendisplay.receiver.video.H264Decoder
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private lateinit var decoder: H264Decoder
    private lateinit var server: ReceiverServer
    private lateinit var nsd: NsdAdvertiser
    private lateinit var streamLocks: StreamLocks
    private lateinit var discoveryProbe: DiscoveryProbe
    private var macHostBrowser: MacHostBrowser? = null
    private lateinit var touchMapper: TouchMapper
    private val uiState = MutableStateFlow(ReceiverUiState())
    private var connectionMode: ConnectionMode = ConnectionMode.NETWORK

    /** Bound when the video view is inflated; cursor updates post to it. */
    @Volatile private var cursorView: CursorOverlayView? = null
    @Volatile private var videoView: TextureView? = null
    @Volatile private var videoSurface: Surface? = null
    /** Keeps decode alive while the activity is stopped (home / recents). */
    @Volatile private var holdSurfaceTexture: SurfaceTexture? = null
    @Volatile private var holdSurface: Surface? = null
    @Volatile private var latestViewport: TouchMapper.Viewport = TouchMapper.Viewport()
    private val controls = InputControls()

    private var clipboard: ClipboardManager? = null
    /** Last text we put on / read from the clipboard — stops sync echo loops. */
    private var lastSyncedClip: String? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty() && text != lastSyncedClip) {
            lastSyncedClip = text
            if (::server.isInitialized) server.sendClipboard(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighRefreshRate()

        val app = application as OpenDisplayApp
        connectionMode = ConnectionMode.load(this)
        // Hold a Wi‑Fi Network for this process early — outbound reverse dial
        // needs it (GrapheneOS often has activeNetwork=null until requested).
        WifiNetworkHolder.start(this)
        val deviceInfo = DeviceReport.collect()
        DeviceReport.logOnce()
        if (!deviceInfo.hasAvcDecoder) {
            uiState.value = uiState.value.copy(
                status = "No H.264 decoder on this device — cannot stream",
                deviceSummary = deviceInfo.summaryLine(),
                connectionMode = connectionMode.name,
            )
        } else {
            uiState.value = uiState.value.copy(
                deviceSummary = deviceInfo.summaryLine(),
                connectionMode = connectionMode.name,
            )
        }

        streamLocks = StreamLocks(this)
        decoder = H264Decoder(
            onNeedKeyframe = {
                if (::server.isInitialized) server.requestKeyframe()
            },
            onVideoSize = { w, h ->
                if (::server.isInitialized) server.onVideoSize(w, h)
                if (::touchMapper.isInitialized) touchMapper.setVideoSize(w, h)
            },
        )
        server = ReceiverServer(
            context = this,
            installId = app.installId,
            onState = { next ->
                streamLocks.setActive(next.connected)
                uiState.value = next.copy(
                    deviceSummary = next.deviceSummary.ifEmpty { deviceInfo.summaryLine() },
                    connectionMode = connectionMode.name,
                )
                // Re-bind the live TextureView after a reconnect so the decoder
                // has a Surface even if a prior session cleared codec state.
                if (next.connected) {
                    videoSurface?.let { surface ->
                        if (surface.isValid) decoder.setSurface(surface)
                    }
                }
            },
            decoder = decoder,
            onCursor = { x, y, visible ->
                val vp = latestViewport
                if (vp.scale <= 1.05f || vp.contentW <= 0.001f || vp.contentH <= 0.001f) {
                    cursorView?.setCursorPosition(x, y, visible)
                } else {
                    val lx = (x - vp.contentX) / vp.contentW
                    val ly = (y - vp.contentY) / vp.contentH
                    val inView = lx in -0.05..1.05 && ly in -0.05..1.05
                    cursorView?.setCursorPosition(
                        lx.coerceIn(0.0, 1.0),
                        ly.coerceIn(0.0, 1.0),
                        visible && inView,
                    )
                }
            },
            onCursorImage = { png, nw, nh, ax, ay ->
                val vp = latestViewport
                if (vp.scale <= 1.05f || vp.contentW <= 0.001f || vp.contentH <= 0.001f) {
                    cursorView?.setCursorSprite(png, nw, nh, ax, ay)
                } else {
                    cursorView?.setCursorSprite(
                        png,
                        nw / vp.contentW.toDouble(),
                        nh / vp.contentH.toDouble(),
                        ax,
                        ay,
                    )
                }
            },
            onCursorReset = {
                cursorView?.clear()
            },
            onClipboard = { text ->
                lastSyncedClip = text
                clipboard?.setPrimaryClip(ClipData.newPlainText("Mac clipboard", text))
            },
        )
        touchMapper = TouchMapper(this, server, controls, onHaptic = ::haptic) { viewport ->
            latestViewport = viewport
            applyViewport(viewport)
        }
        nsd = NsdAdvertiser(this)
        discoveryProbe = DiscoveryProbe(
            context = this,
            installId = app.installId,
            serviceName = { uiState.value.serviceName.ifBlank { Build.MODEL.ifBlank { "OpenDisplay" } } },
            tcpPort = { WireProtocol.DEFAULT_PORT },
        )
        // When the Mac cannot dial us (AP isolation), it advertises reverse host
        // and we dial the Mac instead.
        macHostBrowser = MacHostBrowser(this) { host, port, name ->
            runOnUiThread {
                if (!::server.isInitialized) return@runOnUiThread
                server.connectOutbound(host, port)
            }
        }

        val defaultName = Build.MODEL.ifBlank { "OpenDisplay" }
        server.setServiceName(defaultName)
        updatePanelFromDisplay()
        server.start(WireProtocol.DEFAULT_PORT)
        discoveryProbe.start() // UDP multicast signature on :9010
        macHostBrowser?.start()
        applyConnectionMode(connectionMode)
        ReceiverForegroundService.start(this)
        app.updates.check(manual = false)

        setContent {
            OpenDisplayTheme {
                val state by uiState.collectAsState()
                val updateState by app.updates.state.collectAsState()
                var autoCheck by remember { mutableStateOf(app.updates.autoCheck) }
                val updatesUi = UpdatesUi(
                    state = updateState,
                    autoCheck = autoCheck,
                    onAutoCheck = { autoCheck = it; app.updates.autoCheck = it },
                    onSource = app.updates::setSource,
                    onCheck = { app.updates.check(manual = true) },
                    onDownload = app.updates::download,
                    onInstall = app.updates::install,
                    onDelete = app.updates::discard,
                )
                val controlsUi by controls.ui.collectAsState()
                val hud by server.hud.collectAsState()
                val caps by server.caps.collectAsState()
                LaunchedEffect(controlsUi.dim, state.streaming) {
                    applyDim(controlsUi.dim && state.streaming)
                }
                ReceiverScreen(
                    state = state,
                    updates = updatesUi,
                    sidebar = {
                        SidebarOverlay(
                            ui = controlsUi,
                            hud = hud,
                            keyEnabled = WireCaps.KEY in caps,
                            modeEnabled = WireCaps.MODE in caps,
                            onTapMod = controls::tapMod,
                            onLockMod = controls::lockMod,
                            onShortcut = { code, mods ->
                                server.sendShortcut(code, mods or controls.activeMods)
                                controls.consumeOneShot()
                            },
                            onPenOnly = controls::setPenOnly,
                            onMirror = { mirror ->
                                if (server.sendDisplayMode(mirror)) controls.setMirror(mirror)
                            },
                            onHud = controls::setHud,
                            onDim = controls::setDim,
                            onExpanded = controls::setExpanded,
                            onHaptic = ::haptic,
                        )
                    },
                    onConnectionMode = { mode -> setConnectionMode(mode) },
                    onBindViews = { texture, cursor ->
                        videoView = texture
                        cursorView = cursor
                        applyViewport(touchMapper.viewport())
                    },
                    onSurfaceReady = { surface ->
                        releaseHoldSurface()
                        videoSurface?.release()
                        videoSurface = surface
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // Match the display to the stream rate to avoid judder re-timing.
                            try {
                                surface.setFrameRate(
                                    currentRefreshHz().toFloat(),
                                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                                )
                            } catch (_: Exception) {
                            }
                        }
                        decoder.setSurface(surface)
                    },
                    onSurfaceDestroyed = {
                        // Keep decoding into an off-screen surface so the Mac
                        // session survives home / recents (foreground service).
                        attachHoldSurface()
                        videoSurface?.release()
                        videoSurface = null
                    },
                    onTouch = { event, w, h -> touchMapper.onTouch(event, w, h) },
                    onHover = { event, w, h -> touchMapper.onHover(event, w, h) },
                    onPanelMetrics = { w, h, scale ->
                        server.updatePanel(PanelInfo(w, h, scale, currentRefreshHz()))
                    },
                )
            }
        }

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    // Do NOT announce sleeping on ON_STOP — the foreground
                    // service keeps the TCP session for a real second-monitor.
                    Lifecycle.Event.ON_START -> {
                        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.addPrimaryClipChangedListener(clipListener)
                        // Always listen on :9000 and re-advertise when visible —
                        // USB mode still accepts network dials (adb forward and Wi‑Fi).
                        ensureListeningAndAdvertising()
                        macHostBrowser?.start()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        clipboard?.removePrimaryClipChangedListener(clipListener)
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        if (!isChangingConfigurations) {
                            server.announceClosing()
                            nsd.unregister()
                            if (::discoveryProbe.isInitialized) discoveryProbe.stop()
                            macHostBrowser?.stop()
                            macHostBrowser = null
                            WifiNetworkHolder.stop()
                            server.stop()
                            streamLocks.setActive(false)
                            ReceiverForegroundService.stop(this)
                            releaseHoldSurface()
                            videoSurface?.release()
                            videoSurface = null
                        }
                    }
                    else -> Unit
                }
            },
        )
    }

    private fun setConnectionMode(mode: ConnectionMode) {
        if (mode == connectionMode) return
        connectionMode = mode
        ConnectionMode.save(this, mode)
        uiState.value = uiState.value.copy(connectionMode = mode.name)
        applyConnectionMode(mode)
    }

    /**
     * Mode is a **hint for the user / Mac preference** only.
     * The TCP server always listens on port 9000 for both USB (adb forward)
     * and Wi‑Fi so the Mac can use either path while the app is open.
     */
    private fun applyConnectionMode(mode: ConnectionMode) {
        ensureListeningAndAdvertising()
        // Keep mode in UI state for idle copy; do not tear down the listener.
        uiState.value = uiState.value.copy(connectionMode = mode.name)
    }

    /** Start (or keep) :9000 TCP + UDP probe + mDNS so Network and USB both work. */
    private fun ensureListeningAndAdvertising() {
        if (!::server.isInitialized) return
        // start() is a no-op if already running — never stop for mode switches.
        server.start(WireProtocol.DEFAULT_PORT)
        if (::discoveryProbe.isInitialized) {
            discoveryProbe.start()
        }
        if (::nsd.isInitialized) {
            val name = Build.MODEL.ifBlank { "OpenDisplay" }
            val app = application as OpenDisplayApp
            nsd.register(name, WireProtocol.DEFAULT_PORT, app.installId)
        }
    }

    private fun attachHoldSurface() {
        if (holdSurface != null) {
            decoder.setSurface(holdSurface)
            return
        }
        val st = SurfaceTexture(0)
        st.setDefaultBufferSize(16, 16)
        val surface = Surface(st)
        holdSurfaceTexture = st
        holdSurface = surface
        decoder.setSurface(surface)
    }

    private fun releaseHoldSurface() {
        decoder.setSurface(null)
        try {
            holdSurface?.release()
        } catch (_: Exception) {
        }
        try {
            holdSurfaceTexture?.release()
        } catch (_: Exception) {
        }
        holdSurface = null
        holdSurfaceTexture = null
    }

    private fun applyViewport(viewport: TouchMapper.Viewport) {
        val video = videoView ?: return
        val cursor = cursorView
        // Soft local scale: full response near 1× for snappy pinch; asymptote
        // toward ~1.2× as logical zoom grows, because the Mac ROI stream
        // already supplies the rest of the magnification as real pixels.
        val local = softLocalScale(viewport.scale)
        val panScale = if (viewport.scale > 1.01f) local / viewport.scale else 1f
        for (v in listOfNotNull(video, cursor)) {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = local
            v.scaleY = local
            v.translationX = viewport.panX * panScale
            v.translationY = viewport.panY * panScale
        }
    }

    /**
     * Brief local magnification for pinch feedback. Kept small because the Mac
     * ROI stream already carries most of the zoom as real pixels; large local
     * scale would double-zoom and reintroduce softness.
     */
    private fun softLocalScale(logical: Float): Float {
        if (logical <= 1.01f) return 1f
        // z=1.5 → ~1.12; z=2 → ~1.15; z=4 → ~1.18 (cap 1.2)
        val t = ((logical - 1f) / 3f).coerceIn(0f, 1f)
        return 1f + 0.2f * t
    }

    private fun updatePanelFromDisplay() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val scale = metrics.density.toDouble()
        server.updatePanel(PanelInfo(w, h, scale, currentRefreshHz()))
    }

    @Suppress("DEPRECATION")
    private fun currentRefreshHz(): Double =
        windowManager.defaultDisplay.refreshRate.toDouble().coerceIn(30.0, 240.0)

    /** Ask for the display's fastest mode at the current resolution (e.g. 120 Hz). */
    @Suppress("DEPRECATION")
    private fun preferHighRefreshRate() {
        try {
            val display = windowManager.defaultDisplay
            val cur = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.modeId != cur.modeId) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            }
        } catch (_: Exception) {
        }
    }

    private fun haptic() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /**
     * Hardware-keyboard passthrough: forward typing to the Mac while streaming.
     * System keys (back, volume, power, …) are left to Android.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::server.isInitialized && uiState.value.streaming && server.hasCap(WireCaps.KEY) &&
            forwardKey(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun forwardKey(event: KeyEvent): Boolean {
        // Soft-keyboard / virtual devices stay with Android.
        val device = event.device ?: return false
        if (device.isVirtual || event.source and InputDevice.SOURCE_KEYBOARD == 0) return false
        val code = MacKeys.fromAndroid(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down && event.action != KeyEvent.ACTION_UP) return false
        val mods = Mods.fromMetaState(event.metaState) or controls.activeMods
        // Plain typing carries the produced character so the Mac can honor the
        // tablet's layout; shortcuts rely on the key code + modifiers alone.
        val chars = if (down && mods and (Mods.CMD or Mods.CTRL) == 0) {
            event.unicodeChar.takeIf { it > 0 }?.let { String(Character.toChars(it)) }
        } else {
            null
        }
        val sent = server.sendKey(code, down, mods, chars, repeat = down && event.repeatCount > 0)
        if (sent && !down) controls.consumeOneShot()
        return sent
    }

    /** Drop the panel to minimum brightness (or hand control back to the system). */
    fun applyDim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun setImmersiveMode(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private const val DIM_BRIGHTNESS = 0.02f

@Composable
private fun ReceiverScreen(
    state: ReceiverUiState,
    updates: UpdatesUi,
    sidebar: @Composable () -> Unit,
    onConnectionMode: (ConnectionMode) -> Unit,
    onBindViews: (TextureView?, CursorOverlayView?) -> Unit,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouch: (android.view.MotionEvent, Int, Int) -> Boolean,
    onHover: (android.view.MotionEvent, Int, Int) -> Boolean,
    onPanelMetrics: (widthPx: Int, heightPx: Int, scale: Double) -> Unit,
) {
    val activity = LocalContext.current as? MainActivity
    val streaming by rememberUpdatedState(state.streaming)
    DisposableEffect(state.streaming) {
        activity?.setImmersiveMode(state.streaming)
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Keep the surface alive while idle so reconnect does not drop the
        // first keyframe. Alpha 0 + solid IdleOverlay hide any leftover frame
        // when the Mac stops sharing.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (state.streaming) Modifier else Modifier.alpha(0f),
                ),
            factory = { context ->
                val match = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                FrameLayout(context).apply {
                    layoutParams = match
                    val texture = TextureView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(match)
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                onSurfaceReady(Surface(surface))
                                val density = resources.displayMetrics.density
                                onPanelMetrics(width, height, density.toDouble())
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                val density = resources.displayMetrics.density
                                onPanelMetrics(width, height, density.toDouble())
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                onSurfaceDestroyed()
                                // We release the Surface wrapper ourselves.
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                    val cursor = CursorOverlayView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(match)
                        isClickable = false
                        isFocusable = false
                    }
                    addView(texture)
                    addView(cursor)
                    onBindViews(texture, cursor)

                    setOnTouchListener { v, event ->
                        // Idle UI must never forward taps to the Mac underneath it.
                        // Deliver every sample as it happens instead of batching
                        // to the next vsync — less pointer/pen lag.
                        if (streaming) v.requestUnbufferedDispatch(event)
                        val handled = if (streaming) onTouch(event, v.width, v.height) else true
                        if (handled) v.performClick()
                        handled
                    }
                    // Pen / mouse hover and mouse-wheel scroll arrive as generic motion.
                    setOnGenericMotionListener { v, event ->
                        if (streaming) onHover(event, v.width, v.height) else false
                    }
                }
            },
            onRelease = {
                onBindViews(null, null)
            },
        )

        if (state.streaming) {
            sidebar()
        } else {
            ConnectionScreen(state, onConnectionMode, updates)
        }
    }
}
