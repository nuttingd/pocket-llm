package dev.nutting.pocketllm.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizePresetTest {

    @Test
    fun `given no saved size then the 1024 square preset is selected`() {
        assertEquals(ImageSizePreset.SQUARE, ImageSizePreset.of(null, null))
    }

    @Test
    fun `given a saved preset size then that preset is selected`() {
        assertEquals(ImageSizePreset.TALL, ImageSizePreset.of(768, 1344))
        assertEquals(ImageSizePreset.LANDSCAPE, ImageSizePreset.of(1152, 896))
    }

    @Test
    fun `given any other size then custom is selected`() {
        assertEquals(ImageSizePreset.CUSTOM, ImageSizePreset.of(1000, 1000))
        assertEquals(ImageSizePreset.CUSTOM, ImageSizePreset.of(1024, null))
    }

    @Test
    fun `given every preset then its sides are multiples of 32 so nothing is rounded`() {
        ImageSizePreset.entries.filter { it.width != null }.forEach {
            assertEquals(0, it.width!! % 32)
            assertEquals(0, it.height!! % 32)
        }
    }
}
