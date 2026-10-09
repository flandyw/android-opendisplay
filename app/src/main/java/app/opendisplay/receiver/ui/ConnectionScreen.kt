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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.opendisplay.receiver.net.MacAddress
import app.opendisplay.receiver.net.NearbyMac
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
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
import app.opendisplay.receiver.DesktopMode
import app.opendisplay.receiver.R
import app.opendisplay.receiver.net.ReceiverProblem
import app.opendisplay.receiver.net.ReceiverUiState
import kotlinx.coroutines.launch

private enum class ConnectionSheet { ADDRESS, HELP, USB, UPDATES }

@Composable
fun ConnectionScreen(
    state: ReceiverUiState,
    onConnectionMode: (ConnectionMode) -> Unit,
    onDesktopMode: (DesktopMode) -> Unit,
    updates: UpdatesUi? = null,
    /** Dial a Mac that listens for reverse connections; [lastMacAddress] pre-fills the field. */
    onConnectMac: ((MacAddress) -> Unit)? = null,
    lastMacAddress: String = "",
    /** Macs found on the network; each is one tap to connect. */
    nearbyMacs: List<NearbyMac> = emptyList(),
    onConnectNearby: (NearbyMac) -> Unit = {},
    autoConnectEnabled: Boolean = false,
    onAutoConnect: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mode = ConnectionMode.entries.firstOrNull { it.name == state.connectionMode }
        ?: ConnectionMode.NETWORK
    val desktopMode = DesktopMode.fromName(state.desktopMode)
    val stage = state.connectionStage(mode)
    var sheet by rememberSaveable { mutableStateOf<ConnectionSheet?>(null) }
    var askingMac by rememberSaveable { mutableStateOf(false) }
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

    // Keep the live decoder covered until the desktop is ready.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Symbol(R.drawable.ic_desktop_windows)
                            }
                        }
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (updates != null) {
                        IconButton(onClick = { sheet = ConnectionSheet.UPDATES }) {
                            Symbol(R.drawable.ic_sidebar_square_arrow_down, description = stringResource(
                                if (updates.hasUpdate) R.string.updates_button_available else R.string.updates_button))
                        }
                    }
                    IconButton(onClick = { sheet = ConnectionSheet.HELP }, modifier = Modifier.padding(end = 12.dp)) {
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
            val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.35f
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 40.dp else 24.dp, vertical = if (wide) 32.dp else 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val introduction: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 24.dp)) {
                        ConnectionHero(stage, state.problem, wide)
                        if (wide) DeviceIdentity(state, autoConnectEnabled, onAutoConnect) { sheet = ConnectionSheet.ADDRESS }
                    }
                }
                val connection: @Composable () -> Unit = {
                    ConnectionPanel(state, mode, desktopMode, stage, nearbyMacs, onConnectNearby,
                        onConnectionMode, onDesktopMode, onConnectMac?.let { { askingMac = true } },
                        { sheet = it }, openSettings,
                        wide = wide, updatesEnabled = updates != null)
                }
                if (wide) {
                    Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(0.9f)) { introduction() }
                        Box(Modifier.weight(1.2f)) { connection() }
                    }
                } else {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        introduction()
                        connection()
                        DeviceIdentity(state, autoConnectEnabled, onAutoConnect) { sheet = ConnectionSheet.ADDRESS }
                    }
                }
                Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth().padding(top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { sheet = ConnectionSheet.HELP }) {
                        Text(stringResource(R.string.connection_trouble))
                    }
                    if (updates?.hasUpdate == true) {
                        FilledTonalButton(onClick = { sheet = ConnectionSheet.UPDATES }) {
                            Text(stringResource(R.string.updates_button_available))
                        }
                    }
                }
            }
        }
    }

    if (askingMac && onConnectMac != null) {
        MacAddressDialog(
            initial = lastMacAddress,
            onDismiss = { askingMac = false },
            onConnect = { address ->
                askingMac = false
                onConnectMac(address)
            },
        )
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

/** Discovery is the main action; manual connection remains available as a fallback. */
@Composable
private fun NearbyMacs(
    macs: List<NearbyMac>,
    onConnect: (NearbyMac) -> Unit,
    onEnterAddress: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (macs.isEmpty()) R.string.connection_search_title else R.string.connection_nearby_title),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (macs.isEmpty()) LoadingIndicator(Modifier.size(28.dp))
        }
        if (macs.isEmpty()) {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Symbol(R.drawable.ic_desktop_windows) }
                    }
                    Text(stringResource(R.string.connection_nearby_empty), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        } else {
            Text(stringResource(R.string.connection_nearby_body), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
            macs.forEach { mac ->
                Surface(onClick = { onConnect(mac) }, shape = RoundedCornerShape(20.dp),
                    color = colors.surface,
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryContainer) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Symbol(R.drawable.ic_desktop_windows) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(mac.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.connection_mac_network), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                        Symbol(R.drawable.ic_arrow_forward, description = stringResource(R.string.connection_mac_connect))
                    }
                }
            }
        }
        if (onEnterAddress != null) {
            if (macs.isEmpty()) {
                FilledTonalButton(onClick = onEnterAddress, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.connection_mac_button))
                }
            } else {
                TextButton(onClick = onEnterAddress, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connection_mac_button))
                }
            }
        }
    }
}

