package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.domain.image.describeImageJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageReplyStatusTest {

    private fun job(status: ImageJobStatus, queue: Int? = null, pass: Int? = null, step: Int? = null, total: Int? = null, error: String? = null) =
        ImageJobEntity(
            id = "j", conversationId = "c", messageId = "m", serverId = "s", modelId = "sd", dialect = ImageDialect.SD_CPP,
            kind = ImageJobKind.GENERATE, prompt = "a fox", paramsJson = "{}", status = status, queuePosition = queue,
            pass = pass, step = step, totalSteps = total, errorMessage = error, createdAt = 0,
        )

    @Test
    fun `given each job state then the reply says what is happening`() {
        assertEquals("Starting…", describeImageJob(job(ImageJobStatus.QUEUED_LOCAL)).phase)
        assertEquals("Queued (position 2)", describeImageJob(job(ImageJobStatus.SUBMITTED, queue = 2)).phase)
        assertEquals("Queued", describeImageJob(job(ImageJobStatus.SUBMITTED, queue = 0)).phase)
        assertEquals("Saving…", describeImageJob(job(ImageJobStatus.SAVING)).phase)
        val failed = describeImageJob(job(ImageJobStatus.FAILED, error = "The server has no model loaded."))
        assertEquals("Couldn't generate the image", failed.phase)
        assertEquals("The server has no model loaded.", failed.detail)
        assertNull(describeImageJob(job(ImageJobStatus.CANCELLED, error = "Cancelled.")).detail)
    }

    @Test
    fun `given step progress then generating shows the step and a determinate fraction`() {
        val text = describeImageJob(job(ImageJobStatus.GENERATING, pass = 1, step = 3, total = 8))
        assertEquals("Generating", text.phase)
        assertEquals("Step 3 of 8", text.detail)
        assertEquals(0.375f, text.progress)

        assertEquals("Step 2 of 4 · pass 2", describeImageJob(job(ImageJobStatus.GENERATING, pass = 2, step = 2, total = 4)).detail)
        assertNull(describeImageJob(job(ImageJobStatus.GENERATING)).progress)
    }

    @Test
    fun `given a generated image then the caption lists size, steps, seed and duration`() {
        val image = GeneratedImageEntity(
            id = "g", messageId = "m", idx = 0, filePath = "p", mimeType = "image/png", width = 1024, height = 768,
            seed = 4417, prompt = "a fox", paramsJson = """{"width":1024,"height":768,"count":1,"seed":4417,"steps":8}""",
            modelId = "sd", durationMs = 48_500, createdAt = 0,
        )

        assertEquals("1024×768 · 8 steps · seed 4417 · 48s", imageCaption(image))
        assertEquals("1024×768", imageCaption(image.copy(seed = null, durationMs = null, paramsJson = "{}")))
        val withNegative = image.copy(paramsJson = """{"width":1024,"height":768,"count":1,"negativePrompt":"blurry"}""", seed = null, durationMs = null)
        assertEquals("1024×768 · excluding \u201cblurry\u201d", imageCaption(withNegative))
    }
}
