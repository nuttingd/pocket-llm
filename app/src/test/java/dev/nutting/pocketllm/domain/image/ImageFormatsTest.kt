package dev.nutting.pocketllm.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ImageFormatsTest {

    // Generated with Pillow: (width, height, base64)
    private val fixtures = mapOf(
        "jpeg" to Triple(37, 21, "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2P/wAARCAAVACUDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwBtFFFeMfMhRRRQAUUUUAFFFFABRRRQAUUUUAf/2Q=="),
        "jpeg_progressive" to Triple(40, 30, "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wgARCAAeACgDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAL/xAAWAQEBAQAAAAAAAAAAAAAAAAAABQb/2gAMAwEAAhADEAAAAZEfNgAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAABA/9oACAEBAAEFAif/xAAUEQEAAAAAAAAAAAAAAAAAAAAg/9oACAEDAQE/AV//xAAUEQEAAAAAAAAAAAAAAAAAAAAg/9oACAECAQE/AV//xAAUEAEAAAAAAAAAAAAAAAAAAABA/9oACAEBAAY/Aif/xAAUEAEAAAAAAAAAAAAAAAAAAABA/9oACAEBAAE/ISf/2gAMAwEAAgADAAAAEAgggggv/8QAFBEBAAAAAAAAAAAAAAAAAAAAIP/aAAgBAwEBPxBf/8QAFBEBAAAAAAAAAAAAAAAAAAAAIP/aAAgBAgEBPxBf/8QAFBABAAAAAAAAAAAAAAAAAAAAQP/aAAgBAQABPxAn/9k="),
        "webp_lossy" to Triple(33, 17, "UklGRkwAAABXRUJQVlA4IEAAAACQAwCdASohABEAPm02l0ikIyIhJWgAgA2JZwDQvoB+AAA1rqvoAP6uF//mwK5rn6W//7O79Gb9O7++RwULTgAA"),
        "webp_lossless" to Triple(65, 9, "UklGRh4AAABXRUJQVlA4TBEAAAAvQAACAAdQ5CrUo/+BiOh/AAA="),
        "webp_alpha" to Triple(300, 200, "UklGRtIAAABXRUJQVlA4WAoAAAAQAAAAKwEAxwAAQUxQSBIAAAABB1DAiAgAKNL//xTR/9T/rwFWUDggmgAAAHAQAJ0BKiwByAA+bTaZSaQjIqEgKACADYlpbuF2sRtACewD32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snCgAP7/1LgAAAAAAAAAAAA="),
    )
    private val png3x2 = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAC0lEQVR4nGNgwAQAABQAAX3+Hu4AAAAASUVORK5CYII="

    private fun bytes(b64: String) = Base64.getDecoder().decode(b64)

    @Test
    fun `given a png when sniffed then its type and size come from the header`() {
        assertEquals(ImageInfo("png", "image/png", 3, 2), sniffImage(bytes(png3x2)))
    }

    @Test
    fun `given jpeg and webp variants when sniffed then type and size are read without decoding`() {
        for ((name, fixture) in fixtures) {
            val (w, h, data) = fixture
            val info = sniffImage(bytes(data))
            assertEquals(name, w, info?.width)
            assertEquals(name, h, info?.height)
            assertEquals(name, if (name.startsWith("jpeg")) "jpg" else "webp", info?.extension)
        }
    }

    @Test
    fun `given data that is not an image or is truncated when sniffed then it is not recognised`() {
        assertNull(sniffImage("not an image".toByteArray()))
        assertNull(sniffImage(ByteArray(0)))
        assertNull(sniffImage(bytes(png3x2).copyOf(12)))
        assertNull(sniffImage(bytes(fixtures.getValue("jpeg").third).copyOf(20)))
    }
}
