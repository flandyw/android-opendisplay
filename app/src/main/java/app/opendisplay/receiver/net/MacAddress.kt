package app.opendisplay.receiver.net

import app.opendisplay.receiver.protocol.WireProtocol

/** Where to dial a Mac that is listening for reverse connections. */
data class MacAddress(val host: String, val port: Int) {
    /** The text the user typed, normalised, for showing and saving. */
    val text: String
        get() = when {
            port == WireProtocol.MAC_REVERSE_PORT -> host
            host.contains(':') -> "[$host]:$port"
            else -> "$host:$port"
        }

    companion object {
        /**
         * Reads `host`, `host:port`, `[v6]` or `[v6]:port`, with the reverse
         * port assumed when none is given. Returns null for anything that
         * cannot be dialed (blank, spaces, a bad port).
         */
        fun parse(input: String): MacAddress? {
            val raw = input.trim().removePrefix("tcp://")
            if (raw.isEmpty() || raw.any { it.isWhitespace() || it == '/' }) return null
            val host: String
            val portText: String?
            if (raw.startsWith("[")) {
                val end = raw.indexOf(']')
                if (end < 2) return null
                host = raw.substring(1, end)
                val rest = raw.substring(end + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> return null
                }
            } else {
                val colon = raw.indexOf(':')
                if (colon < 0) {
                    host = raw
                    portText = null
                } else if (raw.indexOf(':', colon + 1) >= 0) {
                    // A bare IPv6 address: no port can be told apart from the digits.
                    host = raw
                    portText = null
                } else {
                    host = raw.substring(0, colon)
                    portText = raw.substring(colon + 1)
                }
            }
            if (host.isEmpty()) return null
            val port = when {
                portText == null -> WireProtocol.MAC_REVERSE_PORT
                else -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            return MacAddress(host, port)
        }
    }
}
