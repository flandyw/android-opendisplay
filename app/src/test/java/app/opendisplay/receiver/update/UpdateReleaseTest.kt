package app.opendisplay.receiver.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateReleaseTest {
    private val base = "https://opendisplay.flandolf.me/releases/v2.5.5-exp.1/"

    private fun manifest(
        code: Long = 25_50_001,
        name: String = "2.5.5-exp.1",
        apkUrl: String = base + "opendisplay-2.5.5-exp.1.apk",
        apkName: String = "opendisplay-2.5.5-exp.1.apk",
        sumsUrl: String = base + "SHA256SUMS",
    ) = JSONObject("""{
        "version_code": $code, "version_name": "$name", "tag_name": "v$name", "html_url": "$base",
        "draft": false, "prerelease": false,
        "assets": [
          {"name": "$apkName", "browser_download_url": "$apkUrl", "size": 10},
          {"name": "SHA256SUMS", "browser_download_url": "$sumsUrl", "size": 10}
        ]}""")

    @Test fun newerBuildIsOffered() {
        val update = decodeUpdateRelease(manifest(code = 2_550_001), installedVersionCode = 2_540_000)!!
        assertEquals(2_550_001L, update.versionCode)
        assertEquals("2.5.5-exp.1", update.versionName)
        assertEquals("opendisplay-2.5.5-exp.1.apk", update.apkName)
        assertEquals(base, update.releaseUrl)
    }

    @Test fun sameOrOlderBuildIsIgnored() {
        assertNull(decodeUpdateRelease(manifest(code = 2_550_001), installedVersionCode = 2_550_001))
        assertNull(decodeUpdateRelease(manifest(code = 2_550_001), installedVersionCode = 3_000_000))
    }

    @Test fun experimentalBuildsSortBelowTheNextCommit() {
        // Commit count 255 -> stable code 2,550,000; exp.N builds of count 255 are above count 254's.
        assertTrue(255L * 10_000 + 1 > 254L * 10_000)
        assertTrue(255L * 10_000 + 9_999 < 256L * 10_000)
    }

    @Test fun rejectsUntrustedHostsAndNames() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeUpdateRelease(manifest(apkUrl = "https://evil.example/opendisplay-2.5.5-exp.1.apk"), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeUpdateRelease(manifest(sumsUrl = "http://opendisplay.flandolf.me/releases/SHA256SUMS"), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeUpdateRelease(manifest(apkName = "opendisplay-../x.apk"), 1)
        }
        assertThrows(IOException::class.java) { decodeUpdateRelease(manifest(name = "2.5"), 1) }
        assertThrows(IOException::class.java) { decodeUpdateRelease(manifest(code = 0), 1) }
        assertThrows(IOException::class.java) { decodeUpdateRelease(JSONObject("""{"version_code": 5, "version_name": "0.0.5"}"""), 1) }
    }

    @Test fun trustedUrls() {
        assertTrue(isTrustedUpdateUrl("https://opendisplay.flandolf.me/releases/latest.json"))
        assertFalse(isTrustedUpdateUrl("https://folio.flandolf.me/releases/latest.json"))
        assertFalse(isTrustedUpdateUrl("https://opendisplay.flandolf.me.evil.example/"))
        assertFalse(isTrustedUpdateUrl("https://user@opendisplay.flandolf.me/"))
        assertFalse(isTrustedUpdateUrl("https://opendisplay.flandolf.me:8443/"))
        assertFalse(isTrustedUpdateUrl("http://opendisplay.flandolf.me/"))
    }

    @Test fun autoCheckThrottle() {
        val day = AUTO_UPDATE_CHECK_INTERVAL_MILLIS
        assertTrue(shouldAutoUpdateCheck(1_000, 0))
        assertFalse(shouldAutoUpdateCheck(day - 1, 1))
        assertTrue(shouldAutoUpdateCheck(day + 1, 1))
        assertTrue(shouldAutoUpdateCheck(5, 10)) // clock moved backwards
    }

    @Test fun serverPushbackSetsACooldown() {
        assertNull(updateRetryAtMillis(404, null, 1_000))
        assertEquals(31_000L, updateRetryAtMillis(429, 30, 1_000))
        assertEquals(1_000 + UPDATE_FAILURE_RETRY_MILLIS, updateRetryAtMillis(503, null, 1_000))
    }
}
