package app.opendisplay.receiver.net

import java.net.Inet4Address
import java.net.InetAddress

internal object MacHostAddress {
    fun pickIPv4(addresses: List<InetAddress>): String? = addresses
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        ?.hostAddress
}
