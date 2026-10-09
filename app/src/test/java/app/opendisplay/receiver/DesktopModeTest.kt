package app.opendisplay.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopModeTest {
    @Test
    fun knownNamesResolve() {
        assertEquals(DesktopMode.EXTEND, DesktopMode.fromName("EXTEND"))
        assertEquals(DesktopMode.MIRROR, DesktopMode.fromName("MIRROR"))
    }

    @Test
    fun missingOrUnknownNamesFallBackToExtend() {
        assertEquals(DesktopMode.EXTEND, DesktopMode.fromName(null))
        assertEquals(DesktopMode.EXTEND, DesktopMode.fromName(""))
        assertEquals(DesktopMode.EXTEND, DesktopMode.fromName("mirror"))
    }
}
