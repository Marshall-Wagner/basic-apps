package dev.montb.basiccontacts.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache

/**
 * Tiny image helpers so we don't pull in an image-loading library (Coil/Glide) just
 * for contact photos. Decoding is downsampled to keep memory small. Call off the
 * main thread.
 */
object Images {

    // Cache decoded list-thumbnail bitmaps by URI so a fast scroll doesn't re-decode the
    // same photo over and over (the main scroll-jank cause). Small cap, thumbnails only.
    private val thumbCache = object : LruCache<String, Bitmap>(64) { }

    /** Drop cached thumbnails (call after edits so a changed photo isn't stale). */
    fun clearThumbCache() = thumbCache.evictAll()

    /** Decode a content/file URI (e.g. a contact PHOTO_URI) to a Bitmap, or null.
     *  When [useCache] is set, decoded thumbnails are memoized by URI. */
    fun decodeUri(
        context: Context,
        uri: String,
        maxSize: Int = 256,
        useCache: Boolean = false
    ): Bitmap? {
        if (useCache) thumbCache.get(uri)?.let { return it }
        val bmp = runCatching {
            Uri.parse(uri).let { parsed ->
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    decodeBytes(input.readBytes(), maxSize)
                }
            }
        }.getOrNull()
        if (useCache && bmp != null) thumbCache.put(uri, bmp)
        return bmp
    }

    /** Decode raw bytes (e.g. a freshly picked photo) to a downsampled Bitmap. */
    fun decodeBytes(bytes: ByteArray, maxSize: Int = 256): Bitmap? {
        // First pass: read bounds only, compute sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxSize && h / 2 >= maxSize) {
            w /= 2; h /= 2; sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Read a picked photo URI and re-encode as a reasonably sized JPEG to store on the
     * contact (the provider keeps photos small; a multi-MB camera shot is wasteful).
     */
    fun readAsContactJpeg(context: Context, uri: Uri, maxSize: Int = 512): ByteArray? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val bmp = decodeBytes(bytes, maxSize) ?: return null
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }.getOrNull()
}
