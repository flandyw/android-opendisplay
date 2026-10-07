package app.opendisplay.receiver.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchAutoConnectTest {
    @Test
    fun disabledLaunchNeverConnects() = runTest {
        var calls = 0
        LaunchAutoConnect(this) { calls++ }.schedule(enabled = false)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun enabledLaunchConnectsOnceAfterThreeSeconds() = runTest {
        var calls = 0
        LaunchAutoConnect(this) { calls++ }.schedule(enabled = true)
        runCurrent()
        advanceTimeBy(2_999)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun turningOffDuringTheDelayCancelsConnection() = runTest {
        var calls = 0
        val launch = LaunchAutoConnect(this) { calls++ }
        launch.schedule(enabled = true)
        advanceTimeBy(1_000)
        launch.schedule(enabled = false)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun manualConnectionOrDisconnectCancelsTheLaunchDial() = runTest {
        var calls = 0
        val launch = LaunchAutoConnect(this) { calls++ }
        launch.schedule(enabled = true)
        advanceTimeBy(2_000)
        launch.cancel()
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun reschedulingCannotCreateMultipleConnections() = runTest {
        var calls = 0
        val launch = LaunchAutoConnect(this) { calls++ }
        launch.schedule(enabled = true)
        advanceTimeBy(2_000)
        launch.schedule(enabled = true)
        runCurrent()
        advanceTimeBy(2_999)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
    }
}
