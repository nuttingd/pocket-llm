package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageNotificationsTest {

    private fun job(status: ImageJobStatus, id: String = "j1", queue: Int? = null, step: Int? = null, total: Int? = null, error: String? = null) =
        ImageJobEntity(
            id = id, conversationId = "c1", messageId = "a1", serverId = "s1", modelId = "m",
            dialect = ImageDialect.SD_CPP, kind = ImageJobKind.GENERATE, prompt = "a red fox", paramsJson = "{}",
            status = status, queuePosition = queue, step = step, totalSteps = total, errorMessage = error, createdAt = 0,
        )

    @Test
    fun `given a queued job then the ongoing notification shows its position and prompt`() {
        val (title, text, progress) = ImageNotifications.ongoingContent(job(ImageJobStatus.SUBMITTED, queue = 3))
        assertEquals("Generating image", title)
        assertEquals("Queued (position 3)\na red fox", text)
        assertNull(progress)
    }

    @Test
    fun `given a generating job with steps then the ongoing notification shows step progress`() {
        val (_, text, progress) = ImageNotifications.ongoingContent(job(ImageJobStatus.GENERATING, step = 2, total = 8))
        assertEquals("Generating · Step 2 of 8\na red fox", text)
        assertEquals(0.25f, progress!!, 0.001f)
    }

    @Test
    fun `given finished jobs then success and failure are announced and a user cancel is not`() {
        assertEquals("Image ready" to "a red fox", ImageNotifications.resultContent(job(ImageJobStatus.SUCCEEDED)))
        assertEquals("Couldn't generate the image" to "boom", ImageNotifications.resultContent(job(ImageJobStatus.FAILED, error = "boom")))
        assertNull(ImageNotifications.resultContent(job(ImageJobStatus.CANCELLED)))
        assertNull(ImageNotifications.resultContent(job(ImageJobStatus.GENERATING)))
    }

    @Test
    fun `given two jobs then their notifications never share an id`() {
        assertNotEquals(ImageNotifications.ongoingId("j1"), ImageNotifications.ongoingId("j2"))
        assertNotEquals(ImageNotifications.ongoingId("j1"), ImageNotifications.resultId("j1"))
    }
}
