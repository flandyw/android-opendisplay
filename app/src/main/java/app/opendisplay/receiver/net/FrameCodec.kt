package app.opendisplay.receiver.net

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing: [uint32 BE length][payload].
 * Shared by video and control on the same TCP connection.
 */
object FrameCodec {
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_FRAME_BYTES) { "frame too large: ${payload.size}" }
        val size = payload.size
        val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
        out.write(header)
        out.write(payload)
        out.flush()
    }

    fun writeJsonFrame(out: OutputStream, json: String) {
        writeFrame(out, json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Blocking read of one full frame.
     * @return null on clean EOF before any header bytes
     */
    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        val headerRead = readFullyOrEof(input, header)
        if (headerRead == 0) return null
        if (headerRead < 4) throw EOFException("truncated frame header")
        val len = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw IllegalArgumentException("invalid frame length: $len")
        }
        val payload = ByteArray(len)
        val bodyRead = readFullyOrEof(input, payload)
        if (bodyRead < len) throw EOFException("truncated frame body")
        return payload
    }

    /** @return number of bytes read; 0 means EOF with nothing read */
    private fun readFullyOrEof(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off
            if (n == 0) {
                val single = input.read()
                if (single < 0) return off
                buf[off++] = single.toByte()
            } else off += n
        }
        return off
    }
}
