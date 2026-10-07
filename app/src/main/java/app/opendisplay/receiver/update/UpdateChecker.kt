package app.opendisplay.receiver.update

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Carries the cooldown across restarts and manual retries. */
internal class UpdateHttpException(
    val code: Int, message: String, val retryAtMillis: Long? = null,
) : IOException(message)

data class UpdateDownloadProgress(val percent: Int? = null, val bytesPerSecond: Long = 0, val verifying: Boolean = false)
data class DownloadedUpdate(val update: AppUpdate, val file: File)

/** Talks to the selected [UpdateSource] only; every URL and redirect must stay on its trusted hosts. */
class UpdateChecker(private val context: Context) {
    private val directory get() = File(context.noBackupFilesDir, "updates").apply { mkdirs() }
    private val record get() = AtomicFile(File(directory, "ready.json"))

    /** Keep only the verified, still-newer APK; discard interrupted downloads and installed builds. */
    fun restoreDownload(): DownloadedUpdate? {
        val ready = runCatching {
            val json = JSONObject(record.openRead().bufferedReader().use { it.readText() })
            val update = AppUpdate(json.getLong("versionCode"), json.getString("versionName"),
                json.getString("apkName"), json.getString("apkUrl"), json.getString("checksumUrl"), json.getString("releaseUrl"))
            require(isValidApkName(update.apkName))
            val file = File(directory, "${update.versionCode}-${update.apkName}")
            require(update.versionCode > installedVersionCode() && file.isFile && sha256(file) == json.getString("sha256"))
            DownloadedUpdate(update, file)
        }.getOrNull()
        if (ready == null) record.delete()
        directory.listFiles()?.filter { it != ready?.file && it.name != "ready.json" }?.forEach { it.delete() }
        return ready
    }

    fun discardDownload(ready: DownloadedUpdate) {
        if (ready.file.exists() && !ready.file.delete()) throw IOException("Couldn’t delete the downloaded update")
        record.delete()
    }

    private fun saveDownload(update: AppUpdate, checksum: String) {
        val json = JSONObject().put("versionCode", update.versionCode).put("versionName", update.versionName)
            .put("apkName", update.apkName).put("apkUrl", update.apkUrl).put("checksumUrl", update.checksumUrl)
            .put("releaseUrl", update.releaseUrl).put("sha256", checksum)
        val output = record.startWrite()
        try {
            output.write(json.toString().toByteArray())
            record.finishWrite(output)
        } catch (error: Throwable) {
            record.failWrite(output)
            throw error
        }
    }

    /** Returns a newer build, or null when up to date (or nothing is published yet). */
    fun check(source: UpdateSource = UpdateSource.SERVER): AppUpdate? {
        val release = try {
            JSONObject(getText(if (source == UpdateSource.GITHUB) GITHUB_LATEST_URL else UPDATE_MANIFEST_URL, source))
        } catch (error: UpdateHttpException) {
            if (error.code == 404) return null
            throw error
        }
        return decodeUpdateRelease(release, installedVersionCode(), source)
    }

    /** Downloads to a temporary file, checks SHA-256, then atomically exposes the APK to the UI. */
    fun download(update: AppUpdate, onProgress: (UpdateDownloadProgress) -> Unit = {}): File {
        val directory = directory
        val target = File(directory, "${update.versionCode}-${update.apkName}")
        val temporary = File(directory, "${update.versionCode}-${update.apkName}.part")
        temporary.delete()
        try {
            val expected = checksum(update).lowercase()
            downloadTo(update.apkUrl, update.source, temporary, onProgress)
            onProgress(UpdateDownloadProgress(100, verifying = true))
            if (sha256(temporary) != expected) throw IOException("The downloaded update failed its checksum")
            if (target.exists() && !target.delete()) throw IOException("Couldn't replace the previous update")
            if (!temporary.renameTo(target)) throw IOException("Couldn't prepare the update")
            try { saveDownload(update, expected) } catch (error: Throwable) { target.delete(); throw error }
            directory.listFiles()?.filter { it.extension == "apk" && it != target }?.forEach { it.delete() }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun checksum(update: AppUpdate): String {
        val line = getText(update.checksumUrl, update.source).lineSequence()
            .firstOrNull { it.trim().endsWith("  ${update.apkName}") || it.trim().endsWith(" *${update.apkName}") }
            ?: throw IOException("The release checksum does not name its APK")
        return line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw IOException("The release checksum is invalid")
    }

    private fun downloadTo(url: String, source: UpdateSource, target: File, onProgress: (UpdateDownloadProgress) -> Unit) {
        val connection = open(url, source)
        try {
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read: Int
                    var lastSampleAt = SystemClock.elapsedRealtime()
                    var lastSampleBytes = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - lastSampleAt
                        if (elapsed >= 500) {
                            onProgress(UpdateDownloadProgress(if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else null,
                                (copied - lastSampleBytes) * 1000 / elapsed))
                            lastSampleAt = now
                            lastSampleBytes = copied
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(url: String, source: UpdateSource): String {
        val connection = open(url, source)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, source: UpdateSource): HttpURLConnection {
        require(isTrustedUpdateUrl(url, source)) { "Untrusted update URL" }
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                // Every redirect must stay on the release host.
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", if (source == UpdateSource.GITHUB) "application/vnd.github+json, application/octet-stream" else "application/json")
                setRequestProperty("User-Agent", "OpenDisplay/${installedVersionName()}")
            }
            connection.connect()
            val code = connection.responseCode
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                val next = location?.let { runCatching { URL(URL(current), it).toString() }.getOrNull() }
                if (next != null && isTrustedUpdateUrl(next, source)) {
                    current = next
                    redirects++
                    continue
                }
                throw UpdateHttpException(code, "Update redirect failed (HTTP $code) · try again later")
            }
            if (code in 200..299) return connection
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            connection.disconnect()
            throw UpdateHttpException(code, updateErrorMessage(code), updateRetryAtMillis(code, retryAfter, System.currentTimeMillis()))
        }
    }

    private companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    }

    private fun installedVersionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
