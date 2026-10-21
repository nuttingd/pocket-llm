package dev.nutting.pocketllm.domain.image

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 10 * HOUR
    private val filesDir by lazy { tmp.newFolder("files") }
    private val store by lazy { ImageStore(filesDir, clock = { now }) }

    @Test
    fun `given bytes when written then the image appears at the returned path and no temp file remains`() {
        val bytes = byteArrayOf(1, 2, 3)

        val path = store.write("conv-1", "msg-1", 0, bytes, "png")

        assertEquals("images/conv-1/msg-1_0.png", path)
        assertArrayEquals(bytes, store.file(path).readBytes())
        assertEquals(listOf("msg-1_0.png"), File(filesDir, "images/conv-1").list()!!.toList())
    }

    @Test
    fun `given an image is saved again when written then it is replaced, so saving a result is idempotent`() {
        store.write("conv-1", "msg-1", 0, byteArrayOf(1), "png")

        val path = store.write("conv-1", "msg-1", 0, byteArrayOf(9, 9), "png")

        assertArrayEquals(byteArrayOf(9, 9), store.file(path).readBytes())
    }

    @Test
    fun `given the write fails when saving then no partial image or temp file is left behind`() {
        // A directory where the temp file should go makes opening the stream fail
        File(filesDir, "images/conv-1/.msg-1_0.png.tmp").mkdirs()

        val result = runCatching { store.write("conv-1", "msg-1", 0, byteArrayOf(1, 2, 3), "png") }

        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(File(filesDir, "images/conv-1/msg-1_0.png").exists())
        assertFalse(File(filesDir, "images/conv-1/.msg-1_0.png.tmp").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given an id that would escape the images folder when writing then it is rejected`() {
        store.write("../conv", "msg-1", 0, byteArrayOf(1), "png")
    }

    @Test
    fun `given a conversation is deleted then all of its images are removed`() {
        store.write("conv-1", "msg-1", 0, byteArrayOf(1), "png")
        store.write("conv-1", "msg-2", 0, byteArrayOf(1), "png")
        val other = store.write("conv-2", "msg-3", 0, byteArrayOf(1), "png")

        store.deleteConversation("conv-1")

        assertFalse(File(filesDir, "images/conv-1").exists())
        assertTrue(store.file(other).exists())
    }

    @Test
    fun `given unreferenced old files when swept then they are deleted while referenced and recent files are kept`() {
        val referenced = store.write("conv-1", "msg-1", 0, byteArrayOf(1), "png")
        val orphan = store.write("conv-1", "msg-2", 0, byteArrayOf(1), "png")
        val recentOrphan = store.write("conv-1", "msg-3", 0, byteArrayOf(1), "png")
        val leftoverTemp = File(filesDir, "images/conv-1/.msg-4_0.png.tmp").apply { writeBytes(byteArrayOf(1)) }
        listOf(store.file(referenced), store.file(orphan), leftoverTemp).forEach { it.setLastModified(now - 2 * HOUR) }
        store.file(recentOrphan).setLastModified(now - 1000)

        val deleted = store.sweepOrphans(setOf(referenced))

        assertEquals(2, deleted)
        assertTrue(store.file(referenced).exists())
        assertTrue(store.file(recentOrphan).exists())
        assertFalse(store.file(orphan).exists())
        assertFalse(leftoverTemp.exists())
    }

    @Test
    fun `given a conversation folder left empty when swept then the folder is removed`() {
        val orphan = store.write("conv-1", "msg-1", 0, byteArrayOf(1), "png")
        store.file(orphan).setLastModified(now - 2 * HOUR)
        val dir = File(filesDir, "images/conv-1")

        store.sweepOrphans(emptySet())
        dir.setLastModified(now - 2 * HOUR)
        store.sweepOrphans(emptySet())

        assertFalse(dir.exists())
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
