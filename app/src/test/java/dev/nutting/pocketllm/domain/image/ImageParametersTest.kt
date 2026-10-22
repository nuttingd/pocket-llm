package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.remote.image.SdCppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ImageParametersTest {

    @Test
    fun `given no settings when resolved for sd-server then 1024 square, one image and a random seed are sent`() {
        val a = ImageParameters().resolve(ImageDialect.SD_CPP, random = Random(1))
        val b = ImageParameters().resolve(ImageDialect.SD_CPP, random = Random(2))

        assertEquals(1024, a.width)
        assertEquals(1024, a.height)
        assertEquals(1, a.count)
        assertNotNull(a.seed)
        assertNotEquals(a.seed, b.seed)
    }

    @Test
    fun `given a custom size when resolved then it snaps to a multiple of 32 within the server's limits`() {
        val limits = SdCppLimits(minWidth = 64, maxWidth = 2048, minHeight = 64, maxHeight = 2048)

        assertEquals(992, ImageParameters(width = 1000).resolve(ImageDialect.SD_CPP, limits).width)
        assertEquals(2048, ImageParameters(width = 5000).resolve(ImageDialect.SD_CPP, limits).width)
        assertEquals(64, ImageParameters(height = 10).resolve(ImageDialect.SD_CPP, limits).height)
        assertEquals(96, snapSide(100, min = 70, max = 4096))
        assertEquals(96, snapSide(10, min = 70, max = 4096))
    }

    @Test
    fun `given a count above the limit when resolved then it is clamped to the app and server maximums`() {
        assertEquals(4, ImageParameters(count = 9).resolve(ImageDialect.SD_CPP).count)
        assertEquals(2, ImageParameters(count = 4).resolve(ImageDialect.SD_CPP, SdCppLimits(maxBatchCount = 2)).count)
        assertEquals(1, ImageParameters(count = 0).resolve(ImageDialect.SD_CPP).count)
    }

    @Test
    fun `given a fixed seed when resolved then it is sent unchanged and batch images use seed plus index`() {
        val resolved = ImageParameters(seed = 42, count = 3).resolve(ImageDialect.SD_CPP)

        assertEquals(42L, resolved.seed)
        assertEquals(44L, resolved.seedOf(2))
        assertEquals(42L, resolved.toSdCppRequest("x").seed)
        assertEquals(3, resolved.toSdCppRequest("x").batchCount)
    }

    @Test
    fun `given native settings when resolved for sd-server then they map to the native request and unset ones are omitted`() {
        val request = ImageParameters(steps = 8, cfg = 1f, sampler = "euler")
            .resolve(ImageDialect.SD_CPP, negativePrompt = "blurry").toSdCppRequest("a fox", previewMode = "proj")

        assertEquals(8, request.sampleParams?.sampleSteps)
        assertEquals(1f, request.sampleParams?.guidance?.txtCfg)
        assertEquals("euler", request.sampleParams?.sampleMethod)
        assertNull(request.sampleParams?.scheduler)
        assertEquals("blurry", request.negativePrompt)
        assertNull(request.batchCount)
        assertEquals("proj", request.preview)
        assertEquals(1, request.previewInterval)

        assertNull(ImageParameters().resolve(ImageDialect.SD_CPP).toSdCppRequest("x").sampleParams)
    }

    @Test
    fun `given the generic images api when resolved then only size and count are sent`() {
        val resolved = ImageParameters(width = 768, height = 1344, count = 2, steps = 8, cfg = 4f, seed = 5)
            .resolve(ImageDialect.OPENAI_IMAGES, negativePrompt = "x")

        assertNull(resolved.seed)
        assertNull(resolved.steps)
        assertNull(resolved.cfg)
        assertNull(resolved.negativePrompt)
        val request = resolved.toOpenAiRequest("qwen-image", "a fox")
        assertEquals("768x1344", request.size)
        assertEquals(2, request.n)
    }
}
