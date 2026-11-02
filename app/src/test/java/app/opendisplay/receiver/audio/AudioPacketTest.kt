package app.opendisplay.receiver.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AudioPacketTest {
    private fun packet(channels: Int = 1, rate: Int = 48000, frames: Int = 2, body: Int = 4): ByteArray =
        ByteBuffer.allocate(12 + body).put("AUD1".toByteArray()).put(1.toByte()).put(channels.toByte())
            .putShort(rate.toShort()).putInt(frames).array()

    @Test fun mono() { assertEquals(AudioPacket(48000, 1, 4), AudioPacket.parse(packet())) }
    @Test fun stereo() { assertEquals(AudioPacket(48000, 2, 8), AudioPacket.parse(packet(channels = 2, body = 8))) }
    @Test fun truncatedHeaders() { for (size in 0..11) assertNull(AudioPacket.parse(ByteArray(size))) }
    @Test fun badMagic() { assertNull(AudioPacket.parse(packet().apply { this[0] = 0 })) }
    @Test fun unknownVersion() { assertNull(AudioPacket.parse(packet().apply { this[4] = 2 })) }
    @Test fun zeroChannels() { assertNull(AudioPacket.parse(packet(channels = 0))) }
    @Test fun unsupportedChannels() { assertNull(AudioPacket.parse(packet(channels = 3))) }
    @Test fun unsignedChannels() { assertNull(AudioPacket.parse(packet(channels = 255))) }
    @Test fun zeroRate() { assertNull(AudioPacket.parse(packet(rate = 0))) }
    @Test fun tooLowRate() { assertNull(AudioPacket.parse(packet(rate = 7999))) }
    @Test fun minimumRate() { assertNotNull(AudioPacket.parse(packet(rate = 8000))) }
    @Test fun unsignedRate() { assertEquals(65535, AudioPacket.parse(packet(rate = 65535))!!.sampleRate) }
    @Test fun zeroFrames() { assertNull(AudioPacket.parse(packet(frames = 0))) }
    @Test fun unsignedFrameCount() { assertNull(AudioPacket.parse(packet(frames = -1))) }
    @Test fun multiplicationOverflow() { assertNull(AudioPacket.parse(packet(channels = 2, frames = 0x40000001))) }
    @Test fun truncatedBody() { assertNull(AudioPacket.parse(packet(body = 3))) }
    @Test fun trailingBytes() { assertNull(AudioPacket.parse(packet(body = 5))) }
}
