package app.opendisplay.receiver.ui

import app.opendisplay.receiver.ConnectionMode
import app.opendisplay.receiver.net.ReceiverProblem
import app.opendisplay.receiver.net.ReceiverUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStageTest {
    private val listening = ReceiverUiState(listening = true, status = "Waiting for Mac…")

    @Test
    fun offlineDeviceOffersNetworkSetupButCanStillUseUsb() {
        assertEquals(ConnectionStage.NO_NETWORK, listening.connectionStage(ConnectionMode.NETWORK))
        assertEquals(ConnectionStage.READY, listening.connectionStage(ConnectionMode.USB))
    }

    @Test
    fun networkAddressDoesNotImplyListenerIsReady() {
        val starting = ReceiverUiState(localAddresses = listOf("192.168.1.24"))
        assertEquals(ConnectionStage.STARTING, starting.connectionStage(ConnectionMode.NETWORK))
        assertEquals(ConnectionStage.READY, starting.copy(listening = true).connectionStage(ConnectionMode.NETWORK))
    }

    @Test
    fun reverseConnectionShowsProgressRatherThanOfflineGuidance() {
        val reverse = listening.copy(status = "Connecting to Mac 127.0.0.1:9011…")
        assertEquals(ConnectionStage.CONNECTING, reverse.connectionStage(ConnectionMode.NETWORK))
        assertTrue(reverse.connectionStage(ConnectionMode.NETWORK).busy)
    }

    @Test
    fun connectedMacShowsDesktopHandoffEvenWithoutLanAddress() {
        val connected = listening.copy(connected = true, status = "Connected — sending hello")
        assertEquals(ConnectionStage.OPENING, connected.connectionStage(ConnectionMode.NETWORK))
        assertEquals(ConnectionStage.OPENING, connected.connectionStage(ConnectionMode.USB))
    }

    @Test
    fun protocolMismatchIsVisibleEvenWhenMacIsConnected() {
        // The Mac can supply arbitrary update copy: the UI must use the typed problem.
        val mismatch = listening.copy(connected = true, status = "Please install a compatible release", problem = ReceiverProblem.UPDATE_REQUIRED)
        assertEquals(ConnectionStage.NEEDS_ATTENTION, mismatch.connectionStage(ConnectionMode.NETWORK))
        assertFalse(mismatch.connectionStage(ConnectionMode.NETWORK).busy)
    }

    @Test
    fun failedListenerAndReverseDialNeverClaimReadiness() {
        for (problem in listOf(ReceiverProblem.LISTENER, ReceiverProblem.UNREACHABLE)) {
            val failure = listening.copy(localAddresses = listOf("192.168.1.24"), problem = problem)
            assertEquals(ConnectionStage.NEEDS_ATTENTION, failure.connectionStage(ConnectionMode.NETWORK))
            assertEquals(ConnectionStage.NEEDS_ATTENTION, failure.connectionStage(ConnectionMode.USB))
        }
    }

    @Test
    fun reconnectReturnsToReadyAfterOldProblemIsCleared() {
        val failed = listening.copy(problem = ReceiverProblem.UNREACHABLE, localAddresses = listOf("192.168.1.24"))
        val recovered = failed.copy(problem = null)
        assertEquals(ConnectionStage.READY, recovered.connectionStage(ConnectionMode.NETWORK))
        assertFalse(recovered.connectionStage(ConnectionMode.NETWORK).busy)
    }
}
