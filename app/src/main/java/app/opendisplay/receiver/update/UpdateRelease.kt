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
    val source: UpdateSource = UpdateSource.SERVER,
)

/** Where updates come from: the project's release server, or the GitHub Releases of its repository. */
enum class UpdateSource { SERVER, GITHUB }

internal const val RELEASE_HOST = "opendisplay.flandolf.me"
internal const val UPDATE_MANIFEST_URL = "https://$RELEASE_HOST/releases/latest.json"
internal const val RELEASES_PAGE_URL = "https://$RELEASE_HOST/releases/"
internal const val GITHUB_REPO = "flandyw/android-opendisplay"
internal const val GITHUB_LATEST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
internal const val GITHUB_RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases"
/** Release assets redirect from github.com to one of these CDN hosts. */
private val GITHUB_CDN_HOSTS = setOf(
    "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
)
/** Only the official release build can install the server's APK; the debug build has another package. */
internal const val RELEASE_PACKAGE = "app.opendisplay.receiver"

private val VERSION_NAME = Regex("\\d+\\.\\d+\\.\\d+(?:-exp\\.[1-9]\\d{0,3})?")
private val APK_NAME = Regex("(?i)opendisplay-[A-Za-z0-9._-]+\\.apk")

internal fun isTrustedUpdateUrl(url: String, source: UpdateSource = UpdateSource.SERVER): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    if (parsed.protocol != "https" || parsed.userInfo != null || (parsed.port != -1 && parsed.port != 443)) return false
    val host = parsed.host.lowercase()
    return when (source) {
        UpdateSource.SERVER -> host == RELEASE_HOST
        UpdateSource.GITHUB -> when (host) {
            "github.com" -> parsed.path.startsWith("/$GITHUB_REPO/")
            "api.github.com" -> parsed.path.startsWith("/repos/$GITHUB_REPO/")
            else -> host in GITHUB_CDN_HOSTS
        }
    }
}

internal fun isValidApkName(name: String) = name.matches(APK_NAME)

/**
 * Parses the server's `latest.json` (GitHub-release shaped, plus explicit `version_code` /
 * `version_name`). Returns null when the installed build is already current.
 */
internal fun decodeUpdateRelease(
    release: JSONObject, installedVersionCode: Long, source: UpdateSource = UpdateSource.SERVER,
): AppUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    // GitHub releases carry only the tag (vX.Y.Z = commit count in base 10); the code is count * 10000.
    val tagVersion = if (source == UpdateSource.GITHUB) {
        Regex("v(\\d+)\\.(\\d)\\.(\\d)").matchEntire(release.optString("tag_name"))?.destructured
            ?.let { (x, y, z) -> x.toLong() * 100 + y.toLong() * 10 + z.toLong() }
            ?: throw IOException("The latest release has an invalid version name")
    } else null
    val code = (if (tagVersion != null) tagVersion * 10_000 else release.optLong("version_code", -1L))
        .takeIf { it in 1..2_100_000_000L } ?: throw IOException("The latest release has an invalid version")
    val versionName = if (tagVersion != null) release.optString("tag_name").removePrefix("v") else release.optString("version_name")
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
                name.endsWith(".apk", ignoreCase = true) && name.startsWith("opendisplay-", ignoreCase = true) -> { apkName = name; apkUrl = url }
                name == "SHA256SUMS" -> checksumUrl = url
            }
        }
    }
    val apk = apkUrl ?: throw IOException("The latest release has no APK")
    val sums = checksumUrl ?: throw IOException("The latest release has no checksum")
    val name = apkName ?: throw IOException("The latest release has no APK name")
    require(isValidApkName(name)) { "Invalid update filename" }
    require(isTrustedUpdateUrl(apk, source) && isTrustedUpdateUrl(sums, source)) { "Untrusted update URL" }
    return AppUpdate(
        versionCode = code,
        versionName = versionName,
        apkName = name,
        apkUrl = apk,
        checksumUrl = sums,
        releaseUrl = release.optString("html_url").takeIf { isTrustedUpdateUrl(it, source) }
            ?: if (source == UpdateSource.GITHUB) GITHUB_RELEASES_PAGE_URL else RELEASES_PAGE_URL,
        source = source,
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
    403 -> "Update server refused the request · try again later"
    429 -> "Update server is busy · try again later"
    in 500..599 -> "Update server unavailable (HTTP $responseCode) · try again later"
    else -> "Update check failed (HTTP $responseCode) · try again later"
}
