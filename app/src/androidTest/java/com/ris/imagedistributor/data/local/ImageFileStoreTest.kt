package com.ris.imagedistributor.data.local

/**
 * INSTRUMENTED — ImageFileStore does real Context-backed file I/O (ContentResolver, filesDir) and
 * real Bitmap decode/compress, not meaningfully unit-testable with mocks alone. [Review][Patch]:
 * this story previously shipped with zero coverage for the one class in it doing raw file/stream
 * I/O; extended for the resize/re-encode/EXIF-rotation behavior added on top of that copy.
 */
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val MAX_DIMENSION_PX = 1920

@RunWith(AndroidJUnit4::class)
class ImageFileStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fileStore = ImageFileStore(context)

    private fun createTestBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        // A distinct marker in the top-left quadrant only, so a rotation changes which quadrant
        // it lands in — used by the EXIF-orientation test to confirm pixels actually rotated.
        canvas.drawRect(0f, 0f, width / 4f, height / 4f, Paint().apply { color = Color.BLUE })
        return bitmap
    }

    private fun writeBitmapAsJpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun copyToAppStorage_producesADecodableJpegNoLargerThanTheMaxDimension() = runTest {
        val sourceFile = File(context.cacheDir, "source.jpg")
        writeBitmapAsJpeg(createTestBitmap(3000, 1500), sourceFile)

        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))

        assertTrue("expected the stored filename to always be .jpg now", filename.endsWith(".jpg"))
        val stored = fileStore.absolutePath(filename)
        assertTrue("expected the copied file to exist", stored.exists())
        val decoded = requireNotNull(BitmapFactory.decodeFile(stored.path)) { "expected a decodable JPEG" }
        assertTrue(
            "expected longer side <= $MAX_DIMENSION_PX, was ${maxOf(decoded.width, decoded.height)}",
            maxOf(decoded.width, decoded.height) <= MAX_DIMENSION_PX,
        )
        // 3000x1500 downscaled to fit 1920 on the longer side -> 1920x960, aspect ratio preserved.
        assertEquals(1920, decoded.width)
        assertEquals(960, decoded.height)
        decoded.recycle()

        sourceFile.delete()
        stored.delete()
    }

    @Test
    fun copyToAppStorage_leavesSmallerThanMaxImagesUnscaled() = runTest {
        val sourceFile = File(context.cacheDir, "small.jpg")
        writeBitmapAsJpeg(createTestBitmap(400, 300), sourceFile)

        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))

        val stored = fileStore.absolutePath(filename)
        val decoded = requireNotNull(BitmapFactory.decodeFile(stored.path))
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)
        decoded.recycle()

        sourceFile.delete()
        stored.delete()
    }

    @Test
    fun copyToAppStorage_appliesExifRotationBeforeStoring() = runTest {
        // A landscape-pixel source (200x100) tagged as needing a 90-degree rotation should come
        // out portrait (100x200) once stored — proves the EXIF tag was baked into the pixels
        // rather than lost (BitmapFactory doesn't auto-rotate on decode).
        val sourceFile = File(context.cacheDir, "rotated.jpg")
        writeBitmapAsJpeg(createTestBitmap(200, 100), sourceFile)
        ExifInterface(sourceFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))

        val stored = fileStore.absolutePath(filename)
        val decoded = requireNotNull(BitmapFactory.decodeFile(stored.path))
        assertEquals("expected width/height swapped after a 90-degree EXIF rotation", 100, decoded.width)
        assertEquals(200, decoded.height)
        decoded.recycle()

        sourceFile.delete()
        stored.delete()
    }

    @Test
    fun copyToAppStorage_leavesNoOrphanedFileWhenTheSourceCannotBeOpened() = runTest {
        // A file:// Uri pointing at a nonexistent path — ContentResolver.openInputStream throws
        // FileNotFoundException for this, exercising the same catch-delete-rethrow path a
        // revoked Photo Picker grant or a mid-copy I/O failure would.
        val unopenableUri = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))
        val imagesDirBefore = File(context.filesDir, "images").listFiles()?.size ?: 0

        try {
            fileStore.copyToAppStorage(unopenableUri)
            fail("expected copyToAppStorage to throw for an unopenable source")
        } catch (e: Exception) {
            // expected
        }

        val imagesDirAfter = File(context.filesDir, "images").listFiles()?.size ?: 0
        assertEquals("expected no file left behind after a failed copy", imagesDirBefore, imagesDirAfter)
    }

    @Test
    fun copyToAppStorage_leavesNoOrphanedFileWhenTheSourceIsNotADecodableImage() = runTest {
        val garbageFile = File(context.cacheDir, "garbage.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val imagesDirBefore = File(context.filesDir, "images").listFiles()?.size ?: 0

        try {
            fileStore.copyToAppStorage(Uri.fromFile(garbageFile))
            fail("expected copyToAppStorage to throw for undecodable source bytes")
        } catch (e: Exception) {
            // expected
        }

        val imagesDirAfter = File(context.filesDir, "images").listFiles()?.size ?: 0
        assertEquals("expected no file left behind after a failed decode", imagesDirBefore, imagesDirAfter)
        garbageFile.delete()
    }

    @Test
    fun delete_removesAPreviouslyCopiedFile() = runTest {
        val sourceFile = File(context.cacheDir, "source2.jpg")
        writeBitmapAsJpeg(createTestBitmap(100, 100), sourceFile)
        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))
        sourceFile.delete()

        fileStore.delete(filename)

        assertFalse("expected the file to be gone after delete()", fileStore.absolutePath(filename).exists())
    }
}
