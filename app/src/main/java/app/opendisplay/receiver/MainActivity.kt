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
import android.view.View
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.KeyCharacterMap
import app.opendisplay.receiver.input.HardwareKeyForwarder
import app.opendisplay.receiver.ui.DesktopInputView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import app.opendisplay.receiver.ui.SidebarWidth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.core.view.ViewCompat
import app.opendisplay.receiver.input.TextForwarder
import app.opendisplay.receiver.ui.KeyboardCaptureView
import app.opendisplay.receiver.ui.rememberStreamPresence
import kotlinx.coroutines.delay
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
import app.opendisplay.receiver.clip.ImageClip
import app.opendisplay.receiver.compat.DeviceReport
import app.opendisplay.receiver.input.InputControls
import app.opendisplay.receiver.input.MacKeys
import app.opendisplay.receiver.input.PalmGuard
import app.opendisplay.receiver.input.StylusShortcutManager
import app.opendisplay.receiver.input.Mods
import app.opendisplay.receiver.input.TouchMapper
import app.opendisplay.receiver.net.DiscoveryProbe
import app.opendisplay.receiver.net.LaunchAutoConnect
import app.opendisplay.receiver.net.MacHostBrowser
import app.opendisplay.receiver.net.MacAddress
import app.opendisplay.receiver.net.NearbyMac
import app.opendisplay.receiver.net.PairTokens
import app.opendisplay.receiver.net.TokenStorage
import app.opendisplay.receiver.net.withMac
import app.opendisplay.receiver.net.withoutMac
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
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private lateinit var decoder: H264Decoder
    private lateinit var server: ReceiverServer
    private lateinit var nsd: NsdAdvertiser
    private lateinit var streamLocks: StreamLocks
    private lateinit var discoveryProbe: DiscoveryProbe
    private var macHostBrowser: MacHostBrowser? = null
    private lateinit var touchMapper: TouchMapper
    private val uiState = MutableStateFlow(ReceiverUiState())

    /** Macs on the network that accept reverse connections, shown as one-tap cards. */
    private val nearbyMacs = MutableStateFlow<List<NearbyMac>>(emptyList())
    @Volatile private var lastMac: NearbyMac? = null
    private val pairTokens by lazy {
        PairTokens(object : TokenStorage {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        })
    }
    private var connectionMode: ConnectionMode = ConnectionMode.NETWORK
    private var desktopMode: DesktopMode = DesktopMode.EXTEND
    private val returningToMain = MutableStateFlow(false)
    private val autoConnectEnabled by lazy { MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, false)) }
    private val launchAutoConnect by lazy {
        LaunchAutoConnect(lifecycleScope) {
            lastMac?.let { last ->
                val mac = nearbyMacs.value.firstOrNull { it.name == last.name || it.host == last.host } ?: last
                if (!receiverStopped && !returningToMain.value && !uiState.value.connected) {
                    dialMac(last.name, mac.host, mac.port, userInitiated = false)
                }
            }
        }
    }
    private var receiverStopped = false

    /** Bound when the video view is inflated; cursor updates post to it. */
    @Volatile private var cursorView: CursorOverlayView? = null
    @Volatile private var videoView: TextureView? = null
    @Volatile private var videoSurface: Surface? = null
    /** Keeps decode alive while the activity is stopped (home / recents). */
    @Volatile private var holdSurfaceTexture: SurfaceTexture? = null
    @Volatile private var holdSurface: Surface? = null
    @Volatile private var latestViewport: TouchMapper.Viewport = TouchMapper.Viewport()
    private var macCursorX = 0.5
    private var macCursorY = 0.5
    private lateinit var controls: InputControls
    private lateinit var hardwareKeys: HardwareKeyForwarder
    private var inputView: DesktopInputView? = null
    private var deadAccent = 0
    private var localInputActive = false
    private var captureRequest = 0
    /** Shared by the video touch path and the sidebar so both ignore a resting hand. */
    private val palmGuard = PalmGuard()
    private lateinit var stylusShortcuts: StylusShortcutManager
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Invisible text target for the system keyboard (null until the stream view exists). */
    @Volatile private var keyboardView: KeyboardCaptureView? = null
    private val textForwarder = TextForwarder(type = ::typeText, backspace = ::typeBackspace)
    private var imeVisible = false

    private var clipboard: ClipboardManager? = null
    /** Last text we put on / read from the clipboard — stops sync echo loops. */
    private var lastSyncedClip: String? = null
    /** Hash of the last image we put on / read from the clipboard — stops image echo loops. */
    private var lastSyncedImage = 0
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboard?.primaryClip ?: return@OnPrimaryClipChangedListener
        val imageUri = ImageClip.imageUri(contentResolver, clip)
        if (imageUri != null) {
            sendClipboardImage(imageUri)
            return@OnPrimaryClipChangedListener
        }
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)
        // A bare content URI (a file, a link to one) has no text worth sending.
        val text = when {
            item == null -> null
            item.text != null -> item.text.toString()
            item.uri == null -> item.coerceToText(this)?.toString()
            else -> null
        }
        if (!text.isNullOrEmpty() && text != lastSyncedClip) {
            lastSyncedClip = text
            if (::server.isInitialized) server.sendClipboard(text)
        }
    }

    private fun sendClipboardImage(uri: android.net.Uri) {
        if (!::server.isInitialized || !server.hasCap(WireCaps.CLIP_IMAGE)) return
        if (ImageClip.isOurs(this, uri)) return
        // Decoding and re-encoding can take a moment; keep it off the UI thread.
        Thread {
            val png = try {
                ImageClip.encodeForWire(contentResolver, uri)
            } catch (_: Exception) {
                null
            } ?: return@Thread
            val hash = png.contentHashCode()
            if (hash == lastSyncedImage) return@Thread
            lastSyncedImage = hash
            server.sendClipboardImage(png)
        }.start()
    }

    /** An image copied on the Mac: serve it to other apps through the clipboard. */
    private fun receiveClipboardImage(png: ByteArray) {
        val hash = png.contentHashCode()
        lastSyncedImage = hash
        Thread {
            val uri = try {
                ImageClip.store(this, png)
            } catch (_: Exception) {
                return@Thread
            }
            runOnUiThread {
                clipboard?.setPrimaryClip(ClipData.newUri(contentResolver, "Mac image", uri))
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighRefreshRate()

        controls = InputControls(
            rightSide = prefs.getBoolean(KEY_SIDEBAR_RIGHT, false),
            palmReject = prefs.getBoolean(KEY_PALM_REJECT, true),
            controlIsCommand = prefs.getBoolean(KEY_CONTROL_COMMAND, false),
            reverseScroll = prefs.getBoolean(KEY_REVERSE_SCROLL, false),
            pointerSpeed = prefs.getFloat(KEY_POINTER_SPEED, 1f),
        )
        palmGuard.enabled = controls.ui.value.palmReject
        watchKeyboardVisibility()

        val app = application as OpenDisplayApp
        connectionMode = ConnectionMode.load(this)
        desktopMode = DesktopMode.load(this)
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
                desktopMode = desktopMode.name,
            )
        } else {
            uiState.value = uiState.value.copy(
                deviceSummary = deviceInfo.summaryLine(),
                connectionMode = connectionMode.name,
                desktopMode = desktopMode.name,
            )
        }

        lastMac = readLastMac()
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
                if (next.connected) runOnUiThread { launchAutoConnect.cancel() }
                if (next.streaming && ::server.isInitialized) {
                    server.connectedMac?.let { mac ->
                        rememberMac(server.reverseMac ?: nearbyMacs.value.firstOrNull { it.host == mac.host } ?: mac)
                    }
                }
                uiState.value = next.copy(
                    deviceSummary = next.deviceSummary.ifEmpty { deviceInfo.summaryLine() },
                    connectionMode = connectionMode.name,
                    desktopMode = desktopMode.name,
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
                macCursorX = x
                macCursorY = y
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
            onClipboardImage = ::receiveClipboardImage,
            onMode = controls::setMirror,
            pairToken = { pairTokens.tokenFor(server.reverseMac?.name) },
            onPairToken = { token -> pairTokens.save(server.reverseMac?.name, token) },
        )
        hardwareKeys = HardwareKeyForwarder(controls, server::sendKey)
        touchMapper = TouchMapper(this, server, controls, onHaptic = ::haptic, palm = palmGuard,
            onPointerEcho = { x, y ->
                val vp = latestViewport
                cursorView?.setCursorPosition((x - vp.contentX) / vp.contentW, (y - vp.contentY) / vp.contentH, true)
            }) { viewport ->
            latestViewport = viewport
            controls.setZoomed(viewport.scale > 1.01f)
            applyViewport(viewport)
        }
        stylusShortcuts = StylusShortcutManager(this)
        nsd = NsdAdvertiser(this)
        discoveryProbe = DiscoveryProbe(
            context = this,
            installId = app.installId,
            serviceName = { uiState.value.serviceName.ifBlank { Build.MODEL.ifBlank { "OpenDisplay" } } },
            tcpPort = { WireProtocol.DEFAULT_PORT },
        )
        // When the Mac cannot dial us (AP isolation), it advertises reverse host
        // and we dial the Mac instead.
        macHostBrowser = MacHostBrowser(
            this,
            onHost = { host, port, name ->
                runOnUiThread {
                    if (receiverStopped || !::server.isInitialized) return@runOnUiThread
                    nearbyMacs.value = nearbyMacs.value.withMac(NearbyMac(name, host, port))
                }
            },
            onLost = { name -> runOnUiThread { nearbyMacs.value = nearbyMacs.value.withoutMac(name) } },
        )

        val defaultName = Build.MODEL.ifBlank { "OpenDisplay" }
        server.setServiceName(defaultName)
        updatePanelFromDisplay()
        server.start(WireProtocol.DEFAULT_PORT)
        discoveryProbe.start() // UDP multicast signature on :9010
        macHostBrowser?.start()
        applyConnectionMode(connectionMode)
        ReceiverForegroundService.start(this)
        app.updates.check(manual = false)
        launchAutoConnect.schedule(autoConnectEnabled.value)

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
                val nearby by nearbyMacs.collectAsState()
                val hud by server.hud.collectAsState()
                val caps by server.caps.collectAsState()
                // A fresh Mac session knows nothing of our zoom; say it again.
                LaunchedEffect(state.streaming) {
                    if (!state.streaming) releaseDesktopInput()
                }
                LaunchedEffect(caps) {
                    if (caps.isNotEmpty()) touchMapper.resendViewport()
                }
                // Keep the last frame up while the Mac rebuilds the display.
                val returning by returningToMain.collectAsState()
                val autoConnect by autoConnectEnabled.collectAsState()
                val showStream = rememberStreamPresence(state.streaming, sessionEnded = state.sessionEnded) && !returning
                var hint by remember { mutableStateOf(!prefs.getBoolean(KEY_HINT_SEEN, false)) }
                LaunchedEffect(controlsUi.dim, showStream) {
                    applyDim(controlsUi.dim && showStream)
                }
                LaunchedEffect(showStream) {
                    if (!showStream) {
                        releaseDesktopInput()
                        keyboardView?.hideKeyboard()
                        if (controls.ui.value.zoomed) touchMapper.resetViewport()
                        controls.endSession()
                    }
                }
                val finishHint = {
                    hint = false
                    prefs.edit().putBoolean(KEY_HINT_SEEN, true).apply()
                }
                // Opening the sidebar is the hint's whole point.
                LaunchedEffect(controlsUi.expanded) {
                    if (controlsUi.expanded && hint) finishHint()
                }
                ReceiverScreen(
                    state = state,
                    showStream = showStream,
                    sidebarOpen = controlsUi.expanded,
                    sidebarRight = controlsUi.rightSide,
                    showHint = hint && showStream && !controlsUi.expanded,
                    onHintDone = finishHint,
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
                                controls.dispatchShortcut(code, mods, send = server::sendShortcut)
                            },
                            onSystemShortcut = { code, mods ->
                                controls.dispatchShortcut(code, mods, useActiveMods = false, send = server::sendShortcut)
                            },
                            onPenOnly = controls::setPenOnly,
                            onMirror = { mirror ->
                                if (server.sendDisplayMode(mirror)) controls.setMirror(mirror)
                            },
                            onHud = controls::setHud,
                            onDim = controls::setDim,
                            onEraser = controls::setEraser,
                            onPalmReject = { on ->
                                controls.setPalmReject(on)
                                palmGuard.enabled = on
                                prefs.edit().putBoolean(KEY_PALM_REJECT, on).apply()
                            },
                            palm = palmGuard,
                            onKeyboard = ::setKeyboard,
                            pointerEnabled = WireCaps.HOVER in caps,
                            onCapturePointer = ::setPointerCapture,
                            onControlIsCommand = { on ->
                                hardwareKeys.releaseAll()
                                controls.setControlIsCommand(on)
                                prefs.edit().putBoolean(KEY_CONTROL_COMMAND, on).apply()
                            },
                            onReverseScroll = { on ->
                                controls.setReverseScroll(on)
                                prefs.edit().putBoolean(KEY_REVERSE_SCROLL, on).apply()
                            },
                            onPointerSpeed = { speed ->
                                controls.setPointerSpeed(speed)
                                prefs.edit().putFloat(KEY_POINTER_SPEED, speed).apply()
                            },
                            onLocalInput = { local ->
                                localInputActive = local
                                if (local) hardwareKeys.releaseAll()
                            },
                            onZoomReset = touchMapper::resetViewport,
                            onRightSide = { right ->
                                controls.setRightSide(right)
                                prefs.edit().putBoolean(KEY_SIDEBAR_RIGHT, right).apply()
                            },
                            onExpanded = controls::setExpanded,
                            onHaptic = ::haptic,
                            onDisconnect = ::returnToMain,
                        )
                    },
                    onConnectionMode = { mode -> setConnectionMode(mode) },
                    onDesktopMode = { mode -> setDesktopMode(mode) },
                    onConnectMac = ::connectToMac,
                    nearbyMacs = nearby,
                    onConnectNearby = ::connectToNearby,
                    autoConnectEnabled = autoConnect,
                    onAutoConnect = ::setAutoConnect,
                    lastMacAddress = prefs.getString(KEY_MAC_ADDRESS, "").orEmpty(),
                    createKeyboardView = { context ->
                        KeyboardCaptureView(context, textForwarder, ::forwardSoftKey)
                            .also { keyboardView = it }
                    },
                    onBindInput = { view ->
                        inputView = view
                        view?.onCaptureChanged = { captured ->
                            controls.setPointerCaptured(captured)
                            if (captured) touchMapper.seedPointer(macCursorX, macCursorY) else touchMapper.releaseInputs()
                        }
                    },
                    onCapturedPointer = { event, w, h -> touchMapper.onCapturedPointer(event, w, h) },
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
                        if (!returningToMain.value && !receiverStopped) attachHoldSurface()
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
                        // Pencil double tap swaps pen and eraser.
                        stylusShortcuts.start {
                            controls.toggleEraser()
                            haptic()
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        stylusShortcuts.stop()
                        clipboard?.removePrimaryClipChangedListener(clipListener)
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        if (!isChangingConfigurations) {
                            stopReceiver()
                        }
                    }
                    else -> Unit
                }
            },
        )
    }

    /** Dial a Mac that listens for reverse connections, and remember it for next time. */
    private fun connectToMac(address: MacAddress) {
        prefs.edit().putString(KEY_MAC_ADDRESS, address.text).apply()
        dialMac(address.text, address.host, address.port)
    }

    private fun connectToNearby(mac: NearbyMac) = dialMac(mac.name, mac.host, mac.port)

    private fun dialMac(key: String, host: String, port: Int, userInitiated: Boolean = true) {
        if (returningToMain.value || receiverStopped) return
        if (userInitiated) launchAutoConnect.cancel()
        server.startNextSessionIn(mirror = desktopMode == DesktopMode.MIRROR)
        server.connectOutbound(host, port, key, userInitiated)
    }

    /** End the display session and return to the connection page. */
    private fun returnToMain() {
        if (returningToMain.value) return
        releaseDesktopInput()
        returningToMain.value = true
        launchAutoConnect.cancel()
        keyboardView?.hideKeyboard()
        controls.endSession()
        touchMapper.resetViewport()
        lifecycleScope.launch {
            try {
                server.disconnectSession()
            } finally {
                returningToMain.value = false
            }
        }
    }

    private fun setAutoConnect(enabled: Boolean) {
        autoConnectEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        if (!enabled) launchAutoConnect.cancel()
    }

    private fun stopAdvertising() {
        nsd.unregister()
        if (::discoveryProbe.isInitialized) discoveryProbe.stop()
        macHostBrowser?.stop()
    }

    private fun stopReceiver() {
        if (receiverStopped) return
        receiverStopped = true
        launchAutoConnect.cancel()
        stopAdvertising()
        macHostBrowser = null
        server.stop()
        WifiNetworkHolder.stop()
        streamLocks.setActive(false)
        stylusShortcuts.stop()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        ReceiverForegroundService.stop(this)
        releaseHoldSurface()
        videoSurface?.release()
        videoSurface = null
    }

    private fun readLastMac(): NearbyMac? {
        val name = prefs.getString(KEY_LAST_MAC_NAME, null) ?: return null
        val host = prefs.getString(KEY_LAST_MAC_HOST, null) ?: return null
        val port = prefs.getInt(KEY_LAST_MAC_PORT, WireProtocol.MAC_REVERSE_PORT)
        return if (name.isNotBlank() && host.isNotBlank() && port in 1..65535) NearbyMac(name, host, port) else null
    }

    private fun rememberMac(mac: NearbyMac) {
        if (lastMac == mac) return
        lastMac = mac
        prefs.edit()
            .putString(KEY_LAST_MAC_NAME, mac.name)
            .putString(KEY_LAST_MAC_HOST, mac.host)
            .putInt(KEY_LAST_MAC_PORT, mac.port)
            .apply()
    }

    private fun setConnectionMode(mode: ConnectionMode) {
        if (mode == connectionMode) return
        connectionMode = mode
        ConnectionMode.save(this, mode)
        uiState.value = uiState.value.copy(connectionMode = mode.name)
        applyConnectionMode(mode)
    }

    private fun setDesktopMode(mode: DesktopMode) {
        if (mode == desktopMode) return
        desktopMode = mode
        DesktopMode.save(this, mode)
        uiState.value = uiState.value.copy(desktopMode = mode.name)
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
        if (receiverStopped || !::server.isInitialized) return
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
        // This local escape chord works even while Android owns the mouse capture.
        if (::server.isInitialized && uiState.value.streaming && event.isCtrlPressed && event.isAltPressed &&
            event.keyCode == KeyEvent.KEYCODE_DEL
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) releaseDesktopInput()
            return true
        }
        if (::server.isInitialized && uiState.value.streaming && !localInputActive && server.hasCap(WireCaps.KEY) &&
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
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down && event.action != KeyEvent.ACTION_UP) return false
        var chars: String? = null
        val mods = Mods.remapControl(Mods.fromMetaState(event.metaState), controls.ui.value.controlIsCommand)
        if (down && MacKeys.modifierFor(MacKeys.fromAndroid(event.keyCode) ?: -1) == 0) {
            if (mods and (Mods.CMD or Mods.CTRL) != 0) deadAccent = 0 else {
                val cp = event.unicodeChar
                if (cp and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    deadAccent = cp and KeyCharacterMap.COMBINING_ACCENT_MASK
                    return true
                }
                if (cp > 0) {
                    val combined = if (deadAccent != 0) KeyCharacterMap.getDeadChar(deadAccent, cp) else cp
                    chars = if (combined > 0) String(Character.toChars(combined)) else
                        String(Character.toChars(deadAccent)) + String(Character.toChars(cp))
                    deadAccent = 0
                }
            }
        }
        return hardwareKeys.key(event.deviceId, event.keyCode, down, Mods.fromMetaState(event.metaState),
            chars, down && event.repeatCount > 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseDesktopInput()
    }

    private fun releaseDesktopInput() {
        captureRequest++
        inputView?.releasePointerCapture()
        if (::hardwareKeys.isInitialized) hardwareKeys.releaseAll()
        if (::touchMapper.isInitialized) touchMapper.releaseInputs()
        if (::controls.isInitialized) controls.setPointerCaptured(false)
        deadAccent = 0
    }

    private fun setPointerCapture(capture: Boolean) {
        if (!capture) { releaseDesktopInput(); return }
        if (!uiState.value.streaming || !server.hasCap(WireCaps.HOVER)) return
        val request = ++captureRequest
        keyboardView?.hideKeyboard()
        inputView?.let { view ->
            view.requestFocus()
            view.postDelayed({
                if (captureRequest == request && view.hasWindowFocus() && uiState.value.streaming && inputView === view) {
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }, 200)
        }
    }

    /** Enter / Backspace / arrows from the on-screen keyboard. */
    private fun forwardSoftKey(event: KeyEvent): Boolean =
        ::server.isInitialized && uiState.value.streaming && server.hasCap(WireCaps.KEY) && sendMappedKey(event)

    private fun sendMappedKey(event: KeyEvent): Boolean {
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

    /** One character from the on-screen keyboard, as a key press the Mac can type. */
    private fun typeText(text: String) {
        if (!::server.isInitialized || !server.hasCap(WireCaps.KEY)) return
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val glyph = String(Character.toChars(cp))
            i += glyph.length
            val key = if (glyph.length == 1) MacKeys.fromChar(glyph[0]) else null
            val mods = controls.activeMods
            val allMods = if (key?.shift == true) mods or Mods.SHIFT else mods
            // Text outside US ANSI has no key of its own; the Mac types the
            // unicode string carried alongside a placeholder key.
            val chars = if (mods and (Mods.CMD or Mods.CTRL) == 0) glyph else null
            val code = key?.code ?: 0
            server.sendKey(code, true, allMods, chars)
            server.sendKey(code, false, allMods)
            controls.consumeOneShot()
        }
    }

    private fun typeBackspace(count: Int) {
        if (!::server.isInitialized) return
        repeat(count) { server.sendShortcut(MacKeys.DELETE, 0) }
    }

    /** Show or hide the system keyboard; its text goes to the Mac. */
    private fun setKeyboard(on: Boolean) {
        if (on) inputView?.releasePointerCapture()
        controls.setKeyboard(on)
        if (on) keyboardView?.showKeyboard() else keyboardView?.hideKeyboard()
    }

    /** Keep the sidebar button honest when the user dismisses the keyboard themselves. */
    private fun watchKeyboardVisibility() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (visible != imeVisible) {
                imeVisible = visible
                controls.setKeyboard(visible)
                if (!visible) textForwarder.finishComposing()
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
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
private const val PREFS = "opendisplay"
private const val KEY_SIDEBAR_RIGHT = "sidebarRight"
private const val KEY_HINT_SEEN = "streamHintSeen"
private const val KEY_MAC_ADDRESS = "macAddress"
private const val KEY_PALM_REJECT = "palmReject"
private const val KEY_CONTROL_COMMAND = "controlIsCommand"
private const val KEY_REVERSE_SCROLL = "reversePointerScroll"
private const val KEY_POINTER_SPEED = "pointerSpeed"
private const val KEY_AUTO_CONNECT = "autoConnectLastMac"
private const val KEY_LAST_MAC_NAME = "lastMacName"
private const val KEY_LAST_MAC_HOST = "lastMacHost"
private const val KEY_LAST_MAC_PORT = "lastMacPort"
private const val HINT_MS = 6_000L

@Composable
private fun ReceiverScreen(
    state: ReceiverUiState,
    showStream: Boolean,
    showHint: Boolean,
    onHintDone: () -> Unit,
    sidebarOpen: Boolean,
    sidebarRight: Boolean,
    updates: UpdatesUi,
    sidebar: @Composable () -> Unit,
    onConnectionMode: (ConnectionMode) -> Unit,
    onDesktopMode: (DesktopMode) -> Unit,
    onConnectMac: (MacAddress) -> Unit,
    lastMacAddress: String,
    nearbyMacs: List<NearbyMac>,
    onConnectNearby: (NearbyMac) -> Unit,
    autoConnectEnabled: Boolean,
    onAutoConnect: (Boolean) -> Unit,
    createKeyboardView: (Context) -> View,
    onBindViews: (TextureView?, CursorOverlayView?) -> Unit,
    onBindInput: (DesktopInputView?) -> Unit,
    onCapturedPointer: (MotionEvent, Int, Int) -> Boolean,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouch: (android.view.MotionEvent, Int, Int) -> Boolean,
    onHover: (android.view.MotionEvent, Int, Int) -> Boolean,
    onPanelMetrics: (widthPx: Int, heightPx: Int, scale: Double) -> Unit,
) {
    val activity = LocalContext.current as? MainActivity
    val streaming by rememberUpdatedState(state.streaming && showStream)
    DisposableEffect(showStream) {
        activity?.setImmersiveMode(showStream)
        onDispose { }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // An open sidebar takes its own strip of the screen: the whole picture
        // scales down to the rest, toward the edge opposite the sidebar. This is
        // only a visual transform; the view keeps its size, so the Mac is not
        // told the panel changed and does not rebuild the display.
        val fit by animateFloatAsState(
            targetValue = if (sidebarOpen && showStream) (maxWidth - SidebarWidth) / maxWidth else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "sidebar fit",
        )
        // Keep the surface alive while idle so reconnect does not drop the
        // first keyframe. Alpha 0 + solid IdleOverlay hide any leftover frame
        // when the Mac stops sharing.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = fit
                    scaleY = fit
                    transformOrigin = TransformOrigin(if (sidebarRight) 0f else 1f, 0.5f)
                }
                .then(
                    if (showStream) Modifier else Modifier.alpha(0f),
                ),
            factory = { context ->
                val match = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                DesktopInputView(context).apply {
                    layoutParams = match
                    onBindInput(this)
                    showRemotePointer(streaming)
                    setOnCapturedPointerListener { v, event ->
                        if (streaming) onCapturedPointer(event, v.width, v.height) else true
                    }
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
                    // 1px text target: the IME needs a focusable editor to type into.
                    addView(createKeyboardView(context).apply { layoutParams = FrameLayout.LayoutParams(1, 1) })
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
            update = { it.showRemotePointer(streaming) },
            onRelease = {
                it.releasePointerCapture()
                onBindInput(null)
                onBindViews(null, null)
            },
        )

        if (showStream) {
            sidebar()
            StreamNotices(reconnecting = !state.streaming, showHint = showHint, onHintDone = onHintDone)
        } else {
            ConnectionScreen(
                state, onConnectionMode, onDesktopMode, updates, onConnectMac, lastMacAddress,
                nearbyMacs, onConnectNearby,
                autoConnectEnabled = autoConnectEnabled,
                onAutoConnect = onAutoConnect,
            )
        }
    }
}

/** Transient pills over the picture: reconnect status and the one-time sidebar hint. */
@Composable
private fun StreamNotices(reconnecting: Boolean, showHint: Boolean, onHintDone: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = reconnecting,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE61C1C1E))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Text(stringResource(R.string.stream_reconnecting), color = Color.White, fontSize = 14.sp)
            }
        }
        AnimatedVisibility(
            visible = showHint && !reconnecting,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            LaunchedEffect(Unit) {
                delay(HINT_MS)
                onHintDone()
            }
            Text(
                stringResource(R.string.stream_hint),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE61C1C1E))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
