package app.opendisplay.receiver.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.opendisplay.receiver.audio.AudioPlayer
import app.opendisplay.receiver.clip.ImageClip
import app.opendisplay.receiver.protocol.WireCaps
import app.opendisplay.receiver.protocol.WireMessage
import app.opendisplay.receiver.protocol.WireProtocol
import app.opendisplay.receiver.video.AnnexBParser
import app.opendisplay.receiver.video.H264Decoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PanelInfo(
    val pixelsWide: Int,
    val pixelsHigh: Int,
    val scale: Double,
    /** Panel refresh rate in Hz; lets the Mac pick a matching capture rate. */
    val refreshHz: Double = 60.0,
)

/** One-second metrics snapshot for the stats loop (thread-safe handoff). */
private data class StatsTick(
    val fps: Int,
    val mbps: Double,
    val stalls: Int,
    val e2e50: Double,
    val e2e95: Double,
    val enc50: Double,
)

enum class ReceiverProblem { UNREACHABLE, LISTENER, UPDATE_REQUIRED }

data class ReceiverUiState(
    val status: String = "Starting…",
    val listening: Boolean = false,
    val connected: Boolean = false,
    val streaming: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val port: Int = WireProtocol.DEFAULT_PORT,
    val serviceName: String = "OpenDisplay",
    /** e.g. "Android 15 (API 35) · Pixel Tablet" for support reports. */
    val deviceSummary: String = "",
    /** Network (default) or USB cable path. */
    val connectionMode: String = "NETWORK",
    val problem: ReceiverProblem? = null,
)

/**
 * Listens on TCP (default 9000), accepts one Mac connection, speaks the
 * OpenDisplay wire protocol (WIRE.md).
 */
