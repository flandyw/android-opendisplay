@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.opendisplay.receiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opendisplay.receiver.R
import app.opendisplay.receiver.update.AppUpdate
import app.opendisplay.receiver.update.UpdateState
import java.util.Locale

/** Everything the connection screen needs to show and drive app updates. */
class UpdatesUi(
    val state: UpdateState,
    val autoCheck: Boolean,
    val onAutoCheck: (Boolean) -> Unit,
    val onCheck: () -> Unit,
    val onDownload: (AppUpdate) -> Unit,
    val onInstall: () -> Unit,
    val onDelete: () -> Unit,
) {
    /** A newer build is known (downloaded or not): worth a nudge on the main screen. */
    val hasUpdate get() = state.ready != null || state.downloadCandidate != null
}

@Composable
internal fun UpdatesContent(ui: UpdatesUi) {
    val state = ui.state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.supported) {
            Text(stringResource(R.string.updates_unsupported), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.updates_auto), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = ui.autoCheck, onCheckedChange = ui.onAutoCheck)
        }
        when {
            state.downloading != null -> {
                Text(stringResource(if (state.progress.verifying) R.string.updates_verifying else R.string.updates_downloading, state.downloading.versionName))
                val percent = state.progress.percent
                if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                if (!state.progress.verifying) {
                    val speed = state.progress.bytesPerSecond
                    val rate = if (speed >= 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB/s", speed / (1024f * 1024)) else "${speed / 1024} KB/s"
                    Text("${percent?.let { "$it% · " }.orEmpty()}$rate", style = MaterialTheme.typography.labelMedium)
                }
            }
            state.initializing -> Text(stringResource(R.string.updates_loading))
            state.checking -> Text(stringResource(R.string.updates_checking))
        }
        state.ready?.let { ready ->
            Text(stringResource(R.string.updates_ready, ready.update.versionName))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(ui.onInstall, enabled = !state.busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.updates_install)) }
                TextButton(ui.onDelete, enabled = !state.busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.updates_delete)) }
            }
        }
        state.downloadCandidate?.takeIf { state.downloading == null }?.let { update ->
            Text(stringResource(R.string.updates_available, update.versionName))
            Button({ ui.onDownload(update) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (state.ready != null) R.string.updates_download_newer else R.string.updates_download))
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(ui.onCheck, enabled = !state.checking && state.downloading == null, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.updates_check))
        }
    }
}
