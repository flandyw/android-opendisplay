package app.opendisplay.receiver.ui

import app.opendisplay.receiver.ConnectionMode
import app.opendisplay.receiver.net.ReceiverUiState

internal enum class ConnectionStage {
    STARTING, READY, NO_NETWORK, CONNECTING, OPENING, NEEDS_ATTENTION;

    val busy: Boolean
        get() = this == STARTING || this == CONNECTING || this == OPENING
}

internal fun ReceiverUiState.connectionStage(mode: ConnectionMode): ConnectionStage = when {
    problem != null -> ConnectionStage.NEEDS_ATTENTION
    connected -> ConnectionStage.OPENING
    status.startsWith("Connecting to Mac ") -> ConnectionStage.CONNECTING
    !listening -> ConnectionStage.STARTING
    mode == ConnectionMode.NETWORK && localAddresses.isEmpty() -> ConnectionStage.NO_NETWORK
    else -> ConnectionStage.READY
}
