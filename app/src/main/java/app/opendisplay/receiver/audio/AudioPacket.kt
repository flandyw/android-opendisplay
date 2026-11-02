package app.opendisplay.receiver.audio

/** Validated AUD1 header; PCM is borrowed from the original payload. */
internal data class AudioPacket(val sampleRate: Int, val channels: Int, val pcmBytes: Int) {
    companion object {
        fun parse(payload: ByteArray): AudioPacket? {
            if (payload.size < 12 || !AudioPlayer.isAudioFrame(payload) || payload[4] != 1.toByte()) return null
            val channels = payload[5].toInt() and 0xff
            val sampleRate = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
            if (channels !in 1..2 || sampleRate !in 8_000..65_535) return null
            var frames = 0L
            for (i in 8..11) frames = (frames shl 8) or (payload[i].toLong() and 0xff)
            val bytes = frames * channels * 2
            if (frames == 0L || bytes != (payload.size - 12).toLong()) return null
            return AudioPacket(sampleRate, channels, bytes.toInt())
        }
    }
}
