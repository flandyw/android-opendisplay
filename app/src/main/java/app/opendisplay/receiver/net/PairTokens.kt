package app.opendisplay.receiver.net

/** Where tokens live. A tiny seam so the logic can be tested without Android. */
interface TokenStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The secrets Macs hand out when the user allows this device ("pairing").
 * Presenting one on later reverse connections lets the Mac skip its prompt.
 *
 * Tokens are filed under what we dial: the Mac's Bonjour name when it was
 * found automatically, or the typed address.
 */
class PairTokens(private val storage: TokenStorage) {
    fun tokenFor(mac: String?): String? = mac?.let { storage.get(KEY_PREFIX + it) }

    fun save(mac: String?, token: String) {
        if (mac.isNullOrEmpty() || token.isEmpty() || token.length > MAX_TOKEN_CHARS) return
        storage.put(KEY_PREFIX + mac, token)
    }

    fun forget(mac: String) = storage.remove(KEY_PREFIX + mac)

    private companion object {
        const val KEY_PREFIX = "pair."
        const val MAX_TOKEN_CHARS = 256
    }
}
