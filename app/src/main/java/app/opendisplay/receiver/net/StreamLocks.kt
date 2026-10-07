package app.opendisplay.receiver.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Wi‑Fi power-save batches packets and adds tens of milliseconds of jitter,
 * which shows up as stutter and cursor lag. While a stream is live we hold a
 * low-latency Wi‑Fi lock (API 29+) or the older high-performance lock.
 */
class StreamLocks(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.WifiLock? = null

    @Synchronized
    fun setActive(active: Boolean) {
        if (active) acquire() else release()
    }

    private fun acquire() {
        if (lock?.isHeld == true) return
        val manager = wifi ?: return
        try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            lock = manager.createWifiLock(mode, "OpenDisplay:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock unavailable: ${e.message}")
        }
    }

    private fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        lock = null
    }

    private companion object {
        const val TAG = "StreamLocks"
    }
}
