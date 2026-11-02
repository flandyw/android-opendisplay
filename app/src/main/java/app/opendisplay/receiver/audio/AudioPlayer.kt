package app.opendisplay.receiver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays Mac → device PCM from wire `AUD1` frames (s16le mono @ 48 kHz).
 */
class AudioPlayer {
    private val tag = "AudioPlayer"
    private var track: AudioTrack? = null
    private val released = AtomicBoolean(false)
    private var sampleRate = 48_000
    private var channels = 1

    @Synchronized
    fun feed(payload: ByteArray) {
        if (released.get()) return
        val packet = AudioPacket.parse(payload) ?: return
        val sr = packet.sampleRate
        val ch = packet.channels
        val pcmOffset = 12
        val expectedBytes = packet.pcmBytes

        ensureTrack(sr, ch)
        val t = track ?: return
        try {
            val written = t.write(payload, pcmOffset, expectedBytes, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) releaseTrackOnly()
        } catch (e: Exception) {
            Log.w(tag, "write failed: ${e.message}")
            releaseTrackOnly()
        }
    }

    private fun ensureTrack(sr: Int, ch: Int) {
        if (track != null && sampleRate == sr && channels == ch) return
        releaseTrackOnly()
        sampleRate = sr
        channels = ch
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf =
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRate * TARGET_BUFFER_MS / 1000 * channels * 2)
        try {
            val t =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    // Non-blocking writes drop what doesn't fit, so this buffer
                    // is the ceiling on audio lag behind the picture.
                    .setBufferSizeInBytes(minBuf)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            track = t
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" }
            t.play()
            Log.i(tag, "AudioTrack ${sampleRate}Hz ch=$channels buf=$minBuf")
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack create failed", e)
            releaseTrackOnly()
        }
    }

    @Synchronized
    fun stop() {
        releaseTrackOnly()
    }

    @Synchronized
    fun release() {
        if (!released.compareAndSet(false, true)) return
        releaseTrackOnly()
    }

    private fun releaseTrackOnly() {
        val t = track ?: return
        track = null
        try { t.pause() } catch (_: Exception) { }
        try { t.flush() } catch (_: Exception) { }
        try { t.release() } catch (_: Exception) { }
    }

    companion object {
        private const val TARGET_BUFFER_MS = 60

        fun isAudioFrame(payload: ByteArray): Boolean {
            if (payload.size < 4) return false
            return payload[0] == 'A'.code.toByte() &&
                payload[1] == 'U'.code.toByte() &&
                payload[2] == 'D'.code.toByte() &&
                payload[3] == '1'.code.toByte()
        }
    }
}
