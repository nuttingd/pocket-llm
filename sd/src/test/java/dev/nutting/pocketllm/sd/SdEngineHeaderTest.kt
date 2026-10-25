package dev.nutting.pocketllm.sd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdEngineHeaderTest {

    private fun raw(width: Int, height: Int, channels: Int, pixels: ByteArray? = null): ByteArray {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).putInt(channels).putInt(0).array()
        return header + (pixels ?: ByteArray(width * height * channels))
    }

    @Test
    fun `given a valid header then dimensions and channels are parsed`() {
        assertEquals(RawImageHeader(512, 256, 3), parseHeader(raw(512, 256, 3)))
        assertEquals(RawImageHeader(1024, 1024, 4), parseHeader(raw(1024, 1024, 4)))
    }

    @Test
    fun `given malformed data then parsing returns null`() {
        assertNull(parseHeader(ByteArray(8)))
        assertNull(parseHeader(raw(0, 16, 3)))
        assertNull(parseHeader(raw(16, 16, 2)))
        assertNull(parseHeader(raw(64, 64, 3, ByteArray(10))))
    }
}
