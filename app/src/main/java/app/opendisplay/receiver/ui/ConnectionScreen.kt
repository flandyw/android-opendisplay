@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.opendisplay.receiver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.opendisplay.receiver.ConnectionMode
import app.opendisplay.receiver.R
import app.opendisplay.receiver.net.ReceiverProblem
import app.opendisplay.receiver.net.ReceiverUiState
import kotlinx.coroutines.launch

private enum class ConnectionSheet { ADDRESS, HELP, USB, UPDATES }

@Composable
fun ConnectionScreen(state: ReceiverUiState, onConnectionMode: (ConnectionMode) -> Unit, updates: UpdatesUi? = null) {
    val context = LocalContext.current
    val mode = ConnectionMode.entries.firstOrNull { it.name == state.connectionMode }
        ?: ConnectionMode.NETWORK
    val stage = state.connectionStage(mode)
    var sheet by rememberSaveable { mutableStateOf<ConnectionSheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsError = stringResource(R.string.connection_settings_unavailable)
    val openSettings: (String) -> Unit = { action ->
        if (!openSystemSettings(context, action)) {
            scope.launch { snackbar.showSnackbar(settingsError) }
        }
    }
    val copyAddress: (String) -> Unit = { address ->
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.connection_address_clip_label), address))
        // Android 13+ supplies its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.connection_address_copied)) }
        }
    }

    // Opaque surfaces cover the live decoder, including during reconnects.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(R.drawable.ic_desktop_windows, modifier = Modifier.size(26.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    FilledTonalIconButton(onClick = { sheet = ConnectionSheet.HELP }, modifier = Modifier.padding(end = 12.dp)) {
                        Symbol(R.drawable.ic_help, description = stringResource(R.string.connection_help))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
        },
        snackbarHost = { if (sheet == null) SnackbarHost(snackbar) },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.5f
            val spacing = if (wide) 32.dp else 20.dp
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 40.dp else 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (wide) {
                    Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                        ConnectionHero(stage, state.problem, wide = true, modifier = Modifier.weight(1f))
                        ConnectionGuide(state, mode, stage, onConnectionMode, { sheet = it }, openSettings, Modifier.weight(1.05f))
                    }
                } else {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                        ConnectionHero(stage, state.problem, wide = false)
                        ConnectionGuide(state, mode, stage, onConnectionMode, { sheet = it }, openSettings)
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { sheet = ConnectionSheet.HELP }) {
                    Text(stringResource(R.string.connection_trouble))
                }
                if (updates != null) {
                    TextButton(onClick = { sheet = ConnectionSheet.UPDATES }) {
                        Text(stringResource(if (updates.hasUpdate) R.string.updates_button_available else R.string.updates_button))
                    }
                }
            }
        }
    }

    sheet?.let { current ->
        val title = stringResource(when (current) {
            ConnectionSheet.ADDRESS -> R.string.connection_manual_title
            ConnectionSheet.UPDATES -> R.string.updates_title
            else -> R.string.connection_help_title
        })
        val sheetMaxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SnackbarHost(snackbar)
            Column(
                Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp)
                    .semantics { paneTitle = title },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmallEmphasized)
                    IconButton(onClick = { sheet = null }) {
                        Symbol(R.drawable.ic_close, description = stringResource(R.string.connection_done))
                    }
                }
                if (current == ConnectionSheet.ADDRESS) {
                    AddressContent(state, openSettings, copyAddress)
                } else if (current == ConnectionSheet.UPDATES && updates != null) {
                    UpdatesContent(updates)
                } else {
                    HelpContent(state, initialTopic = if (current == ConnectionSheet.USB) "usb" else null, openSettings, copyAddress)
                }
                TextButton(onClick = { sheet = null }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.connection_done))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ConnectionHero(stage: ConnectionStage, problem: ReceiverProblem?, wide: Boolean, modifier: Modifier = Modifier) {
    val attention = stage == ConnectionStage.NEEDS_ATTENTION
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    Surface(
        modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomEnd = 36.dp, bottomStart = 12.dp),
        color = if (attention) colors.errorContainer else colors.primaryContainer,
        contentColor = if (attention) colors.onErrorContainer else colors.onPrimaryContainer,
    ) {
        Column(Modifier.padding(if (wide) 36.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = if (attention) colors.error else colors.primary) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Symbol(if (attention) R.drawable.ic_info else R.drawable.ic_desktop_windows, modifier = Modifier.size(28.dp))
                    }
                }
                Text(
                    stringResource(stage.label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (stage.busy) LoadingIndicator(Modifier.size(32.dp), color = colors.onPrimaryContainer)
            }
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) togetherWith
                        fadeOut(motion.fastEffectsSpec())).using(SizeTransform(clip = false))
                },
                label = "Connection readiness",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(when (current) {
                            ConnectionStage.OPENING -> R.string.connection_opening_headline
                            ConnectionStage.CONNECTING, ConnectionStage.STARTING -> R.string.connection_busy_headline
                            ConnectionStage.NEEDS_ATTENTION -> if (problem == ReceiverProblem.UPDATE_REQUIRED) R.string.connection_update_headline else R.string.connection_attention_headline
                            else -> R.string.connection_headline
                        }),
                        style = if (wide) MaterialTheme.typography.displayMediumEmphasized else MaterialTheme.typography.displaySmallEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(descriptionFor(current, problem)), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!stage.busy && !attention) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(R.drawable.ic_check, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.connection_automatic), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ConnectionGuide(
    state: ReceiverUiState,
    mode: ConnectionMode,
    stage: ConnectionStage,
    onConnectionMode: (ConnectionMode) -> Unit,
    onSheet: (ConnectionSheet) -> Unit,
    openSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.connected && stage != ConnectionStage.NEEDS_ATTENTION) {
                LoadingIndicator(Modifier.size(56.dp))
                Text(stringResource(R.string.connection_sending_desktop), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(stringResource(R.string.connection_sending_desktop_body), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.connection_permission_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.connection_method), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                ConnectionModeButtons(mode, onConnectionMode)
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) togetherWith
                            fadeOut(motion.fastEffectsSpec())).using(SizeTransform(clip = false))
                    },
                    label = "Connection instructions",
                ) { selected ->
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        GuideStep(1,
                            if (selected == ConnectionMode.NETWORK) R.string.connection_wifi_step_title else R.string.connection_usb_step_title,
                            if (selected == ConnectionMode.NETWORK) R.string.connection_wifi_step_body else R.string.connection_usb_step_body)
                        GuideStep(2, R.string.connection_mac_step_title,
                            if (selected == ConnectionMode.NETWORK) R.string.connection_mac_step_body else R.string.connection_usb_mac_step_body)
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Symbol(R.drawable.ic_desktop_windows, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.connection_choose_device), style = MaterialTheme.typography.labelSmall)
                            // Wrap long model names; the full advertised name is needed on the Mac.
                            Text(state.serviceName, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                when {
                    stage == ConnectionStage.NO_NETWORK -> ExpressiveAction(R.string.connection_wifi_settings, R.drawable.ic_wifi) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
                    stage == ConnectionStage.NEEDS_ATTENTION -> ExpressiveAction(R.string.connection_trouble, R.drawable.ic_help) { onSheet(ConnectionSheet.HELP) }
                    mode == ConnectionMode.USB -> ExpressiveAction(R.string.connection_usb_help, R.drawable.ic_usb, primary = false) { onSheet(ConnectionSheet.USB) }
                    else -> ExpressiveAction(R.string.connection_manual, R.drawable.ic_arrow_forward, primary = false) { onSheet(ConnectionSheet.ADDRESS) }
                }
                if (mode == ConnectionMode.USB || stage == ConnectionStage.NEEDS_ATTENTION || stage == ConnectionStage.NO_NETWORK) {
                    TextButton(onClick = { onSheet(ConnectionSheet.ADDRESS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connection_manual))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeButtons(selectedMode: ConnectionMode, onSelect: (ConnectionMode) -> Unit) {
    // The connected group defaults supply asymmetric ends and shape morphing on press/selection.
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ConnectionMode.entries.forEachIndexed { index, mode ->
            val active = mode == selectedMode
            val label = stringResource(if (mode == ConnectionMode.NETWORK) R.string.connection_wifi else R.string.connection_usb)
            ToggleButton(
                checked = active,
                onCheckedChange = { if (!active) onSelect(mode) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                    role = Role.RadioButton
                    selected = active
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Symbol(if (mode == ConnectionMode.NETWORK) R.drawable.ic_wifi else R.drawable.ic_usb, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, @StringRes title: Int, @StringRes body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(number.toString(), style = MaterialTheme.typography.labelLarge) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExpressiveAction(@StringRes label: Int, @DrawableRes icon: Int, primary: Boolean = true, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(12.dp))
        Symbol(icon)
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight)
    val shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight)
    val padding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight)
    if (primary) Button(onClick, shapes, modifier, contentPadding = padding, content = content)
    else FilledTonalButton(onClick, shapes, modifier, contentPadding = padding, content = content)
}

@Composable
private fun AddressContent(state: ReceiverUiState, openSettings: (String) -> Unit, onCopy: (String) -> Unit) {
    Text(stringResource(R.string.connection_manual_body), style = MaterialTheme.typography.bodyLarge)
    if (state.localAddresses.isEmpty()) {
        Text(stringResource(R.string.connection_no_addresses), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExpressiveAction(R.string.connection_wifi_settings, R.drawable.ic_wifi) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
    } else {
        state.localAddresses.distinct().forEach { ip ->
            val address = "$ip:${state.port}"
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ip, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        Text(stringResource(R.string.connection_port, state.port), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = { onCopy(address) }) { Symbol(R.drawable.ic_content_copy, description = stringResource(R.string.connection_copy_address, address)) }
                }
            }
        }
    }
}

@Composable
private fun HelpContent(state: ReceiverUiState, initialTopic: String?, openSettings: (String) -> Unit, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable(initialTopic) { mutableStateOf(initialTopic) }
    Text(stringResource(R.string.connection_help_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
    data class Topic(val key: String, @param:StringRes val title: Int, @param:StringRes val body: Int, @param:DrawableRes val icon: Int)
    val topics = listOf(
        Topic("network", R.string.connection_help_network, R.string.connection_help_network_body, R.drawable.ic_wifi),
        Topic("vpn", R.string.connection_help_vpn, R.string.connection_help_vpn_body, R.drawable.ic_settings),
        Topic("usb", R.string.connection_help_usb, R.string.connection_help_usb_body, R.drawable.ic_usb),
        Topic("tether", R.string.connection_help_tether, R.string.connection_help_tether_body, R.drawable.ic_usb),
        Topic("gestures", R.string.connection_help_gestures, R.string.connection_help_gestures_body, R.drawable.ic_touch_app),
        Topic("device", R.string.connection_help_device, 0, R.drawable.ic_info),
    )
    topics.forEach { topic ->
        val isExpanded = expanded == topic.key
        val expansionDescription = stringResource(if (isExpanded) R.string.connection_expanded else R.string.connection_collapsed)
        Column {
            Surface(
                onClick = { expanded = if (isExpanded) null else topic.key },
                shape = RoundedCornerShape(16.dp),
                color = if (isExpanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionDescription },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(topic.icon)
                    Text(stringResource(topic.title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Symbol(if (isExpanded) R.drawable.ic_close else R.drawable.ic_expand_more)
                }
            }
            AnimatedVisibility(isExpanded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (topic.key == "device") {
                        Detail(R.string.connection_device_name, state.serviceName)
                        Detail(R.string.connection_device_status, state.status)
                        Detail(R.string.connection_device_port, state.port.toString())
                        if (state.deviceSummary.isNotBlank()) Detail(R.string.connection_device_info, state.deviceSummary)
                    } else {
                        Text(stringResource(topic.body), style = MaterialTheme.typography.bodyLarge)
                        when (topic.key) {
                            "vpn" -> ExpressiveAction(R.string.connection_vpn_settings, R.drawable.ic_settings) { openSettings(Settings.ACTION_VPN_SETTINGS) }
                            "network" -> AddressContent(state, openSettings, onCopy)
                            "usb", "tether" -> ExpressiveAction(R.string.connection_system_settings, R.drawable.ic_settings) { openSettings(Settings.ACTION_SETTINGS) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(@StringRes label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Symbol(@DrawableRes drawable: Int, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(drawable), description, modifier.size(24.dp))
}

private val ConnectionStage.label: Int
    get() = when (this) {
        ConnectionStage.STARTING -> R.string.connection_starting
        ConnectionStage.READY -> R.string.connection_ready
        ConnectionStage.NO_NETWORK -> R.string.connection_no_network
        ConnectionStage.CONNECTING -> R.string.connection_connecting
        ConnectionStage.OPENING -> R.string.connection_opening
        ConnectionStage.NEEDS_ATTENTION -> R.string.connection_attention
    }

@StringRes
private fun descriptionFor(stage: ConnectionStage, problem: ReceiverProblem?): Int = when (stage) {
    ConnectionStage.STARTING -> R.string.connection_starting_description
    ConnectionStage.CONNECTING -> R.string.connection_connecting_description
    ConnectionStage.OPENING -> R.string.connection_opening_description
    ConnectionStage.NO_NETWORK -> R.string.connection_no_network_description
    ConnectionStage.NEEDS_ATTENTION -> when (problem) {
        ReceiverProblem.LISTENER -> R.string.connection_problem_listener
        ReceiverProblem.UPDATE_REQUIRED -> R.string.connection_problem_update
        else -> R.string.connection_problem_unreachable
    }
    ConnectionStage.READY -> R.string.connection_description
}

private fun openSystemSettings(context: Context, action: String): Boolean {
    for (candidate in listOf(action, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS).distinct()) {
        try {
            context.startActivity(Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            // A device may omit a specific settings screen; use the next available one.
        } catch (_: SecurityException) {
            // Restricted profiles may prevent access to a specific settings activity.
        }
    }
    return false
}

@Preview(name = "Phone · light", widthDp = 412, heightDp = 915)
@Composable
private fun PhoneConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel 9", localAddresses = listOf("192.168.1.24")), {})
    }
}

@Preview(name = "Tablet · dark", widthDp = 1280, heightDp = 800)
@Composable
private fun TabletConnectionPreview() {
    OpenDisplayTheme(darkTheme = true, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel Tablet", localAddresses = listOf("192.168.1.24")), {})
    }
}

@Preview(name = "Large text · USB", widthDp = 412, heightDp = 915, fontScale = 1.5f)
@Preview(name = "Largest text · USB", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun AccessibleConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel Tablet", connectionMode = "USB"), {})
    }
}

@Preview(name = "Offline · phone", widthDp = 360, heightDp = 800)
@Composable
private fun OfflineConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel 9"), {})
    }
}

@Preview(name = "Connected · tablet", widthDp = 1280, heightDp = 800)
@Composable
private fun ConnectedConnectionPreview() {
    OpenDisplayTheme(darkTheme = true, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, connected = true, serviceName = "Pixel Tablet"), {})
    }
}

@Preview(name = "Update needed · phone", widthDp = 412, heightDp = 915)
@Composable
private fun UpdateConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, connected = true, serviceName = "Pixel 9", problem = ReceiverProblem.UPDATE_REQUIRED), {})
    }
}