/** Asks for the address of a Mac to dial. A dialog, not a sheet: it needs the keyboard. */
@Composable
private fun MacAddressDialog(initial: String, onDismiss: () -> Unit, onConnect: (MacAddress) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val submit = {
        val address = MacAddress.parse(text)
        if (address == null) showError = true else onConnect(address)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection_mac_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.connection_mac_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; showError = false },
                    label = { Text(stringResource(R.string.connection_mac_label)) },
                    placeholder = { Text(stringResource(R.string.connection_mac_hint)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.connection_mac_error)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.connection_mac_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ConnectionHero(stage: ConnectionStage, problem: ReceiverProblem?, wide: Boolean) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val attention = stage == ConnectionStage.NEEDS_ATTENTION
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp)) {
        Surface(shape = RoundedCornerShape(50),
            color = if (attention) colors.errorContainer else colors.surfaceContainerHigh,
            contentColor = if (attention) colors.onErrorContainer else colors.onSurfaceVariant) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stage.busy) LoadingIndicator(Modifier.size(20.dp)) else {
                    Box(Modifier.size(7.dp).background(
                        if (attention) colors.error else if (stage == ConnectionStage.READY) colors.tertiary else colors.onSurfaceVariant,
                        CircleShape))
                }
                Text(stringResource(stage.label), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        AnimatedContent(targetState = stage,
            transitionSpec = { (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false)) }, label = "Connection introduction") { current ->
            Text(stringResource(when (current) {
                ConnectionStage.OPENING -> R.string.connection_opening_headline
                ConnectionStage.NEEDS_ATTENTION -> if (problem == ReceiverProblem.UPDATE_REQUIRED)
                    R.string.connection_update_headline else R.string.connection_attention_headline
                else -> R.string.connection_headline
            }), style = if (wide) MaterialTheme.typography.displayMediumEmphasized else MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.connection_description), style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(max = 400.dp))
    }
}

