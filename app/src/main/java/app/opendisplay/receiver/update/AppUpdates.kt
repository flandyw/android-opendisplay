package app.opendisplay.receiver.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateState(
    /** False in the debug build, whose package can't be replaced by the release APK. */
    val supported: Boolean = true,
    val initializing: Boolean = true,
    val available: AppUpdate? = null,
    val ready: DownloadedUpdate? = null,
    val checking: Boolean = false,
    val downloading: AppUpdate? = null,
    val progress: UpdateDownloadProgress = UpdateDownloadProgress(),
    val message: String? = null,
) {
    val busy get() = initializing || checking || downloading != null
    val downloadCandidate get() = available?.takeIf { it.versionCode > (ready?.update?.versionCode ?: 0) }
}

/** Process-owned transfers survive Activity recreation; verified downloads also survive process death. */
class AppUpdates(private val context: Context) {
    private val checker = UpdateChecker(context.applicationContext)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val supported = context.packageName == RELEASE_PACKAGE
    private val mutableState = MutableStateFlow(UpdateState(supported = supported, initializing = supported))
    val state = mutableState.asStateFlow()

    private val initialized = scope.async {
        if (!supported) return@async
        try {
            val ready = withContext(Dispatchers.IO) { checker.restoreDownload() }
            mutableState.update { it.copy(ready = ready) }
        } catch (_: Exception) {
            message("Could not restore the downloaded update.")
        } finally {
            mutableState.update { it.copy(initializing = false) }
        }
    }

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply() }

    fun message(value: String) { mutableState.update { it.copy(message = value) } }

    /** [manual] checks bypass the once-a-day throttle but still honor a server-requested cooldown. */
    fun check(manual: Boolean) {
        // Not `busy`: the launch-time check may overlap restoring a downloaded update.
        if (!supported || state.value.checking || state.value.downloading != null) return
        if (!manual && !autoCheck) return
        val now = System.currentTimeMillis()
        val retryAt = prefs.getLong(KEY_RETRY_AT, 0L)
        if (retryAt > now) {
            if (manual) message("Update check paused · try again in ${((retryAt - now) + 59_999) / 60_000} min")
            return
        }
        if (!manual && !shouldAutoUpdateCheck(now, prefs.getLong(KEY_LAST_CHECK, 0L))) return
        mutableState.update { it.copy(checking = true, message = null) }
        scope.launch {
            try {
                awaitReady()
                val result = withContext(Dispatchers.IO) { checker.check() }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).remove(KEY_RETRY_AT).apply()
                mutableState.update { it.copy(available = result, message = if (manual) {
                    when {
                        result == null -> "You’re up to date."
                        result.versionCode <= (it.ready?.update?.versionCode ?: 0) -> "The latest update is already downloaded."
                        else -> null
                    }
                } else null) }
            } catch (error: Exception) {
                val retry = (error as? UpdateHttpException)?.retryAtMillis
                if (retry != null) prefs.edit().putLong(KEY_RETRY_AT, retry).apply()
                else if (!manual) prefs.edit().putLong(KEY_LAST_CHECK,
                    System.currentTimeMillis() - AUTO_UPDATE_CHECK_INTERVAL_MILLIS + UPDATE_FAILURE_RETRY_MILLIS).apply()
                if (manual) message(error.message ?: "Could not check for updates.")
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun download(update: AppUpdate) {
        if (state.value.busy || update.versionCode <= (state.value.ready?.update?.versionCode ?: 0)) return
        mutableState.update { it.copy(downloading = update, progress = UpdateDownloadProgress(), message = null) }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    checker.download(update) { progress -> mutableState.update { it.copy(progress = progress) } }
                }
                mutableState.update { it.copy(ready = DownloadedUpdate(update, file)) }
            } catch (error: Exception) {
                message(error.message ?: "The update could not be downloaded.")
            } finally {
                mutableState.update { it.copy(downloading = null) }
            }
        }
    }

    fun install() {
        val ready = state.value.ready ?: return
        if (state.value.busy) return
        try {
            commitUpdateSession(context, ready.file)
        } catch (error: Exception) {
            message(error.message ?: "The update could not be installed.")
        }
    }

    fun discard() {
        if (state.value.busy) return
        val ready = state.value.ready ?: return
        mutableState.update { it.copy(initializing = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { checker.discardDownload(ready) }
                mutableState.update { it.copy(ready = null, message = "Downloaded update deleted.") }
            } catch (_: Exception) {
                message("Could not delete the downloaded update. Try again.")
            } finally {
                mutableState.update { it.copy(initializing = false) }
            }
        }
    }

    private suspend fun awaitReady() { initialized.await() }

    private companion object {
        const val PREFS = "opendisplay_updates"
        const val KEY_AUTO_CHECK = "autoCheck"
        const val KEY_LAST_CHECK = "lastCheck"
        const val KEY_RETRY_AT = "retryAt"
    }
}
