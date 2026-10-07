package app.opendisplay.receiver.net

/** A Mac found on the network that is willing to take reverse connections. */
data class NearbyMac(val name: String, val host: String, val port: Int)

/** The list of [macs] with [mac] added, replacing an older entry of the same name. */
fun List<NearbyMac>.withMac(mac: NearbyMac): List<NearbyMac> =
    (filterNot { it.name == mac.name } + mac).sortedBy { it.name.lowercase() }

fun List<NearbyMac>.withoutMac(name: String): List<NearbyMac> = filterNot { it.name == name }
