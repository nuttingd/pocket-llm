package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageReplyTextTest {

    private fun msg(id: String, role: String, content: String) =
        MessageEntity(id = id, conversationId = "c", role = role, content = content, createdAt = 0)

    private fun job(messageId: String, status: ImageJobStatus, prompt: String = "a fox") = ImageJobEntity(
        id = "j-$messageId", conversationId = "c", messageId = messageId, serverId = "s", modelId = "m",
        dialect = ImageDialect.SD_CPP, kind = ImageJobKind.GENERATE, prompt = prompt, paramsJson = "{}", status = status, createdAt = 0,
    )

    private fun image(messageId: String, prompt: String) = GeneratedImageEntity(
        id = "g-$messageId", messageId = messageId, idx = 0, filePath = "p", mimeType = "image/png", width = 1, height = 1,
        prompt = prompt, paramsJson = "{}", modelId = "m", createdAt = 0,
    )

    @Test
    fun `given image replies in history when sent to a chat model then they become placeholders with the prompt`() {
        val history = listOf(
            msg("u1", "user", "a fox"), msg("a1", "assistant", ""),
            msg("u2", "user", "a cat"), msg("a2", "assistant", ""),
            msg("u3", "user", "a dog"), msg("a3", "assistant", ""),
            msg("u4", "user", "an owl"), msg("a4", "assistant", ""),
            msg("u5", "user", "describe them"),
        )

        val described = describeImageReplies(
            history,
            jobsByMessage = mapOf(
                "a2" to job("a2", ImageJobStatus.FAILED, "a cat"),
                "a3" to job("a3", ImageJobStatus.GENERATING, "a dog"),
                "a4" to job("a4", ImageJobStatus.SUCCEEDED, "an owl"),
            ),
            imagesByMessage = mapOf("a1" to listOf(image("a1", "a fox"))),
        )

        assertEquals(
            listOf(
                "a fox", "[Generated image: a fox]",
                "a cat", "[Image generation failed for: a cat]",
                "a dog", // still generating: left out
                "an owl", "[Generated image: an owl]",
                "describe them",
            ),
            described.map { it.content },
        )
    }

    @Test
    fun `given ordinary text replies then they pass through unchanged`() {
        val history = listOf(msg("u1", "user", "hi"), msg("a1", "assistant", "hello"))

        assertEquals(history, describeImageReplies(history, emptyMap(), emptyMap()))
    }
}
