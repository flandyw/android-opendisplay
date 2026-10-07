package app.opendisplay.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacAddressTest {
    @Test
    fun bareHostUsesTheReversePort() {
        assertEquals(MacAddress("192.168.1.20", 9011), MacAddress.parse(" 192.168.1.20 "))
        assertEquals(MacAddress("Macs-MacBook.local", 9011), MacAddress.parse("Macs-MacBook.local"))
    }

    @Test
    fun explicitPortIsKept() {
        assertEquals(MacAddress("10.0.0.5", 9500), MacAddress.parse("10.0.0.5:9500"))
        assertEquals(MacAddress("10.0.0.5", 9500), MacAddress.parse("tcp://10.0.0.5:9500"))
    }

    @Test
    fun ipv6NeedsBracketsForAPort() {
        assertEquals(MacAddress("fe80::1", 9011), MacAddress.parse("fe80::1"))
        assertEquals(MacAddress("fe80::1", 9011), MacAddress.parse("[fe80::1]"))
        assertEquals(MacAddress("fe80::1", 9100), MacAddress.parse("[fe80::1]:9100"))
    }

    @Test
    fun rejectsWhatCannotBeDialed() {
        assertNull(MacAddress.parse(""))
        assertNull(MacAddress.parse("   "))
        assertNull(MacAddress.parse("my mac"))
        assertNull(MacAddress.parse("10.0.0.5:"))
        assertNull(MacAddress.parse("10.0.0.5:0"))
        assertNull(MacAddress.parse("10.0.0.5:70000"))
        assertNull(MacAddress.parse("10.0.0.5:abc"))
        assertNull(MacAddress.parse(":9011"))
        assertNull(MacAddress.parse("[]"))
        assertNull(MacAddress.parse("[::1]x"))
        assertNull(MacAddress.parse("http://10.0.0.5"))
    }

    @Test
    fun textRoundTrips() {
        for (s in listOf("10.0.0.5", "10.0.0.5:9500", "[fe80::1]:9100", "host.local")) {
            val parsed = MacAddress.parse(s)!!
            assertEquals(parsed, MacAddress.parse(parsed.text))
        }
    }
}
