package app.opendisplay.receiver.update

import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** One installable build published on the release server. */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val releaseUrl: String,
)

internal const val RELEASE_HOST = "opendisplay.flandolf.me"
internal const val UPDATE_MANIFEST_URL = "https://$RELEASE_HOST/releases/latest.json"
internal const val RELEASES_PAGE_URL = "https://$RELEASE_HOST/releases/"
/** Only the official release build can install the server's APK; the debug build has another package. */
internal const val RELEASE_PACKAGE = "app.opendisplay.receiver"

private val VERSION_NAME = Regex("\\d+\\.\\d+\\.\\d+(?:-exp\\.[1-9]\\d{0,3})?")
private val APK_NAME = Regex("opendisplay-[A-Za-z0-9._-]+\\.apk")

internal fun isTrustedUpdateUrl(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    return parsed.protocol == "https" && parsed.host.lowercase() == RELEASE_HOST &&
        parsed.userInfo == null && (parsed.port == -1 || parsed.port == 443)
}

internal fun isValidApkName(name: String) = name.matches(APK_NAME)

/**
 * Parses the server's `latest.json` (GitHub-release shaped, plus explicit `version_code` /
 * `version_name`). Returns null when the installed build is already current.
 */
internal fun decodeUpdateRelease(release: JSONObject, installedVersionCode: Long): AppUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val code = release.optLong("version_code", -1L).takeIf { it in 1..2_100_000_000L }
        ?: throw IOException("The latest release has an invalid version")
    val versionName = release.optString("version_name")
    if (!versionName.matches(VERSION_NAME)) throw IOException("The latest release has an invalid version name")
    if (code <= installedVersionCode) return null

    var apkName: String? = null
    var apkUrl: String? = null
    var checksumUrl: String? = null
    release.optJSONArray("assets")?.let { assets ->
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            when {
                name.endsWith(".apk", ignoreCase = true) && name.startsWith("opendisplay-") -> { apkName = name; apkUrl = url }
                name == "SHA256SUMS" -> checksumUrl = url
            }
        }
    }
    val apk = apkUrl ?: throw IOException("The latest release has no APK")
    val sums = checksumUrl ?: throw IOException("The latest release has no checksum")
    val name = apkName ?: throw IOException("The latest release has no APK name")
    require(isValidApkName(name)) { "Invalid update filename" }
    require(isTrustedUpdateUrl(apk) && isTrustedUpdateUrl(sums)) { "Untrusted update URL" }
    return AppUpdate(
        versionCode = code,
        versionName = versionName,
        apkName = name,
        apkUrl = apk,
        checksumUrl = sums,
        releaseUrl = release.optString("html_url").takeIf { isTrustedUpdateUrl(it) } ?: RELEASES_PAGE_URL,
    )
}

internal const val AUTO_UPDATE_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
internal const val UPDATE_FAILURE_RETRY_MILLIS = 60L * 60 * 1000

/** True when the last automatic check is old enough to check again. Manual checks bypass this. */
internal fun shouldAutoUpdateCheck(nowMillis: Long, lastCheckMillis: Long): Boolean {
    if (lastCheckMillis <= 0L) return true
    if (nowMillis < lastCheckMillis) return true // Clock moved backwards: don't block updates.
    return nowMillis - lastCheckMillis >= AUTO_UPDATE_CHECK_INTERVAL_MILLIS
}

/** When to try again after the server (or Cloudflare/Nginx in front of it) pushed back; null otherwise. */
internal fun updateRetryAtMillis(code: Int, retryAfterSeconds: Long?, nowMillis: Long): Long? {
    if (code != 429 && code != 503) return null
    val retry = retryAfterSeconds?.takeIf { it > 0 }?.let { nowMillis + it * 1000 }
    return retry ?: (nowMillis + UPDATE_FAILURE_RETRY_MILLIS)
}

/** User-facing message for a failed request. Never includes response bodies. */
internal fun updateErrorMessage(responseCode: Int): String = when (responseCode) {
    429 -> "Update server is busy · try again later"
    in 500..599 -> "Update server unavailable (HTTP $responseCode) · try again later"
    else -> "Update check failed (HTTP $responseCode) · try again later"
}
