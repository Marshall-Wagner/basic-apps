package dev.montb.basiccamera.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.heifwriter.HeifWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where captures go and how we read them back. Everything lands in DCIM/BasicCamera via
 * the MediaStore, so shots show up in any gallery app and we need no storage permission
 * (scoped storage, API 29+). Kept separate from the UI so the capture wiring stays small.
 */
object CaptureStore {

    /** Sub-folder under DCIM. Stored by MediaStore as "DCIM/BasicCamera/". */
    private const val FOLDER = "DCIM/BasicCamera"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Output options for a still photo saved into the gallery. */
    fun imageOutput(resolver: ContentResolver): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return ImageCapture.OutputFileOptions.Builder(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
    }

    /**
     * Encodes an in-memory captured JPEG to HEIF/HEIC and saves it to the gallery, returning
     * the saved Uri (or null on failure, so the caller can surface an error). HEIC is
     * HEVC-based, so files are roughly half the size of JPEG at similar quality, and the
     * ROG's hardware HEVC encoder makes it fast.
     *
     * Must run off the main thread. [rotationDegrees] is the capture's orientation hint
     * (0/90/180/270), written into the HEIF so viewers display it upright, we don't rotate
     * the (large) bitmap itself. We encode to a temp cache file first because HeifWriter's
     * file-descriptor constructor needs API 30 while our minSdk is 29; the path constructor
     * works on 29, then we copy the bytes into the MediaStore entry.
     */
    fun saveHeic(
        context: Context,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        quality: Int = 92,
    ): Uri? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("Couldn't decode captured image")
        val temp = File.createTempFile("heic_", ".heic", context.cacheDir)
        try {
            HeifWriter.Builder(
                temp.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(quality)
                .setMaxImages(1)
                .setRotation(((rotationDegrees % 360) + 360) % 360)
                .build()
                .apply {
                    start()
                    addBitmap(bitmap)
                    stop(/* timeoutMs = */ 10_000)
                    close()
                }
            bitmap.recycle()

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}.heic")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/heic")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't open output stream for $uri")
            uri
        } finally {
            temp.delete()
        }
    }.getOrNull()

    /**
     * Re-encodes an in-memory captured JPEG to a fresh, **metadata-free** JPEG and saves it to
     * the gallery (returns the Uri, or null on failure). `Bitmap.compress` writes no EXIF at
     * all, no location, timestamp, camera model, or exposure, so this matches HEIC's
     * near-zero metadata for our own shots. A bare JPEG can't carry an orientation tag, so we
     * bake [rotationDegrees] into the pixels to keep the photo upright. Off the main thread.
     */
    fun saveStrippedJpeg(
        context: Context,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        quality: Int = 95,
    ): Uri? = runCatching {
        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("Couldn't decode captured image")
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot != 0) {
            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        } ?: error("Couldn't open output stream for $uri")
        bitmap.recycle()
        uri
    }.getOrNull()

    /**
     * Output options that write straight to a caller-supplied URI, used when another app
     * launches us with ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT and wants the file in its place.
     */
    fun streamOutput(resolver: ContentResolver, target: Uri): ImageCapture.OutputFileOptions {
        val out = resolver.openOutputStream(target)
            ?: error("Can't open output stream for $target")
        return ImageCapture.OutputFileOptions.Builder(out).build()
    }

    /** Output options for a video clip saved into the gallery. */
    fun videoOutput(resolver: ContentResolver): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return MediaStoreOutputOptions
            .Builder(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /** A small bitmap for the gallery-preview button. Safe to call off the main thread. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    }.getOrNull()

    /** Thumbnail of the newest shot we saved, for the preview button on launch. */
    fun latestThumbnail(context: Context): Bitmap? =
        latestImageUri(context)?.let { thumbnail(context, it) }

    /** Newest photo in our folder, or null if none yet. */
    fun latestImageUri(context: Context): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$FOLDER%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
