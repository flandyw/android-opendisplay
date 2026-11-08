package app.opendisplay.receiver.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import app.opendisplay.receiver.net.FrameCodec
import app.opendisplay.receiver.protocol.WireMessage
import app.opendisplay.receiver.protocol.WireProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The reverse of the rest of this app: Android is the OpenDisplay *sender*.
 * Dials a receiver (the Mac receiver app on :9000), waits for `hello`, replies
 * `welcome` + `streamConfig`, and streams the phone's screen as H.264 Annex B,
 * one access unit per frame, SPS/PPS before every IDR (PROTOCOL.md §5).
 * Input from the receiver is ignored: injecting it needs an accessibility service.
 */
class ScreenCastSender(
    private val host: String,
    private val port: Int,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val onEnded: (error: String?) -> Unit,
) {
    private val tag = "ScreenCastSender"
    private val socket = Socket()
    @Volatile private var running = true
    @Volatile private var encoder: MediaCodec? = null
    private lateinit var out: OutputStream

    fun start() {
        thread(name = "cast-session") { session() }
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun session() {
        var error: String? = null
        var display: VirtualDisplay? = null
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 6_000 // peer pings every 2 s; silence > 5 s is a dead link (§8.2)
            val input = BufferedInputStream(socket.getInputStream())
            out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)

            while (running) {
                val frame = FrameCodec.readFrame(input) ?: return
                if (JSONObject(String(frame, Charsets.UTF_8)).optString("type") == WireMessage.HELLO) break
            }
            send(JSONObject().put("type", WireMessage.WELCOME).put("pv", WireProtocol.VERSION)
                .put("min", WireProtocol.MIN_SUPPORTED_PEER))
            send(JSONObject().put("type", "streamConfig").put("codec", "h264")
                .put("width", width).put("height", height).put("framesPerSecond", FPS))

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(encoderFormat(width, height), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            display = projection.createVirtualDisplay("OpenDisplay cast", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            thread(name = "cast-encode") { drain(codec) }
            thread(name = "cast-ping") {
                while (running) {
                    runCatching { send(JSONObject().put("type", WireMessage.PING)) }.onFailure { stop() }
                    Thread.sleep(2_000)
                }
            }

            while (running) {
                val frame = FrameCodec.readFrame(input) ?: break
                val msg = runCatching { JSONObject(String(frame, Charsets.UTF_8)) }.getOrNull() ?: continue
                when (msg.optString("type")) {
                    WireMessage.CLOSING, WireMessage.SLEEPING -> break
                    WireMessage.KF -> requestKeyframe()
                    else -> controlReply(msg, System.currentTimeMillis())?.let(::send)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(tag, "cast to $host:$port ended: ${e.message}")
                error = e.message ?: e.javaClass.simpleName
            }
        } finally {
            running = false
            display?.release()
            encoder?.let { runCatching { it.stop() }; it.release() }
            encoder = null
            runCatching { socket.close() }
            onEnded(error)
        }
    }

    private fun requestKeyframe() {
        encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buf = codec.getOutputBuffer(index)
                val bytes = ByteArray(info.size)
                buf?.position(info.offset)
                buf?.get(bytes)
                codec.releaseOutputBuffer(index, false)
                when {
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> config = bytes
                    bytes.isEmpty() -> Unit
                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> sendVideo(config + bytes)
                    else -> sendVideo(bytes)
                }
            }
        } catch (e: Exception) {
            // Codec released by session() during shutdown, or the socket died.
            if (running) Log.w(tag, "encoder drain: ${e.message}")
            stop()
        }
    }

    private fun send(msg: JSONObject) = sendVideo(msg.toString().toByteArray(Charsets.UTF_8))

    // shortcut: a slow link blocks the encoder thread instead of dropping frames; add a drop policy if Wi-Fi lag shows.
    @Synchronized
    private fun sendVideo(payload: ByteArray) = FrameCodec.writeFrame(out, payload)

    companion object {
        const val FPS = 60

        /** Reply to a receiver control message, or null when it needs none. */
        fun controlReply(msg: JSONObject, nowMs: Long): JSONObject? = when (msg.optString("type")) {
            WireMessage.PING -> JSONObject().put("type", WireMessage.PONG).put("t", msg.opt("t")).put("mt", nowMs)
            else -> null
        }

        /** Phone screen scaled so the long side fits [maxLong], rounded to the encoder's 16-pixel blocks. */
        fun streamSize(realW: Int, realH: Int, maxLong: Int = 1920): Pair<Int, Int> {
            val scale = minOf(1.0, maxLong.toDouble() / maxOf(realW, realH))
            fun fit(v: Int) = maxOf(16, (v * scale).toInt() / 16 * 16)
            return fit(realW) to fit(realH)
        }

        private fun encoderFormat(w: Int, h: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * FPS / 8).coerceIn(4_000_000, 20_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // frames are shown in arrival order (§5.1)
            // A static screen yields no frames; repeat so a `kf` request still gets an IDR.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000)
        }
    }
}
