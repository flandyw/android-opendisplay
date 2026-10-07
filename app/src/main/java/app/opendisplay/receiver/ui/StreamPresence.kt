package app.opendisplay.receiver.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * How long the last frame stays on screen after the stream drops. The Mac
 * rebuilds its virtual display on rotation, mirror/extend toggles and Wi‑Fi
 * blips; riding those out keeps the desktop in place instead of flashing the
 * connection screen.
 */
internal const val RECONNECT_GRACE_MS = 5_000L

/** True while streaming, and for [graceMs] after a stream that was up has dropped. */
@Composable
internal fun rememberStreamPresence(streaming: Boolean, graceMs: Long = RECONNECT_GRACE_MS, sessionEnded: Boolean = false): Boolean {
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(streaming, sessionEnded) {
        if (sessionEnded) {
            held = false
        } else if (streaming) {
            held = true
        } else if (held) {
            delay(graceMs)
            held = false
        }
    }
    return !sessionEnded && (streaming || held)
}
