package app.opendisplay.receiver.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageClipTest {
    /** An "encoder" whose output shrinks with the scale squared, like pixel count. */
    private fun sizeAt(percent: Int, fullSize: Int) = ByteArray((fullSize.toLong() * percent * percent / 10_000).toInt())

    @Test
    fun fullSizeIsKeptWhenItFits() {
        val bytes = ImageClip.fit(1000) { sizeAt(it, 800) }
        assertEquals(800, bytes?.size)
    }

    @Test
    fun scalesDownUntilItFits() {
        val tried = mutableListOf<Int>()
        val bytes = ImageClip.fit(1000) { percent -> tried += percent; sizeAt(percent, 4000) }
        // 4000 * 0.7^2 = 1960, 4000 * 0.5^2 = 1000 -> fits at 50%.
        assertEquals(listOf(100, 70, 50), tried)
        assertEquals(1000, bytes?.size)
    }

    @Test
    fun givesUpWhenNothingFits() {
        assertNull(ImageClip.fit(10) { sizeAt(it, 1_000_000) })
    }

    @Test
    fun skipsScalesTheEncoderCannotProduce() {
        val bytes = ImageClip.fit(1000) { percent -> if (percent == 100) null else sizeAt(percent, 800) }
        assertEquals(800 * 70 * 70 / 10_000, bytes?.size)
    }

    @Test
    fun budgetLeavesRoomForBase64InsideTheMacControlFrame() {
        val base64 = ImageClip.MAX_PNG_BYTES / 3 * 4
        assertTrue(base64 < 1 shl 20)
    }
}
