package app.opendisplay.receiver.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/**
 * The stylus's own shortcut gesture (double tap on a pencil's barrel). The
 * vendor event is only a trigger; this app turns it into one action: swap the
 * pen and the eraser.
 *
 * One adapter per vendor keeps the rest of the app free of vendor APIs. An
 * adapter must tolerate being started on hardware that lacks it.
 */
interface StylusShortcutAdapter {
    fun start(onShortcut: () -> Unit)
    fun stop()
}

/** The OnePlus / OPPO Pencil double tap, delivered as a vendor broadcast. */
class OplusStylusShortcutAdapter(private val context: Context) : StylusShortcutAdapter {
    private var receiver: BroadcastReceiver? = null

    override fun start(onShortcut: () -> Unit) {
        if (receiver != null) return
        val callback = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_DOUBLE_CLICK) onShortcut()
            }
        }
        val filter = IntentFilter(ACTION_DOUBLE_CLICK)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Exported on purpose: the broadcast comes from the vendor's IPE manager process.
                context.registerReceiver(callback, filter, DOUBLE_CLICK_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(callback, filter, DOUBLE_CLICK_PERMISSION, null)
            }
            receiver = callback
        } catch (_: SecurityException) {
            // Firmware without the vendor permission just has no double-tap source.
        }
    }

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        const val ACTION_DOUBLE_CLICK = "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK"
        const val DOUBLE_CLICK_PERMISSION = "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK"
    }
}

/**
 * Fans every adapter into one callback and drops duplicates, so one physical
 * double tap changes the tool once even if firmware reports it twice.
 */
class StylusShortcutManager(context: Context) {
    private val adapters: List<StylusShortcutAdapter> = listOf(OplusStylusShortcutAdapter(context.applicationContext))
    private var listener: (() -> Unit)? = null
    private var started = false
    private var lastEvent = 0L

    fun start(listener: () -> Unit) {
        this.listener = listener
        if (started) return
        started = true
        adapters.forEach { it.start(::dispatch) }
    }

    fun stop() {
        listener = null
        if (!started) return
        started = false
        adapters.forEach { it.stop() }
    }

    private fun dispatch() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEvent < DEBOUNCE_MS) return
        lastEvent = now
        listener?.invoke()
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
