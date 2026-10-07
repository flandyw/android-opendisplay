package app.opendisplay.receiver.net

/** A Mac-side Disconnect pauses discovery and retry dials until the user connects again. */
internal class ReverseReconnectPolicy {
    private val paused = mutableMapOf<String, Pair<String, Int>>()

    @Synchronized
    fun allow(name: String, host: String, port: Int, userInitiated: Boolean): Boolean {
        if (userInitiated) {
            paused.entries.removeAll { it.key == name || it.value == (host to port) }
        }
        return name !in paused && (host to port) !in paused.values
    }

    @Synchronized
    fun pause(name: String, host: String, port: Int) {
        paused[name] = host to port
    }
}