@Composable
private fun DeviceIdentity(
    state: ReceiverUiState,
    autoConnectEnabled: Boolean,
    onAutoConnect: ((Boolean) -> Unit)?,
    onAddress: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(R.drawable.ic_touch_app)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.connection_choose_device), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Keep the full advertised name readable for the Mac's device picker.
                    Text(state.serviceName, style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onClick = onAddress, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.connection_device_address_action))
                Spacer(Modifier.size(8.dp))
                Symbol(R.drawable.ic_arrow_forward, modifier = Modifier.size(18.dp))
            }
            if (onAutoConnect != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().toggleable(autoConnectEnabled, role = Role.Switch, onValueChange = onAutoConnect)
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.auto_connect_last_device), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.auto_connect_delay), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoConnectEnabled, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: ReceiverUiState,
    mode: ConnectionMode,
    desktopMode: DesktopMode,
    stage: ConnectionStage,
    macs: List<NearbyMac>,
    onConnect: (NearbyMac) -> Unit,
    onConnectionMode: (ConnectionMode) -> Unit,
    onDesktopMode: (DesktopMode) -> Unit,
    onEnterAddress: (() -> Unit)?,
    onSheet: (ConnectionSheet) -> Unit,
    openSettings: (String) -> Unit,
    wide: Boolean,
    updatesEnabled: Boolean,
) {
    Surface(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(if (wide) 28.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.connection_panel_title), style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() })
            if ((!state.connected || stage == ConnectionStage.NEEDS_ATTENTION) && stage != ConnectionStage.CONNECTING) {
                ConnectionModeButtons(mode, onConnectionMode)
                DesktopModeButtons(desktopMode, onDesktopMode)
            }
            when {
                stage.busy -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LoadingIndicator(Modifier.size(48.dp))
                        Text(stringResource(if (stage == ConnectionStage.OPENING) R.string.connection_sending_desktop else stage.label),
                            style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(descriptionFor(stage, state.problem)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (stage == ConnectionStage.OPENING) {
                            Text(stringResource(R.string.connection_permission_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    if (stage == ConnectionStage.NEEDS_ATTENTION) {
                        val showUpdates = state.problem == ReceiverProblem.UPDATE_REQUIRED && updatesEnabled
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(descriptionFor(stage, state.problem)), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onSheet(if (showUpdates) ConnectionSheet.UPDATES else ConnectionSheet.HELP) }) {
                                    Text(stringResource(if (showUpdates) R.string.updates_button else R.string.connection_trouble),
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                    if (mode == ConnectionMode.NETWORK && state.problem != ReceiverProblem.UPDATE_REQUIRED) {
                        if (stage == ConnectionStage.NO_NETWORK) {
                            Text(stringResource(R.string.connection_no_network_description), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ExpressiveAction(R.string.connection_wifi_settings, R.drawable.ic_wifi) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
                            if (onEnterAddress != null) TextButton(onClick = onEnterAddress) {
                                Text(stringResource(R.string.connection_mac_button))
                            }
                        } else {
                            NearbyMacs(macs, onConnect, onEnterAddress)
                        }
                        ConnectionSetup(onSheet)
                    } else if (mode == ConnectionMode.USB && state.problem != ReceiverProblem.UPDATE_REQUIRED) {
                        GuideStep(1, R.string.connection_usb_step_title, R.string.connection_usb_step_body)
                        GuideStep(2, R.string.connection_mac_step_title, R.string.connection_usb_mac_step_body)
                        ExpressiveAction(R.string.connection_usb_help, R.drawable.ic_usb, primary = false) { onSheet(ConnectionSheet.USB) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionSetup(onSheet: (ConnectionSheet) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stateLabel = stringResource(if (expanded) R.string.connection_expanded else R.string.connection_collapsed)
    Column {
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().semantics { stateDescription = stateLabel }) {
            Row(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(R.drawable.ic_info, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.connection_setup_from_mac), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                Symbol(if (expanded) R.drawable.ic_close else R.drawable.ic_expand_more, modifier = Modifier.size(20.dp))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GuideStep(1, R.string.connection_wifi_step_title, R.string.connection_wifi_step_body)
                GuideStep(2, R.string.connection_mac_step_title, R.string.connection_mac_step_body)
                TextButton(onClick = { onSheet(ConnectionSheet.ADDRESS) }) {
                    Text(stringResource(R.string.connection_manual))
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeButtons(selectedMode: ConnectionMode, onSelect: (ConnectionMode) -> Unit) {
    SegmentedChoice(
        options = ConnectionMode.entries,
        selectedOption = selectedMode,
        onSelect = onSelect,
        label = { stringResource(if (it == ConnectionMode.NETWORK) R.string.connection_wifi else R.string.connection_usb) },
        icon = { if (it == ConnectionMode.NETWORK) R.drawable.ic_wifi else R.drawable.ic_usb },
    )
}

/** Extend or mirror, chosen before connecting. The sidebar still changes it mid-session. */
@Composable
private fun DesktopModeButtons(selectedMode: DesktopMode, onSelect: (DesktopMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedChoice(
            options = DesktopMode.entries,
            selectedOption = selectedMode,
            onSelect = onSelect,
            label = { stringResource(if (it == DesktopMode.MIRROR) R.string.connection_desktop_mirror else R.string.connection_desktop_extend) },
            icon = { null },
        )
        Text(stringResource(if (selectedMode == DesktopMode.MIRROR) R.string.connection_desktop_mirror_body else R.string.connection_desktop_extend_body),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

/** A row of connected toggle buttons where exactly one option is checked. */
@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selectedOption: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    icon: (T) -> Int?,
) {
    // The connected group defaults supply asymmetric ends and shape morphing on press/selection.
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            val active = option == selectedOption
            ToggleButton(
                checked = active,
                onCheckedChange = { if (!active) onSelect(option) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                    role = Role.RadioButton
                    selected = active
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                val drawable = icon(option)
                if (drawable != null) {
                    Symbol(drawable, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                }
                Text(label(option))
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
        ConnectionScreen(
            ReceiverUiState(listening = true, serviceName = "Pixel 9", localAddresses = listOf("192.168.1.24")), {}, {},
            onConnectMac = {}, onAutoConnect = {},
            nearbyMacs = listOf(NearbyMac("MacBook Pro", "192.168.1.10", 9011)),
        )
    }
}

@Preview(name = "Tablet · dark", widthDp = 1280, heightDp = 800)
@Composable
private fun TabletConnectionPreview() {
    OpenDisplayTheme(darkTheme = true, dynamicColor = false) {
        ConnectionScreen(
            ReceiverUiState(listening = true, serviceName = "OnePlus Pad", localAddresses = listOf("192.168.1.24")), {}, {},
            onConnectMac = {}, onAutoConnect = {},
            nearbyMacs = listOf(NearbyMac("MacBook Pro", "192.168.1.10", 9011), NearbyMac("Mac mini", "192.168.1.11", 9011)),
        )
    }
}

@Preview(name = "Tablet · searching", widthDp = 1024, heightDp = 768)
@Composable
private fun SearchingConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(
            ReceiverUiState(listening = true, serviceName = "OnePlus Pad", localAddresses = listOf("192.168.1.24")), {}, {},
            onConnectMac = {}, onAutoConnect = {},
        )
    }
}

@Preview(name = "Tablet · large text", widthDp = 1024, heightDp = 768, fontScale = 1.5f)
@Composable
private fun LargeTextTabletPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(
            ReceiverUiState(listening = true, serviceName = "OnePlus Pad with a long device name", localAddresses = listOf("192.168.1.24")), {}, {},
            onConnectMac = {}, onAutoConnect = {},
        )
    }
}

@Preview(name = "Large text · USB", widthDp = 412, heightDp = 915, fontScale = 1.5f)
@Preview(name = "Largest text · USB", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun AccessibleConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel Tablet", connectionMode = "USB"), {}, {})
    }
}

@Preview(name = "Offline · phone", widthDp = 360, heightDp = 800)
@Composable
private fun OfflineConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, serviceName = "Pixel 9"), {}, {})
    }
}

@Preview(name = "Connected · tablet", widthDp = 1280, heightDp = 800)
@Composable
private fun ConnectedConnectionPreview() {
    OpenDisplayTheme(darkTheme = true, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, connected = true, serviceName = "Pixel Tablet"), {}, {})
    }
}

@Preview(name = "Update needed · phone", widthDp = 412, heightDp = 915)
@Composable
private fun UpdateConnectionPreview() {
    OpenDisplayTheme(darkTheme = false, dynamicColor = false) {
        ConnectionScreen(ReceiverUiState(listening = true, connected = true, serviceName = "Pixel 9", problem = ReceiverProblem.UPDATE_REQUIRED), {}, {})
    }
}
