package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.MessageEntity

/**
 * Rewrites image replies as text for a chat model (and for export): a model can't see our image files, so
 * each image reply becomes "[Generated image: <prompt>]", failed or cancelled ones say so, and replies still
 * generating are left out. Other messages pass through unchanged.
 */
fun describeImageReplies(
    messages: List<MessageEntity>,
    jobsByMessage: Map<String, ImageJobEntity>,
    imagesByMessage: Map<String, List<GeneratedImageEntity>>,
): List<MessageEntity> = messages.mapNotNull { message ->
    val job = jobsByMessage[message.id]
    val images = imagesByMessage[message.id].orEmpty()
    when {
        message.role != "assistant" || (job == null && images.isEmpty()) -> message
        images.isNotEmpty() -> message.copy(content = imageReplyPlaceholder(images.first().prompt))
        job == null -> message
        job.status == ImageJobStatus.SUCCEEDED -> message.copy(content = imageReplyPlaceholder(job.prompt))
        job.status == ImageJobStatus.FAILED -> message.copy(content = "[Image generation failed for: ${job.prompt}]")
        job.status == ImageJobStatus.CANCELLED -> message.copy(content = "[Image generation cancelled for: ${job.prompt}]")
        else -> null
    }
}

fun imageReplyPlaceholder(prompt: String) = "[Generated image: $prompt]"