class ReceiverServer(
    context: Context,
    private val installId: String,
    private val onState: (ReceiverUiState) -> Unit,
    private val decoder: H264Decoder,
    /** Mac local-cursor echo — invoked on the main thread. */
    private val onCursor: (x: Double, y: Double, visible: Boolean) -> Unit = { _, _, _ -> },
    private val onCursorImage: (pngBase64: String, nw: Double, nh: Double, ax: Double, ay: Double) -> Unit =
        { _, _, _, _, _ -> },
    private val onCursorReset: () -> Unit = {},
    /** Mac clipboard text — invoked on the main thread. */
    private val onClipboard: (String) -> Unit = {},
    /** Mac clipboard image (PNG bytes) — invoked on the main thread. */
    private val onClipboardImage: (ByteArray) -> Unit = {},
    /** The Mac's actual display mode (mirror = true) — invoked on the main thread. */
    private val onMode: (mirror: Boolean) -> Unit = {},
) {
    private val tag = "ReceiverServer"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeMutex = Mutex()
    // Input events go through one ordered queue: separate launch{} coroutines
    // on Dispatchers.IO can reorder (e.g. "ended" overtaking "moved").
    private val outbox = Channel<JSONObject>(Channel.UNLIMITED)
    // Guarded by writeMutex; reuse the buffer for every control/touch message.
    private var outputSocket: Socket? = null
    private var clientOutput: BufferedOutputStream? = null
    private val lastDataMs = AtomicLong(0)
    private val clientSocket = AtomicReference<Socket?>(null)
    private val audioPlayer = AudioPlayer()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var panel = PanelInfo(1920, 1200, 2.0)
    @Volatile private var serviceName = "OpenDisplay"
    @Volatile private var port = WireProtocol.DEFAULT_PORT
    @Volatile private var running = false

    private var acceptJob: Job? = null
    private var sessionJob: Job? = null
    private var watchdogJob: Job? = null
    private var pingJob: Job? = null
    private var statsJob: Job? = null
    private var helloDebounceJob: Job? = null

    private var state = ReceiverUiState()

    /** One-line stream health for the on-screen HUD; updated once a second. */
    private val hudText = MutableStateFlow("")
    val hud: StateFlow<String> = hudText

    /** Optional features the connected Mac advertised in `welcome.caps`. */
    private val capsFlow = MutableStateFlow<Set<String>>(emptySet())
    val caps: StateFlow<Set<String>> = capsFlow

    // Reverse-dial target, kept so a dropped session can be re-dialed.
    @Volatile private var lastOutbound: Pair<String, Int>? = null
    private var reconnectJob: Job? = null
    private val dialing = AtomicBoolean(false)

    // Clock sync (lowest-RTT sample, same as iOS PhoneReceiver).
    private val offsetSamples = ArrayList<Pair<Double, Double>>(16) // rtt → offset
    @Volatile private var clockOffsetMs: Double? = null
    @Volatile private var lastRttMs: Double = 0.0

    // Per-window stream health (reset each stats tick).
    private var windowStartMs = 0L
    private var framesThisWindow = 0
    private var bytesThisWindow = 0L
    private var stallsThisWindow = 0
    private var lastFrameAtMs = 0L
    // Session read thread + stats coroutine both touch these — guard access.
    private val metricsLock = Any()
    private val e2eWindow = ArrayList<Double>(64)
    private val encodeWindow = ArrayList<Double>(64)
    private var statsReportCounter = 0

    fun hasCap(cap: String): Boolean = cap in capsFlow.value

    fun updatePanel(info: PanelInfo) {
        val changed = panel != info
        panel = info
        if (changed && state.connected) {
            // Debounce: orientation / surface size can fire several times in a
            // row; flooding the Mac with hello rebuilds drops the stream.
            helloDebounceJob?.cancel()
            helloDebounceJob = scope.launch {
                delay(250)
                if (state.connected) sendHello()
            }
        }
    }

    fun setServiceName(name: String) {
        serviceName = name.ifBlank { "OpenDisplay" }
        publish(state.copy(serviceName = serviceName))
    }

    fun start(port: Int = WireProtocol.DEFAULT_PORT) {
        if (running) return
        running = true
        scope.launch {
            for (msg in outbox) sendJson(msg)
        }
        this.port = port
        acceptJob = scope.launch { listenLoop() }
    }

    /**
     * Dial the Mac (reverse path). Used when the AP blocks Mac→tablet TCP but
     * the tablet can still open a connection to the Mac (Bonjour-discovered).
     * Same wire protocol as an accepted inbound session.
     *
     * Must bind the socket to the **Wi‑Fi Network** — with USB/adb attached,
     * Android often returns EACCES for unbound LAN dials (`from /::`).
     */
    fun connectOutbound(host: String, port: Int) {
        if (!running) return
        // If already connected only via adb loopback (127.0.0.1), allow reverse
        // to take over so Wi‑Fi path can be tested while the cable is plugged
        // for debugging. Real Wi‑Fi peers keep their session.
        val existing = clientSocket.get()
        val existingPeer = existing?.inetAddress?.hostAddress
        if (state.connected && existingPeer != null &&
            existingPeer != "127.0.0.1" && existingPeer != "::1"
        ) {
            Log.i(tag, "connectOutbound skipped — already connected to $existingPeer")
            return
        }
        if (state.connected && (existingPeer == "127.0.0.1" || existingPeer == "::1")) {
            Log.i(tag, "connectOutbound replacing adb/loopback session with reverse $host:$port")
        }
        if (!dialing.compareAndSet(false, true)) return
        scope.launch {
            try {
                dial(host, port)
            } finally {
                dialing.set(false)
            }
        }
    }

    private suspend fun dial(host: String, port: Int) {
        // Prefer loopback first (adb reverse); then LAN IP for pure Wi‑Fi.
        val candidates = linkedSetOf<String>()
        if (port == WireProtocol.MAC_REVERSE_PORT) {
            candidates.add("127.0.0.1")
        }
        candidates.add(host)
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                Log.i(tag, "dialing Mac $candidate:$port (reverse)")
                publish(state.copy(status = "Connecting to Mac $candidate:$port…", connected = false, problem = null))
                val socket = openWifiBoundSocket(candidate, port)
                closeClient("outbound-replace")
                clientSocket.set(socket)
                lastOutbound = host to port
                sessionJob?.cancel()
                sessionJob = scope.launch { runSession(socket, outbound = true) }
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "connectOutbound $candidate:$port failed: ${e.message}")
            }
        }
        publish(
            state.copy(
                status = "Mac unreachable — ${lastError?.message ?: "no route"}",
                problem = ReceiverProblem.UNREACHABLE,
                connected = false,
                streaming = false,
            ),
        )
    }

    /**
     * Re-dial the Mac after an outbound session drops (Mac restarted, Wi‑Fi
     * blip, watchdog). Backs off 1s → 8s; gives up after a minute or so and
     * leaves the usual discovery path to pick the Mac up again.
     */
    private fun scheduleReconnect() {
        val target = lastOutbound ?: return
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var wait = 1_000L
            repeat(RECONNECT_ATTEMPTS) {
                delay(wait)
                if (!running || state.connected || clientSocket.get() != null) return@launch
                connectOutbound(target.first, target.second)
                wait = (wait * 2).coerceAtMost(8_000L)
            }
        }
    }

    /**
     * Loopback = adb reverse (no Wi‑Fi bind). LAN uses [WifiNetworkHolder].
     */
    private fun openWifiBoundSocket(host: String, port: Int): Socket {
        val isLoopback = host == "127.0.0.1" || host == "::1" || host.equals("localhost", true)
        if (isLoopback) {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), 2_000)
            Log.i(tag, "outbound loopback OK local=${socket.localSocketAddress}")
            return socket
        }

        val target = preferIPv4(host)
            ?: throw java.net.UnknownHostException("no address for $host")
        val network = WifiNetworkHolder.network(timeoutMs = 5_000)
            ?: throw java.io.IOException("no Wi‑Fi Network for app — disable VPN lockdown / allow network")

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        Log.i(tag, "using network=$network active=${cm?.activeNetwork}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
            try {
                cm.bindProcessToNetwork(network)
            } catch (e: Exception) {
                Log.w(tag, "bindProcessToNetwork: ${e.message}")
            }
        }

        val socket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            network.socketFactory.createSocket(target, port)
        } else {
            Socket(target, port)
        }
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 0
        Log.i(
            tag,
            "outbound OK local=${socket.localSocketAddress} remote=${socket.remoteSocketAddress}",
        )
        return socket
    }

    private fun preferIPv4(host: String): InetAddress? {
        return try {
            val all = InetAddress.getAllByName(host)
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        sessionJob?.cancel()
        watchdogJob?.cancel()
        pingJob?.cancel()
        statsJob?.cancel()
        try {
            clientSocket.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        decoder.release()
        audioPlayer.release()
        scope.cancel()
        publish(
            state.copy(
                status = "Stopped",
                listening = false,
                connected = false,
                streaming = false,
            ),
        )
    }

    /**
     * [pen] is optional stylus metadata; older Mac builds ignore the extra
     * keys and treat the event as a plain touch.
     */
    fun sendTouch(phase: String, x: Double, y: Double, pen: PenState? = null, mods: Int = 0) {
        val msg = JSONObject()
            .put("type", WireMessage.TOUCH)
            .put("phase", phase)
            .put("x", x)
            .put("y", y)
        if (mods != 0) msg.put("mods", mods)
        if (pen != null) {
            msg.put("tool", if (pen.eraser) "eraser" else "stylus")
                .put("pressure", pen.pressure.toDouble())
                .put("tilt", pen.tilt.toDouble())
                .put("azimuth", pen.azimuth.toDouble())
                .put("barrel", pen.barrel)
            if (pen.barrel2) msg.put("barrel2", true)
        }
        outbox.trySend(msg)
    }

    /** Pen / mouse hover before contact. No-op unless the Mac advertised [WireCaps.HOVER]. */
    fun sendHover(x: Double, y: Double, pen: PenState? = null, mods: Int = 0) {
        if (!hasCap(WireCaps.HOVER)) return
        sendTouch("hover", x, y, pen, mods)
    }

    /** Right-click etc. @return false if the Mac cannot take it (caller may fall back). */
    fun sendClick(button: String, x: Double, y: Double, mods: Int = 0): Boolean {
        if (!hasCap(WireCaps.CLICK)) return false
        val msg = JSONObject()
            .put("type", WireMessage.CLICK)
            .put("button", button)
            .put("x", x)
            .put("y", y)
        if (mods != 0) msg.put("mods", mods)
        return outbox.trySend(msg).isSuccess
    }

    /** One keyboard event; [code] is a macOS virtual key code. */
    fun sendKey(code: Int, down: Boolean, mods: Int, chars: String? = null, repeat: Boolean = false): Boolean {
        if (!hasCap(WireCaps.KEY)) return false
        val msg = JSONObject()
            .put("type", WireMessage.KEY)
            .put("code", code)
            .put("down", down)
            .put("mods", mods)
        if (!chars.isNullOrEmpty()) msg.put("chars", chars)
        if (repeat) msg.put("repeat", true)
        return outbox.trySend(msg).isSuccess
    }

    /** Press and release [code] with [mods], e.g. Cmd+Z. */
    fun sendShortcut(code: Int, mods: Int): Boolean {
        if (!sendKey(code, true, mods)) return false
        return sendKey(code, false, mods)
    }

    fun sendClipboard(text: String) {
        if (!hasCap(WireCaps.CLIP) || text.isEmpty() || text.length > MAX_CLIP_CHARS) return
        outbox.trySend(JSONObject().put("type", WireMessage.CLIP).put("text", text))
    }

    /** Copy an image to the Mac. [png] must already fit [ImageClip.MAX_PNG_BYTES]. */
    fun sendClipboardImage(png: ByteArray) {
        if (!hasCap(WireCaps.CLIP_IMAGE) || png.isEmpty() || png.size > ImageClip.MAX_PNG_BYTES) return
        outbox.trySend(
            JSONObject()
                .put("type", WireMessage.CLIP_IMAGE)
                .put("png", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)),
        )
    }

    /** Ask the Mac to mirror (true) or extend (false) its desktop. */
    fun sendDisplayMode(mirror: Boolean): Boolean {
        if (!hasCap(WireCaps.MODE)) return false
        return outbox.trySend(
            JSONObject().put("type", WireMessage.MODE).put("mode", if (mirror) "mirror" else "extend"),
        ).isSuccess
    }

    /** Stylus sample: pressure 0..1, tilt/azimuth radians, barrel = side button held. */
    data class PenState(
        val pressure: Float,
        val tilt: Float,
        val azimuth: Float,
        val barrel: Boolean,
        val eraser: Boolean,
        /** Secondary stylus button (also what a pencil squeeze / double-tap maps to). */
        val barrel2: Boolean = false,
    )

    fun sendScroll(dx: Double, dy: Double) {
        outbox.trySend(
            JSONObject()
                .put("type", WireMessage.SCROLL)
                .put("dx", dx)
                .put("dy", dy),
        )
    }

    fun announceSleeping() {
        scope.launch {
            sendJson(JSONObject().put("type", WireMessage.SLEEPING))
            closeClient("sleeping")
        }
    }

    fun announceClosing() {
        scope.launch {
            sendJson(JSONObject().put("type", WireMessage.CLOSING))
            closeClient("closing")
        }
    }

    fun requestKeyframe() {
        outbox.trySend(JSONObject().put("type", WireMessage.KF))
    }

    /**
     * Tell the Mac which fraction of the desktop is on screen (pinch-zoom).
     * Mac crops ScreenCaptureKit to that rect and encodes it at full stream
     * resolution so zoom stays sharp instead of magnifying compressed pixels.
     *
     * @param x,y top-left of visible region in normalized video space [0,1]
     * @param w,h size of visible region in normalized video space
     * @param z   pinch scale (≥ 1); used for bitrate boost
     */
    fun sendViewport(x: Double, y: Double, w: Double, h: Double, z: Double) {
        outbox.trySend(
            JSONObject()
                .put("type", WireMessage.VIEWPORT)
                .put("x", x)
                .put("y", y)
                .put("w", w)
                .put("h", h)
                .put("z", z),
        )
    }

    private suspend fun listenLoop() {
        while (scope.isActive && running) {
            try {
                // reuseAddress must be set BEFORE bind; ServerSocket(port) binds
                // immediately and the flag would be ignored — causing flaky
                // "address already in use" after a quick restart.
                val server = ServerSocket()
                server.reuseAddress = true
                // Explicit wildcard so Wi‑Fi clients can connect (not only loopback/USB).
                server.bind(java.net.InetSocketAddress("0.0.0.0", port))
                serverSocket = server
                val addrs = localIpv4Addresses()
                publish(
                    state.copy(
                        status = "Waiting for Mac…",
                        problem = null,
                        listening = true,
                        connected = false,
                        streaming = false,
                        localAddresses = addrs,
                        port = port,
                        serviceName = serviceName,
                    ),
                )
                Log.i(tag, "listening on 0.0.0.0:$port addrs=$addrs")

                while (scope.isActive && running) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketException) {
                        if (!running) break
                        throw e
                    }
                    // Single client: the newest dial wins. (Do not "sticky-reject"
                    // extras — if the Mac has already abandoned the old socket,
                    // rejecting the new one leaves a zombie session with no
                    // sender and a permanent connect/RST thrash.)
                    closeClient("replaced")
                    try {
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        // Detect half-open peers faster on flaky Wi‑Fi.
                        socket.soTimeout = 0 // framing does blocking reads
                    } catch (_: Exception) {
                    }
                    clientSocket.set(socket)
                    lastOutbound = null
                    reconnectJob?.cancel()
                    sessionJob = scope.launch { runSession(socket) }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.e(tag, "listen error", e)
                publish(state.copy(status = "Listen error: ${e.message}", listening = false, problem = ReceiverProblem.LISTENER))
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
                serverSocket = null
                delay(1_000)
            }
        }
    }

    private suspend fun runSession(socket: Socket, outbound: Boolean = false) = withContext(Dispatchers.IO) {
        reconnectJob?.cancel()
        capsFlow.value = emptySet()
        lastDataMs.set(System.currentTimeMillis())
        resetSessionMetrics()
        publish(
            state.copy(
                status = "Connected — sending hello",
                problem = null,
                connected = true,
                streaming = false,
            ),
        )
        Log.i(tag, "session from ${socket.inetAddress?.hostAddress}")

        var boostedTid = -1
        var priorityBefore = 0
        try {
            sendHello()
            startPing()
            startWatchdog()
            startStats()

            // The read loop below never suspends, so it stays on this thread.
            // It feeds the decoder: keep it ahead of background work, and
            // restore afterwards because IO pool threads are shared.
            boostedTid = Process.myTid()
            priorityBefore = Process.getThreadPriority(boostedTid)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
            while (scope.isActive && !socket.isClosed) {
                val frame = FrameCodec.readFrame(input) ?: break
                lastDataMs.set(System.currentTimeMillis())
                handleInbound(frame)
            }
        } catch (e: Exception) {
            Log.i(tag, "session ended: ${e.message}")
        } finally {
            if (boostedTid == Process.myTid()) Process.setThreadPriority(priorityBefore)
            pingJob?.cancel()
            watchdogJob?.cancel()
            statsJob?.cancel()
            try {
                socket.close()
            } catch (_: Exception) {
            }
            // Only a session that ended on its own is re-dialed. closeClient()
            // (replaced / closing / sleeping) clears clientSocket first.
            val endedByPeer = clientSocket.compareAndSet(socket, null)
            capsFlow.value = emptySet()
            hudText.value = ""
            mainHandler.post { onCursorReset() }
            // Keep the TextureView Surface bound — only tear down the codec.
            // setSurface(null) made reconnects stay black: SPS arrived with no
            // surface, configure returned early, UI never showed video.
            try {
                decoder.resetForNewSession()
            } catch (_: Exception) {
            }
            audioPlayer.stop()
            publish(
                state.copy(
                    status = "Waiting for Mac…",
                    problem = null,
                    connected = false,
                    streaming = false,
                    videoWidth = 0,
                    videoHeight = 0,
                ),
            )
            if (endedByPeer && outbound) scheduleReconnect()
        }
    }

    private fun resetSessionMetrics() {
        offsetSamples.clear()
        clockOffsetMs = null
        lastRttMs = 0.0
        windowStartMs = System.currentTimeMillis()
        synchronized(metricsLock) {
            framesThisWindow = 0
            bytesThisWindow = 0L
            stallsThisWindow = 0
            lastFrameAtMs = 0L
            e2eWindow.clear()
            encodeWindow.clear()
        }
        statsReportCounter = 0
    }

    private fun handleInbound(payload: ByteArray) {
        if (AnnexBParser.isJsonControl(payload)) {
            handleJson(payload.toString(Charsets.UTF_8))
            return
        }
        // System audio from Mac (AUD1 PCM) — not video.
        if (AudioPlayer.isAudioFrame(payload)) {
            audioPlayer.feed(payload)
            return
        }
        val parsed = AnnexBParser.parse(payload)
        noteVideoFrame(payload.size, parsed.captureMs, parsed.sendMs)
        decoder.feedParsed(parsed)
        if (!state.streaming) {
            publish(state.copy(status = "Streaming", streaming = true))
        }
    }

    private fun noteVideoFrame(byteCount: Int, captureMs: Double?, sendMs: Double?) {
        val now = System.currentTimeMillis()
        val offset = clockOffsetMs
        synchronized(metricsLock) {
            bytesThisWindow += byteCount
            framesThisWindow++
            if (lastFrameAtMs > 0) {
                val gap = now - lastFrameAtMs
                if (gap > 50) stallsThisWindow++
            }
            lastFrameAtMs = now

            if (captureMs != null && sendMs != null) {
                encodeWindow.add(sendMs - captureMs)
                if (encodeWindow.size > 120) encodeWindow.removeAt(0)
                if (offset != null) {
                    val e2e = (now.toDouble() + offset) - captureMs
                    if (e2e > -50 && e2e < 5000) {
                        e2eWindow.add(e2e)
                        if (e2eWindow.size > 120) e2eWindow.removeAt(0)
                    }
                }
            }
        }
    }

    private fun handleJson(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                WireMessage.PONG -> {
                    val t1 = obj.optDouble("t", Double.NaN)
                    val mt = obj.optDouble("mt", Double.NaN)
                    if (t1.isNaN() || mt.isNaN()) return
                    val t2 = System.currentTimeMillis().toDouble()
                    val rtt = t2 - t1
                    if (rtt < 0 || rtt >= 2000) return
                    val offset = mt - (t1 + t2) / 2
                    offsetSamples.add(rtt to offset)
                    if (offsetSamples.size > 15) offsetSamples.removeAt(0)
                    clockOffsetMs = offsetSamples.minByOrNull { it.first }?.second
                    lastRttMs = rtt
                }
                WireMessage.PING -> {
                    // Mac liveness / health — nothing required beyond watchdog.
                }
                WireMessage.WELCOME -> {
                    val pv = obj.optInt("pv", WireProtocol.ASSUMED_WHEN_ABSENT)
                    val caps = obj.optJSONArray("caps")
                    capsFlow.value = buildSet {
                        if (caps != null) for (i in 0 until caps.length()) add(caps.optString(i))
                    }
                    Log.i(tag, "welcome from Mac pv=$pv caps=${capsFlow.value}")
                    // The Mac says which mode this session really is, so the
                    // sidebar toggle is right even after a Mac-side change.
                    obj.optString("mode").takeIf { it.isNotEmpty() }?.let { mode ->
                        mainHandler.post { onMode(mode == "mirror") }
                    }
                }
                WireMessage.MODE -> {
                    val mode = obj.optString("mode")
                    if (mode == "mirror" || mode == "extend") {
                        mainHandler.post { onMode(mode == "mirror") }
                    }
                }
                WireMessage.UPDATE_REQUIRED -> {
                    Log.w(tag, "Mac requested update: ${obj.optString("message")}")
                    publish(state.copy(status = obj.optString("message", "Update required"), problem = ReceiverProblem.UPDATE_REQUIRED))
                }
                WireMessage.CURSOR -> {
                    val visible = obj.optInt("v", 0) == 1
                    val x = obj.optDouble("x", 0.0)
                    val y = obj.optDouble("y", 0.0)
                    mainHandler.post { onCursor(x, y, visible) }
                }
                WireMessage.CURSOR_IMG -> {
                    val png = obj.optString("png")
                    val nw = obj.optDouble("nw", 0.0)
                    val nh = obj.optDouble("nh", 0.0)
                    val ax = obj.optDouble("ax", 0.0)
                    val ay = obj.optDouble("ay", 0.0)
                    if (nw > 0 && nh > 0 && png.isNotEmpty()) {
                        mainHandler.post { onCursorImage(png, nw, nh, ax, ay) }
                    }
                }
                WireMessage.CLIP_IMAGE -> {
                    val encoded = obj.optString("png")
                    if (encoded.isNotEmpty() && encoded.length <= MAX_CLIP_IMAGE_BASE64) {
                        val png = try {
                            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        if (png != null) mainHandler.post { onClipboardImage(png) }
                    }
                }
                WireMessage.CLIP -> {
                    val text = obj.optString("text")
                    if (text.isNotEmpty() && text.length <= MAX_CLIP_CHARS) {
                        mainHandler.post { onClipboard(text) }
                    }
                }
                else -> Log.d(tag, "ignore control type=${obj.optString("type")}")
            }
        } catch (e: Exception) {
            Log.w(tag, "bad JSON control: ${e.message}")
        }
    }

    private suspend fun sendHello() {
        val p = panel
        val json = JSONObject()
            .put("type", WireMessage.HELLO)
            .put("pixelsWide", p.pixelsWide)
            .put("pixelsHigh", p.pixelsHigh)
            .put("scale", p.scale)
            .put("refresh", p.refreshHz)
            .put("ext", JSONArray(WireCaps.ALL))
            .put("device", "Android")
            .put("id", installId)
            .put("pv", WireProtocol.VERSION)
            // Ask the Mac to stream system audio (AUD1 frames).
            .put("audio", 1)
        sendJson(json)
        Log.i(tag, "hello ${p.pixelsWide}x${p.pixelsHigh} @${p.scale}x audio=1")
    }

    private suspend fun sendJson(obj: JSONObject) {
        writeMutex.withLock {
            val socket = clientSocket.get() ?: return
            try {
                if (outputSocket !== socket) {
                    clientOutput = BufferedOutputStream(socket.getOutputStream())
                    outputSocket = socket
                }
                FrameCodec.writeJsonFrame(clientOutput!!, obj.toString())
            } catch (e: Exception) {
                // A failed flush can leave bytes in the buffer. Do not replay
                // those bytes on the next message or retain a failed socket.
                clientOutput = null
                outputSocket = null
                Log.w(tag, "send failed: ${e.message}")
            }
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(2_000)
                sendJson(
                    JSONObject()
                        .put("type", WireMessage.PING)
                        .put("t", System.currentTimeMillis().toDouble()),
                )
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            // Longer first grace: Mac may still be building the virtual display
            // / waiting for Screen Recording after TCP connects (often 5–20s).
            val connectedAt = System.currentTimeMillis()
            while (isActive) {
                delay(1_000)
                val idle = System.currentTimeMillis() - lastDataMs.get()
                val sinceConnect = System.currentTimeMillis() - connectedAt
                // First 20s: setup / permission / first keyframe. After that,
                // 15s of total radio silence means a half-open link. Shorter
                // limits (6s) dropped healthy sessions under decoder load.
                val limit = if (sinceConnect < 20_000) 20_000L else 15_000L
                if (idle > limit) {
                    Log.w(tag, "watchdog: no data for ${idle}ms — dropping")
                    closeClient("watchdog")
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    /** Every 1s roll a local window; every 5s send aggregate `stats` to the Mac. */
    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val (fps, mbps, stalls, e2e50, e2e95, enc50) = synchronized(metricsLock) {
                    val elapsed = (now - windowStartMs).coerceAtLeast(1) / 1000.0
                    val f = (framesThisWindow / elapsed).toInt()
                    val m = bytesThisWindow * 8.0 / elapsed / 1_000_000.0
                    val s = stallsThisWindow
                    // Snapshot before percentile so session thread can keep writing.
                    val e2eSnap = ArrayList(e2eWindow)
                    val encSnap = ArrayList(encodeWindow)
                    framesThisWindow = 0
                    bytesThisWindow = 0L
                    stallsThisWindow = 0
                    windowStartMs = now
                    StatsTick(
                        fps = f,
                        mbps = m,
                        stalls = s,
                        e2e50 = H264Decoder.percentile(e2eSnap, 0.5),
                        e2e95 = H264Decoder.percentile(e2eSnap, 0.95),
                        enc50 = H264Decoder.percentile(encSnap, 0.5),
                    )
                }
                val decSnap = decoder.snapshotAndResetDecodeSamples()
                hudText.value = "%d fps · %d ms e2e · %d ms rtt · %.1f Mbps · %d drops".format(
                    fps, e2e50.toInt(), lastRttMs.toInt(), mbps, decSnap.inputDrops + decSnap.outputDrops,
                )

                statsReportCounter++
                if (statsReportCounter < 5) continue
                statsReportCounter = 0

                val json = JSONObject()
                    .put("type", WireMessage.STATS)
                    .put("transport", "WiFi")
                    .put("fps", fps)
                    .put("mbps", (mbps * 10).toLong() / 10.0)
                    .put("e2e50", e2e50.roundTo())
                    .put("e2e95", e2e95.roundTo())
                    .put("enc50", enc50.roundTo())
                    .put("rtt", lastRttMs.roundTo())
                    .put("stalls", stalls)
                    .put("dec50", decSnap.decodeP50Ms.roundTo())
                    .put("drops", decSnap.inputDrops + decSnap.outputDrops)
                    .put("inDrops", decSnap.inputDrops)
                    .put("outDrops", decSnap.outputDrops)
                    .put("offsetKnown", clockOffsetMs != null)
                sendJson(json)
                Log.i(
                    tag,
                    "stats fps=$fps mbps=${"%.1f".format(mbps)} e2e50=${e2e50.toInt()} " +
                        "rtt=${lastRttMs.toInt()} drops=${decSnap.inputDrops + decSnap.outputDrops}",
                )
                e2eWindow.clear()
                encodeWindow.clear()
            }
        }
    }

    private fun Double.roundTo(): Double = kotlin.math.round(this)

    private fun closeClient(reason: String) {
        Log.i(tag, "close client ($reason)")
        try {
            clientSocket.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
        sessionJob?.cancel()
        pingJob?.cancel()
        watchdogJob?.cancel()
        statsJob?.cancel()
    }

    fun onVideoSize(width: Int, height: Int) {
        publish(state.copy(videoWidth = width, videoHeight = height, streaming = true, status = "Receiving ${width}×${height}"))
    }

    private fun publish(next: ReceiverUiState) {
        state = next
        onState(next)
    }

    companion object {
        private const val RECONNECT_ATTEMPTS = 10
        private const val MAX_CLIP_CHARS = 256 * 1024
        /** Base64 of the largest PNG we accept (a third larger than the bytes). */
        private const val MAX_CLIP_IMAGE_BASE64 = ImageClip.MAX_PNG_BYTES / 3 * 4 + 16

        fun localIpv4Addresses(): List<String> {
            val out = ArrayList<String>()
            try {
                val en = NetworkInterface.getNetworkInterfaces() ?: return out
                while (en.hasMoreElements()) {
                    val nif = en.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            out.add(a.hostAddress ?: continue)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return out
        }
    }
}
