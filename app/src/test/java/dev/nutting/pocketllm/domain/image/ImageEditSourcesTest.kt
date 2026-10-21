package dev.nutting.pocketllm.domain.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ImageEditSourcesTest {

    @Test
    fun `given a base64 data url then its bytes and type are decoded`() {
        val bytes = byteArrayOf(1, 2, 3)
        val image = decodeDataUrl("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes))!!
        assertEquals("image/jpeg", image.mimeType)
        assertArrayEquals(bytes, image.bytes)
    }

    @Test
    fun `given anything other than a base64 data url then nothing is decoded`() {
        assertNull(decodeDataUrl("content://media/1"))
        assertNull(decodeDataUrl("data:image/png,plain"))
        assertNull(decodeDataUrl("data:image/png;base64,***"))
    }

    @Test
    fun `given a portrait photo then the edit keeps its aspect ratio at about one megapixel`() {
        val (w, h) = editSizeFor(600, 800)
        assertEquals(3f / 4f, w.toFloat() / h, 0.01f)
        assertEquals(1024 * 1024f, (w * h).toFloat(), 1024 * 1024 * 0.01f)
        // And resolving snaps it to multiples of 32
        val resolved = ImageParameters(width = w, height = h).resolve(dev.nutting.pocketllm.data.local.entity.ImageDialect.SD_CPP)
        assertEquals(896 to 1184, resolved.width to resolved.height)
    }
}
