package app.opendisplay.receiver.clip

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Clipboard images, both ways. The wire carries PNG inside one control frame,
 * so anything bigger than [MAX_PNG_BYTES] is scaled down until it fits.
 *
 * Android only lets a clip carry an image as a content URI, so an image from
 * the Mac is written to the app cache and served through a [FileProvider].
 */
object ImageClip {
    /** PNG bytes before base64 (which adds a third) must fit the Mac's 1 MiB control frame. */
    const val MAX_PNG_BYTES = 600_000

    /** Longest edge we ever decode; a screenshot's pixels beyond this are never needed. */
    private const val MAX_EDGE = 4096

    private const val DIR = "clip"
    private const val FILE = "mac-clip.png"

    /** Scale steps tried in order, as percent of the decoded size. */
    internal val SCALE_STEPS = intArrayOf(100, 70, 50, 35, 25, 18, 12)

    /**
     * Encodes with the largest scale in [steps] whose result is at most
     * [maxBytes]. [encode] gets the scale as a percent and may return null when
     * it cannot produce an image at that size.
     */
    internal fun fit(
        maxBytes: Int,
        steps: IntArray = SCALE_STEPS,
        encode: (percent: Int) -> ByteArray?,
    ): ByteArray? {
        for (percent in steps) {
            val bytes = encode(percent) ?: continue
            if (bytes.size <= maxBytes) return bytes
        }
        return null
    }

    /** Whether [clip] holds an image we can send. */
    fun imageUri(resolver: ContentResolver, clip: ClipData?): Uri? {
        if (clip == null || clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val type = resolver.getType(uri) ?: clip.description.getMimeType(0)
        return uri.takeIf { type?.startsWith("image/") == true }
    }

    /** The image at [uri] as PNG within [MAX_PNG_BYTES], or null if it cannot be read or shrunk enough. */
    fun encodeForWire(resolver: ContentResolver, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return try {
            fit(MAX_PNG_BYTES) { percent -> pngBytes(decoded, percent) }
        } finally {
            decoded.recycle()
        }
    }

    private fun pngBytes(source: Bitmap, percent: Int): ByteArray? {
        val width = source.width * percent / 100
        val height = source.height * percent / 100
        if (width < 16 || height < 16) return null
        val scaled = if (percent == 100) source else Bitmap.createScaledBitmap(source, width, height, true)
        return try {
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** Stores an image from the Mac and returns the content URI to put on the clipboard. */
    fun store(context: Context, png: ByteArray): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE)
        file.writeBytes(png)
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** True if [uri] is one [store] produced, so our own clipboard writes are not echoed back. */
    fun isOurs(context: Context, uri: Uri?): Boolean = uri?.authority == authority(context)

    fun authority(context: Context) = "${context.packageName}.clipimage"
}
