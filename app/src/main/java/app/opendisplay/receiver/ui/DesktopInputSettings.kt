package app.opendisplay.receiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opendisplay.receiver.R
import app.opendisplay.receiver.input.ControlsUi

@Composable
fun DesktopInputSettings(
    ui: ControlsUi,
    keyEnabled: Boolean,
    onControlIsCommand: (Boolean) -> Unit,
    onReverseScroll: (Boolean) -> Unit,
    onPointerSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speedLabel = stringResource(R.string.desktop_pointer_speed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.desktop_input_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.desktop_input_help))
                SettingSwitch(stringResource(R.string.desktop_ctrl_command), ui.controlIsCommand, keyEnabled, onControlIsCommand)
                Text(stringResource(R.string.desktop_ctrl_command_help), style = MaterialTheme.typography.bodySmall)
                SettingSwitch(stringResource(R.string.desktop_reverse_scroll), ui.reverseScroll, true, onReverseScroll)
                Text(stringResource(R.string.desktop_speed_value, (ui.pointerSpeed * 100).toInt()))
                Slider(value = ui.pointerSpeed, onValueChange = onPointerSpeed, valueRange = 0.5f..2f,
                    steps = 5, modifier = Modifier.semantics { contentDescription = speedLabel })
                Text(stringResource(R.string.desktop_capture_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connection_done)) } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
