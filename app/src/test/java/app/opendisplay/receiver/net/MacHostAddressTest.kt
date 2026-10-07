package app.opendisplay.receiver.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacHostAddressTest {
    @Test
    fun findsIPv4WhenBonjourReturnsIPv6First() {
        val addresses = listOf("fe80::1234", "192.168.1.20").map(InetAddress::getByName)
        assertEquals("192.168.1.20", MacHostAddress.pickIPv4(addresses))
    }

    @Test
    fun ignoresLoopbackAndWildcardAddresses() {
        val addresses = listOf("127.0.0.1", "0.0.0.0", "10.0.0.2").map(InetAddress::getByName)
        assertEquals("10.0.0.2", MacHostAddress.pickIPv4(addresses))
    }

    @Test
    fun handlesLegacySingleAddressAndMissingIPv4() {
        assertEquals("10.0.0.2", MacHostAddress.pickIPv4(listOf(InetAddress.getByName("10.0.0.2"))))
        assertNull(MacHostAddress.pickIPv4(listOf(InetAddress.getByName("fe80::1234"))))
        assertNull(MacHostAddress.pickIPv4(emptyList()))
    }
}
