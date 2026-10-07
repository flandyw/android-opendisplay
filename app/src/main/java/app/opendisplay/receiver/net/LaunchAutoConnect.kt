package app.opendisplay.receiver.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One optional launch dial; discovery never starts its own connections. */
internal class LaunchAutoConnect(
    private val scope: CoroutineScope,
    private val connect: () -> Unit,
) {
    private var pending: Job? = null

    fun schedule(enabled: Boolean) {
        cancel()
        if (!enabled) return
        pending = scope.launch {
            delay(3_000)
            connect()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
