package com.ris.imagedistributor.data.local

/**
 * INSTRUMENTED — ImageFileStore does real Context-backed file I/O (ContentResolver, filesDir),
 * not meaningfully unit-testable with mocks alone. [Review][Patch]: this story previously shipped
 * with zero coverage for the one class in it doing raw file/stream I/O.
 */
import android.net.Uri
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

@RunWith(AndroidJUnit4::class)
class ImageFileStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fileStore = ImageFileStore(context)

    @Test
    fun copyToAppStorage_copiesTheSourceBytesIntoAppPrivateStorage() = runTest {
        val sourceFile = File(context.cacheDir, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))

        val stored = fileStore.absolutePath(filename)
        assertTrue("expected the copied file to exist", stored.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4), stored.readBytes().toList())
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
    fun delete_removesAPreviouslyCopiedFile() = runTest {
        val sourceFile = File(context.cacheDir, "source2.jpg").apply { writeBytes(byteArrayOf(9)) }
        val filename = fileStore.copyToAppStorage(Uri.fromFile(sourceFile))
        sourceFile.delete()

        fileStore.delete(filename)

        assertFalse("expected the file to be gone after delete()", fileStore.absolutePath(filename).exists())
    }
}
