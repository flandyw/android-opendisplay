package app.opendisplay.receiver.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 decode into a Surface. Configures from in-band SPS/PPS.
 *
 * Latency policy:
 * - Never block waiting for an input buffer (timeout 0) — drop the frame instead.
 * - When several output buffers are ready, render only the newest (drop older).
 * - After input drops, request a keyframe so the stream can recover.
 *
 * Cross-version notes (minSdk 26):
 * - [MediaFormat.KEY_LOW_LATENCY] is API 30+ only; below that we try vendor keys.
 * - [MediaFormat.KEY_PRIORITY] is API 23+ (always available here).
 */
class H264Decoder(
    private val onNeedKeyframe: () -> Unit,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
) {
    private val tag = "H264Decoder"
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configured = false
    private val released = AtomicBoolean(false)
    private var ptsUs = 0L
    private var configureAttempts = 0
    private var consecutiveInputDrops = 0
    private var lastKeyframeRequestMs = 0L
    private val outputInfo = MediaCodec.BufferInfo()

    private val framesIn = AtomicLong(0)
    private val framesOut = AtomicLong(0)
    private val inputDrops = AtomicLong(0)
    private val outputDrops = AtomicLong(0)
    private val decodeMsSamples = ArrayList<Double>(64)
    private val sampleLock = Any()

    data class Snapshot(
        val framesIn: Long,
        val framesOut: Long,
        val inputDrops: Long,
        val outputDrops: Long,
        val decodeP50Ms: Double,
    )

    fun snapshotAndResetDecodeSamples(): Snapshot {
        val p50 = synchronized(sampleLock) {
            val v = percentile(decodeMsSamples, 0.5)
            decodeMsSamples.clear()
            v
        }
        return Snapshot(
            framesIn = framesIn.get(),
            framesOut = framesOut.get(),
            inputDrops = inputDrops.get(),
            outputDrops = outputDrops.get(),
            decodeP50Ms = p50,
        )
    }

    @Synchronized
    fun setSurface(surface: Surface?) {
        if (this.surface === surface && surface != null) return
        this.surface = surface
        // Surface changed — force reconfigure with next SPS/PPS.
        if (configured) {
            releaseCodecOnly()
            configured = false
        }
    }

    /**
     * Tear down the codec between Mac sessions without dropping the UI
     * Surface. Clearing the surface (old behaviour) left reconnects unable
     * to configure: frames arrived but [configure] returned early → black
     * tablet until the TextureView was recreated.
     */
    @Synchronized
    fun resetForNewSession() {
        releaseCodecOnly()
        configured = false
        sps = null
        pps = null
        consecutiveInputDrops = 0
    }

    fun feed(payload: ByteArray) {
        if (released.get()) return
        if (AnnexBParser.isJsonControl(payload)) return
        feedParsed(AnnexBParser.parse(payload))
    }

    @Synchronized
    fun feedParsed(parsed: AnnexBParser.ParsedFrame) {
        if (released.get()) return

        val feedStartNs = System.nanoTime()
        var formatChanged = false
        for (nalu in parsed.nalus) {
            when (nalu.type) {
                7 -> { // SPS
                    if (!nalu.contentEquals(sps)) {
                        sps = nalu.copyBytes()
                        formatChanged = true
                    }
                }
                8 -> { // PPS
                    if (!nalu.contentEquals(pps)) {
                        pps = nalu.copyBytes()
                        formatChanged = true
                    }
                }
            }
        }

        if (formatChanged || !configured) {
            val s = sps
            val p = pps
            if (s != null && p != null) {
                try {
                    configure(s, p)
                } catch (e: Exception) {
                    Log.e(tag, "configure failed (API ${Build.VERSION.SDK_INT})", e)
                    requestKeyframeThrottled()
                    return
                }
            }
        }

        if (!configured) return

        // Release ready output before asking for input: a codec can run out of
        // input buffers while waiting for us to return its output buffers.
        drainOutput()
        if (!configured || parsed.decoderInputSize == 0) return
        if (!queueInput(parsed)) {
            return
        }
        framesIn.incrementAndGet()
        drainOutput()
        val decodeMs = (System.nanoTime() - feedStartNs) / 1_000_000.0
        synchronized(sampleLock) {
            decodeMsSamples.add(decodeMs)
            if (decodeMsSamples.size > 120) {
                decodeMsSamples.removeAt(0)
            }
        }
    }

    private fun configure(sps: ByteArray, pps: ByteArray) {
        releaseCodecOnly()
        val surf = surface
        if (surf == null || !surf.isValid) {
            Log.w(tag, "configure skipped — no valid Surface yet (will retry on next keyframe)")
            requestKeyframeThrottled()
            return
        }
        configureAttempts++

        val (width, height) = estimateSizeFromSps(sps) ?: (1920 to 1080)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))

        applyLowLatencyHints(format)

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format, surf, null, 0)
        } catch (e: Exception) {
            // Some OEMs reject vendor keys — retry bare format once.
            Log.w(tag, "configure with low-latency hints failed, retrying plain: ${e.message}")
            c.release()
            val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            plain.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
            plain.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
            val c2 = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c2.configure(plain, surf, null, 0)
            c2.start()
            codec = c2
            configured = true
            consecutiveInputDrops = 0
            Log.i(tag, "decoder configured (plain) ${width}x${height} name=${c2.name}")
            return
        }
        c.start()
        codec = c
        configured = true
        consecutiveInputDrops = 0
        Log.i(tag, "decoder configured ${width}x${height} name=${c.name} attempt=$configureAttempts")
    }

    /**
     * Best-effort low-latency flags. Safe on all API ≥ 26; unknown keys are
     * ignored by the framework, but a few OEMs throw — caller retries plain.
     */
    private fun applyLowLatencyHints(format: MediaFormat) {
        // Real-time priority (API 23+).
        try {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        } catch (_: Exception) {
        }

        // Official low-latency key (API 30 / Android 11+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            } catch (_: Exception) {
            }
        }

        // Vendor extensions used by common SoCs when the public key is missing
        // or ignored (Android 8–10 tablets, some Android 11+ builds).
        val vendorFlags = arrayOf(
            "vendor.qti-ext-dec-low-latency.enable",
            "vendor.low-latency.enable",
            "low-latency",
            "vdec-lowlatency",
        )
        for (key in vendorFlags) {
            try {
                format.setInteger(key, 1)
            } catch (_: Exception) {
            }
        }
    }

    /** @return true if the frame was queued */
    private fun queueInput(parsed: AnnexBParser.ParsedFrame): Boolean {
        val c = codec ?: return false
        try {
            // Non-blocking: never stall the TCP read loop waiting on the codec.
            val inIndex = c.dequeueInputBuffer(0)
            if (inIndex < 0) {
                inputDrops.incrementAndGet()
                consecutiveInputDrops++
                // After a few drops, demand an IDR — but rate-limit: a storm of
                // kf messages clogs the control channel and makes the Mac
                // re-key every frame (bitrate spikes / more drops).
                if (consecutiveInputDrops >= 3 || parsed.hasIdr) {
                    requestKeyframeThrottled()
                }
                if (consecutiveInputDrops == 1 || consecutiveInputDrops % 60 == 0) {
                    Log.w(tag, "no input buffer — drop frame (drops=${inputDrops.get()})")
                }
                return false
            }
            consecutiveInputDrops = 0
            val buf = c.getInputBuffer(inIndex) ?: return false
            buf.clear()
            if (buf.remaining() < parsed.decoderInputSize) {
                Log.w(tag, "input buffer too small ${buf.remaining()} < ${parsed.decoderInputSize}")
                c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                inputDrops.incrementAndGet()
                requestKeyframeThrottled()
                return false
            }
            parsed.writeDecoderInput(buf)
            ptsUs += 16_666
            c.queueInputBuffer(inIndex, 0, parsed.decoderInputSize, ptsUs, 0)
            return true
        } catch (e: Exception) {
            Log.e(tag, "queueInput failed", e)
            releaseCodecOnly()
            configured = false
            requestKeyframeThrottled()
            return false
        }
    }

    /** At most one keyframe request per 500ms. */
    private fun requestKeyframeThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestMs < 500) return
        lastKeyframeRequestMs = now
        onNeedKeyframe()
    }

    private fun drainOutput() {
        val c = codec ?: return
        // Keep only the newest ready buffer, returning older buffers promptly.
        var pending = -1
        try {
            while (true) {
                val outIndex = c.dequeueOutputBuffer(outputInfo, 0)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = c.outputFormat
                        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.i(tag, "output format $w x $h")
                        onVideoSize(w, h)
                    }
                    outIndex >= 0 -> {
                        if (pending >= 0) {
                            c.releaseOutputBuffer(pending, false)
                            outputDrops.incrementAndGet()
                        }
                        pending = outIndex
                    }
                }
            }
            if (pending < 0) return
            c.releaseOutputBuffer(pending, true)
            pending = -1
            framesOut.incrementAndGet()
        } catch (e: Exception) {
            Log.e(tag, "drainOutput failed", e)
            if (pending >= 0) {
                try {
                    c.releaseOutputBuffer(pending, false)
                } catch (_: Exception) {
                }
            }
            releaseCodecOnly()
            configured = false
            requestKeyframeThrottled()
        }
    }

    @Synchronized
    fun release() {
        if (!released.compareAndSet(false, true)) return
        releaseCodecOnly()
    }

    private fun releaseCodecOnly() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        configured = false
    }

    private fun withStartCode(nalu: ByteArray): ByteArray {
        val out = ByteArray(4 + nalu.size)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(nalu, 0, out, 4, nalu.size)
        return out
    }

    /**
     * Minimal H.264 SPS parse for pic_width/height (enough for MediaFormat).
     * Returns null if the SPS is truncated or non-baseline layout.
     */
    private fun estimateSizeFromSps(sps: ByteArray): Pair<Int, Int>? {
        // Skip NAL header; exp-Golomb walk is fragile — prefer output-format.
        // Keep a coarse size for configure only.
        if (sps.size < 4) return null
        // Many SPS blobs encode 1080p-class streams; decoder will correct via
        // INFO_OUTPUT_FORMAT_CHANGED. Avoid wrong non-zero that breaks some OEMs.
        return null
    }

    companion object {
        /**
         * p in [0,1]. Tolerates empty lists and nulls (concurrent snapshot races).
         */
        fun percentile(samples: List<Double?>, p: Double): Double {
            if (samples.isEmpty()) return 0.0
            // Copy + drop nulls: ArrayList can expose nulls if mutated while
            // toTypedArray/sorted runs on another thread.
            val sorted = ArrayList<Double>(samples.size)
            for (v in samples) {
                if (v != null && !v.isNaN()) sorted.add(v)
            }
            if (sorted.isEmpty()) return 0.0
            sorted.sort()
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }
    }
}
