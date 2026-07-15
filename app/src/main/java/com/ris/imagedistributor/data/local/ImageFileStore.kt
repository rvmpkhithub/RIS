package com.ris.imagedistributor.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The only class that touches context.filesDir for images. [AD-14]
 *
 * The picked Uri's read grant is temporary (Photo Picker) — copy() must be called immediately
 * when the Uri is received, never deferred to a later app session.
 */
class ImageFileStore(private val context: Context) {

    private val imagesDir: File by lazy {
        File(context.filesDir, "images").apply { mkdirs() }
    }

    /**
     * Copies the content at [uri] into app-private storage, downscaling to at most
     * [MAX_DIMENSION_PX] on the longer side and re-encoding as JPEG at [JPEG_QUALITY] — keeps a
     * typical phone photo in the low hundreds of KB instead of several MB, so a delivery attempt
     * (email attachment, WhatsApp media upload) stays practical. Returns the relative filename
     * (not a full path); always `.jpg` now, since every stored image is re-encoded regardless of
     * its original format.
     *
     * EXIF orientation is read and baked into the output pixels before compression — decoding via
     * [BitmapFactory] does not auto-rotate, and without this step a photo taken in portrait (most
     * phone cameras store portrait shots as landscape pixels + an orientation tag) would come out
     * sideways once re-encoded, since the orientation tag itself isn't carried over.
     *
     * [Review][Patch] runs on [Dispatchers.IO] — the previous version performed blocking stream
     * I/O on whatever dispatcher the caller happened to be on, which for `viewModelScope.launch`
     * defaults to `Dispatchers.Main.immediate` (a real main-thread jank/ANR risk for larger
     * images). Also deletes the partially-written destination file if the copy fails partway
     * through, rather than leaving a truncated, orphaned file on disk.
     */
    suspend fun copyToAppStorage(uri: Uri): String = withContext(Dispatchers.IO) {
        val filename = "${UUID.randomUUID()}.jpg"
        val destination = File(imagesDir, filename)

        try {
            val decoded = decodeDownscaled(uri)
            val oriented = applyExifRotation(uri, decoded)
            destination.outputStream().use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            if (oriented !== decoded) decoded.recycle()
            oriented.recycle()
        } catch (e: Exception) {
            destination.delete()
            throw e
        }

        filename
    }

    /** Absolute path for a stored image, for the UI to load (Coil, etc.). */
    fun absolutePath(filePath: String): File = File(imagesDir, filePath)

    /** [Review][Patch] lets the repository clean up a copied file whose DB insert then failed. */
    fun delete(filePath: String) {
        File(imagesDir, filePath).delete()
    }

    /**
     * Decodes [uri] at (at most) [MAX_DIMENSION_PX] on the longer side. Uses [BitmapFactory]'s
     * `inSampleSize` first — a cheap power-of-two downscale performed during decode itself,
     * avoiding ever holding the full-resolution bitmap in memory — then a final
     * [Bitmap.createScaledBitmap] pass to land exactly on the target size (sampling alone only
     * gets within a factor of 2).
     */
    private fun decodeDownscaled(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open input stream for $uri" }
            BitmapFactory.decodeStream(input, null, bounds)
        }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_DIMENSION_PX && bounds.outHeight / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }

        val sampled = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open input stream for $uri" }
            requireNotNull(BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })) {
                "Could not decode image for $uri"
            }
        }

        val longerSide = maxOf(sampled.width, sampled.height)
        if (longerSide <= MAX_DIMENSION_PX) return sampled

        val scale = MAX_DIMENSION_PX.toFloat() / longerSide
        val scaled = Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * scale).roundToInt().coerceAtLeast(1),
            (sampled.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri).use { input ->
            input?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val MAX_DIMENSION_PX = 1920
        private const val JPEG_QUALITY = 80
    }
}
