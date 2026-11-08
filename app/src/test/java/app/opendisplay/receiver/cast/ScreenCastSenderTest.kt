package app.opendisplay.receiver.cast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCastSenderTest {
    @Test
    fun pingGetsPongEchoingT() {
        val pong = ScreenCastSender.controlReply(JSONObject("""{"type":"ping","t":1234.5}"""), 99L)!!
        assertEquals("pong", pong.getString("type"))
        assertEquals(1234.5, pong.getDouble("t"), 0.0)
        assertEquals(99L, pong.getLong("mt"))
    }

    @Test
    fun otherMessagesNeedNoReply() {
        assertNull(ScreenCastSender.controlReply(JSONObject("""{"type":"touch","x":0.5}"""), 0L))
    }

    @Test
    fun streamSizeFitsLongSideAndBlocks() {
        assertEquals(864 to 1920, ScreenCastSender.streamSize(1080, 2400))
        assertEquals(720 to 1280, ScreenCastSender.streamSize(720, 1280))
        assertEquals(1920 to 1072, ScreenCastSender.streamSize(2560, 1440))
    }
}
