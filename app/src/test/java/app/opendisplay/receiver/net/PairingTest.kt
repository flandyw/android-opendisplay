package app.opendisplay.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private class MemoryStorage : TokenStorage {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun savedTokenComesBackForThatMacOnly() {
        val tokens = PairTokens(MemoryStorage())
        tokens.save("Studio Mac", "secret")
        assertEquals("secret", tokens.tokenFor("Studio Mac"))
        assertNull(tokens.tokenFor("Other Mac"))
        assertNull(tokens.tokenFor(null))
    }

    @Test
    fun blankOrHugeTokensAreIgnored() {
        val tokens = PairTokens(MemoryStorage())
        tokens.save("Mac", "")
        tokens.save("", "secret")
        tokens.save(null, "secret")
        tokens.save("Mac", "x".repeat(1000))
        assertNull(tokens.tokenFor("Mac"))
    }

    @Test
    fun forgetRemovesIt() {
        val tokens = PairTokens(MemoryStorage())
        tokens.save("Mac", "secret")
        tokens.forget("Mac")
        assertNull(tokens.tokenFor("Mac"))
    }

    @Test
    fun nearbyListReplacesSameNameAndStaysSorted() {
        val a = NearbyMac("b-mac", "10.0.0.2", 9011)
        val b = NearbyMac("A-mac", "10.0.0.3", 9011)
        val list = emptyList<NearbyMac>().withMac(a).withMac(b)
        assertEquals(listOf("A-mac", "b-mac"), list.map { it.name })
        val moved = list.withMac(a.copy(host = "10.0.0.9"))
        assertEquals(2, moved.size)
        assertEquals("10.0.0.9", moved.first { it.name == "b-mac" }.host)
        assertEquals(listOf("A-mac"), moved.withoutMac("b-mac").map { it.name })
    }
}
