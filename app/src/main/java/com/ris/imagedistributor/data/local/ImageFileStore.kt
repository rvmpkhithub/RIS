package com.ris.imagedistributor.data.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
     * Copies the content at [uri] into app-private storage. Returns the relative filename (not a
     * full path).
     *
     * [Review][Patch] runs on [Dispatchers.IO] — the previous version performed blocking stream
     * I/O on whatever dispatcher the caller happened to be on, which for `viewModelScope.launch`
     * defaults to `Dispatchers.Main.immediate` (a real main-thread jank/ANR risk for larger
     * images). Also deletes the partially-written destination file if the copy fails partway
     * through, rather than leaving a truncated, orphaned file on disk.
     */
    suspend fun copyToAppStorage(uri: Uri): String = withContext(Dispatchers.IO) {
        val extension = context.contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "jpg"
        val filename = "${UUID.randomUUID()}.$extension"
        val destination = File(imagesDir, filename)

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open input stream for $uri" }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
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
}
