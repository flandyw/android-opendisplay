package app.opendisplay.receiver.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TextForwarderTest {
    private val log = StringBuilder()
    private val forwarder = TextForwarder(
        type = { log.append(it) },
        backspace = { log.append("<$it>") },
    )

    @Test
    fun growingCompositionTypesOnlyTheNewTail() {
        forwarder.setComposing("h")
        forwarder.setComposing("he")
        forwarder.setComposing("hel")
        assertEquals("hel", log.toString())
    }

    @Test
    fun correctionBackspacesOverTheChangedSuffix() {
        forwarder.setComposing("helo")
        forwarder.commit("hello")
        // "helo" -> "hello": keep "hel", erase "o", type "lo".
        assertEquals("helo<1>lo", log.toString())
    }

    @Test
    fun commitEndsCompositionSoNextWordStartsFresh() {
        forwarder.commit("hi")
        forwarder.setComposing("yo")
        assertEquals("hiyo", log.toString())
    }

    @Test
    fun deletingTrimsTheComposingRegionToo() {
        forwarder.setComposing("abc")
        forwarder.deleteBefore(1)
        forwarder.setComposing("ab")
        assertEquals("abc<1>", log.toString())
    }

    @Test
    fun emojiCountsAsOneBackspace() {
        forwarder.setComposing("a😀")
        forwarder.setComposing("a")
        assertEquals("a😀<1>", log.toString())
    }

    @Test
    fun rewritingAnEmojiNeverSplitsItsSurrogates() {
        forwarder.setComposing("😀")
        forwarder.setComposing("😁")
        assertEquals("😀<1>😁", log.toString())
    }
}
