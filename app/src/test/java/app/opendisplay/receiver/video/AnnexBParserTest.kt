package app.opendisplay.receiver.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class AnnexBParserTest {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    @Test
    fun slicesReferenceTheReceivedFrame() {
        val meta = """{"cap":100.5,"snd":110.25}""".toByteArray()
        val slice = byteArrayOf(0x65, 0x01, 0x02)
        val payload = meta + startCode + slice
        val parsed = AnnexBParser.parse(payload)

        assertEquals(100.5, parsed.captureMs!!, 0.0)
        assertEquals(110.25, parsed.sendMs!!, 0.0)
        assertSame(payload, parsed.nalus.single().payload)
        assertEquals(meta.size + 4, parsed.nalus.single().offset)
        assertEquals(slice.size, parsed.nalus.single().size)
        assertTrue(parsed.hasIdr)
    }

    @Test
    fun writesOnlyDecoderNalusInWireOrder() {
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68, 0x01)
        val sei = byteArrayOf(0x06, 0x02)
        val aud = byteArrayOf(0x09, 0x10)
        val idr = byteArrayOf(0x65, 0x20, 0x30)
        val slice = byteArrayOf(0x41, 0x40)
        val payload = listOf(sps, pps, sei, aud, idr, slice)
            .fold(ByteArray(0)) { acc, nalu -> acc + startCode + nalu }
        val expected = startCode + aud + startCode + idr + startCode + slice
        val parsed = AnnexBParser.parse(payload)

        // MediaCodec provides a direct buffer; start codes must not depend on
        // the buffer's byte order or overwrite bytes before its position.
        val buffer = ByteBuffer.allocateDirect(expected.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x7f).put(0x7e)
        parsed.writeDecoderInput(buffer)
        assertEquals(expected.size, parsed.decoderInputSize)
        assertEquals(expected.size + 2, buffer.position())
        buffer.flip()
        val actual = ByteArray(buffer.remaining())
        buffer.get(actual)
        assertArrayEquals(byteArrayOf(0x7f, 0x7e) + expected, actual)
    }

    @Test
    fun parameterSetsCanBeRetainedWithoutRetainingTheFrame() {
        val payload = startCode + byteArrayOf(0x67, 0x42)
        val nalu = AnnexBParser.parse(payload).nalus.single()
        val saved = nalu.copyBytes()
        assertTrue(nalu.contentEquals(saved))
        assertFalse(nalu.contentEquals(null))
        assertFalse(nalu.contentEquals(byteArrayOf(0x67)))
        assertFalse(nalu.contentEquals(byteArrayOf(0x67, 0x43)))
        payload[payload.lastIndex] = 0x43
        assertArrayEquals(byteArrayOf(0x67, 0x42), saved)
        assertFalse(nalu.contentEquals(saved))
    }

    @Test
    fun ignoresEmptyNalusAndTrailingStartCodes() {
        val payload = startCode + startCode + byteArrayOf(0x41, 0x02) + startCode
        val parsed = AnnexBParser.parse(payload)
        assertEquals(1, parsed.nalus.size)
        assertNull(parsed.captureMs)
        assertNull(parsed.sendMs)
        assertFalse(parsed.hasIdr)
        assertArrayEquals(startCode + byteArrayOf(0x41, 0x02), decoderBytes(parsed))
    }

    @Test
    fun metadataOnlyAndTruncatedFramesHaveNoDecoderInput() {
        for (payload in listOf(ByteArray(0), byteArrayOf(0, 0, 0), startCode,
            """{"cap":12,"snd":13}""".toByteArray())) {
            val parsed = AnnexBParser.parse(payload)
            assertTrue(parsed.nalus.isEmpty())
            assertEquals(0, parsed.decoderInputSize)
            assertFalse(parsed.hasIdr)
            assertArrayEquals(ByteArray(0), decoderBytes(parsed))
        }
    }

    @Test
    fun rejectsInsufficientSpaceBeforeWritingAnything() {
        val parsed = AnnexBParser.parse(startCode + byteArrayOf(0x65, 0x01))
        val buffer = ByteBuffer.allocate(parsed.decoderInputSize)
        buffer.put(0x7f)
        try {
            parsed.writeDecoderInput(buffer)
            throw AssertionError("expected insufficient capacity to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, buffer.position())
            assertEquals(0x7f.toByte(), buffer.get(0))
        }
    }

    @Test
    fun matchesCopyBasedFramingForVariedPayloads() {
        val random = Random(42)
        repeat(200) {
            val payload = """{"cap":10,"snd":20}""".toByteArray() +
                List(random.nextInt(1, 12)) {
                    startCode + random.nextBytes(random.nextInt(0, 2048))
                }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            val originalNalus = splitByStartCode(payload)
            val parsed = AnnexBParser.parse(payload)
            assertEquals(originalNalus.size, parsed.nalus.size)
            originalNalus.forEachIndexed { i, bytes ->
                assertArrayEquals(bytes, parsed.nalus[i].copyBytes())
            }
            val expected = originalNalus.filter { (it[0].toInt() and 0x1f) !in listOf(6, 7, 8) }
                .fold(ByteArray(0)) { acc, nalu -> acc + startCode + nalu }
            assertArrayEquals(expected, decoderBytes(parsed))
        }
    }

    private fun decoderBytes(parsed: AnnexBParser.ParsedFrame): ByteArray {
        val buffer = ByteBuffer.allocate(parsed.decoderInputSize)
        parsed.writeDecoderInput(buffer)
        return buffer.array()
    }

    /** Reference framing used before slices were introduced. */
    private fun splitByStartCode(payload: ByteArray): List<ByteArray> {
        val offsets = ArrayList<Int>()
        var i = 0
        while (i + 3 < payload.size) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() &&
                payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()) {
                offsets.add(i)
                i += 4
            } else {
                i++
            }
        }
        return offsets.mapIndexedNotNull { index, offset ->
            val end = offsets.getOrNull(index + 1) ?: payload.size
            if (offset + 4 < end) payload.copyOfRange(offset + 4, end) else null
        }
    }
}
