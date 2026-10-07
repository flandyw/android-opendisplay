package app.opendisplay.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseReconnectPolicyTest {
    private val policy = ReverseReconnectPolicy()

    @Test
    fun temporaryDisconnectStillAllowsAutomaticReconnect() {
        assertTrue(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
        assertTrue(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
    }

    @Test
    fun deliberateDisconnectBlocksRetriesAndRediscoveryIncludingAddressChanges() {
        policy.pause("MacBook", "10.0.0.2", 9011)
        repeat(10) {
            assertFalse(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
        }
        assertFalse(policy.allow("MacBook", "10.0.0.9", 9011, userInitiated = false))
        assertFalse(policy.allow("10.0.0.2:9011", "10.0.0.2", 9011, userInitiated = false))
        assertTrue(policy.allow("Other Mac", "10.0.0.3", 9011, userInitiated = false))
    }

    @Test
    fun explicitConnectResumesTheMacAndSubsequentNetworkRecovery() {
        policy.pause("MacBook", "10.0.0.2", 9011)
        assertTrue(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = true))
        assertTrue(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
        policy.pause("MacBook", "10.0.0.2", 9011)
        assertFalse(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
    }

    @Test
    fun manualConnectAfterAddressChangeClearsThePreviousAddressToo() {
        policy.pause("MacBook", "10.0.0.2", 9011)
        assertTrue(policy.allow("MacBook", "10.0.0.9", 9011, userInitiated = true))
        assertTrue(policy.allow("MacBook", "10.0.0.2", 9011, userInitiated = false))
    }
}
