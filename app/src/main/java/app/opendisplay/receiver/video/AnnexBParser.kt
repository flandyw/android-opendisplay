package app.opendisplay.receiver.video

import java.nio.ByteBuffer

/**
 * Split a Mac video payload into optional JSON meta + Annex B NALUs.
 * Start codes are 00 00 00 01 only (see WIRE.md).
 */
object AnnexBParser {
    /** A view into the received frame. Only SPS/PPS need an owned copy. */
    data class Nalu(val payload: ByteArray, val offset: Int, val size: Int) {
        val type: Int get() = payload[offset].toInt() and 0x1F
        val isDecoderInput: Boolean get() = type != 7 && type != 8 && type != 6

        fun copyBytes(): ByteArray = payload.copyOfRange(offset, offset + size)

        fun contentEquals(bytes: ByteArray?): Boolean {
            if (bytes == null || bytes.size != size) return false
            for (i in bytes.indices) {
                if (bytes[i] != payload[offset + i]) return false
            }
            return true
        }
    }

    /** Borrows the received payload; keep it unchanged until decoder input is written. */
    data class ParsedFrame(
        val captureMs: Double?,
        val sendMs: Double?,
        val nalus: List<Nalu>,
    ) {
        val decoderInputSize: Int = nalus.sumOf { if (it.isDecoderInput) 4 + it.size else 0 }
        val hasIdr: Boolean = nalus.any { it.type == 5 }

        /** Write slices straight to MediaCodec, without rebuilding a frame array. */
        fun writeDecoderInput(buffer: ByteBuffer) {
            require(buffer.remaining() >= decoderInputSize) { "input buffer too small" }
            for (nalu in nalus) {
                if (!nalu.isDecoderInput) continue
                buffer.put(START_CODE)
                buffer.put(nalu.payload, nalu.offset, nalu.size)
            }
        }
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun isJsonControl(payload: ByteArray): Boolean {
        // Cursor sprites are base64 PNG (Mac caps raw PNG at 24KB ≈ ~32KB b64).
        // Leave headroom so cursorImg never falls through as video.
        if (payload.isEmpty() || payload.size >= 64 * 1024) return false
        if (payload[0] != '{'.code.toByte()) return false
        for (b in payload) {
            if (b == 0.toByte()) return false
        }
        return true
    }

    fun parse(payload: ByteArray): ParsedFrame {
        var startCode = findStartCode(payload, 0)
        var captureMs: Double? = null
        var sendMs: Double? = null
        val bodyStart = if (startCode >= 0) startCode else payload.size

        if (bodyStart > 0) {
            val meta = String(payload, 0, bodyStart, Charsets.UTF_8)
            // Lightweight parse — avoid full JSON dependency on the hot path.
            captureMs = extractDouble(meta, "cap")
            sendMs = extractDouble(meta, "snd")
        }

        val nalus = ArrayList<Nalu>(4)
        while (startCode >= 0) {
            val dataStart = startCode + 4
            val nextStartCode = findStartCode(payload, dataStart)
            val dataEnd = if (nextStartCode >= 0) nextStartCode else payload.size
            if (dataStart < dataEnd) {
                nalus.add(Nalu(payload, dataStart, dataEnd - dataStart))
            }
            startCode = nextStartCode
        }
        return ParsedFrame(captureMs, sendMs, nalus)
    }

    fun naluType(nalu: Nalu): Int = nalu.type

    private fun findStartCode(payload: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < payload.size) {
            if (payload[i] == 0.toByte() &&
                payload[i + 1] == 0.toByte() &&
                payload[i + 2] == 0.toByte() &&
                payload[i + 3] == 1.toByte()
            ) {
                return i
            } else {
                i++
            }
        }
        return -1
    }

    private fun extractDouble(json: String, key: String): Double? {
        val needle = "\"$key\":"
        val idx = json.indexOf(needle)
        if (idx < 0) return null
        var i = idx + needle.length
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '.' || json[i] == '-')) i++
        if (i == start) return null
        return json.substring(start, i).toDoubleOrNull()
    }
}
