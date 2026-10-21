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

/** Phase text (announced on change), optional detail, and a progress fraction (null = indeterminate). */
internal data class ImageStatusText(val phase: String, val detail: String? = null, val progress: Float? = null)

internal fun describeImageJob(job: ImageJobEntity): ImageStatusText = when (job.status) {
    ImageJobStatus.QUEUED_LOCAL -> ImageStatusText("Starting…")
    ImageJobStatus.SUBMITTED -> ImageStatusText(job.queuePosition?.takeIf { it > 0 }?.let { "Queued (position $it)" } ?: "Queued")
    ImageJobStatus.GENERATING -> {
        val step = job.step
        val total = job.totalSteps
        if (step != null && total != null && total > 0) {
            val pass = job.pass?.takeIf { it > 1 }?.let { " · pass $it" } ?: ""
            ImageStatusText("Generating", "Step $step of $total$pass", (step.toFloat() / total).coerceIn(0f, 1f))
        } else {
            ImageStatusText("Generating")
        }
    }
    ImageJobStatus.SAVING -> ImageStatusText("Saving…")
    ImageJobStatus.SUCCEEDED -> ImageStatusText("Done")
    ImageJobStatus.FAILED -> ImageStatusText("Couldn't generate the image", job.errorMessage)
    ImageJobStatus.CANCELLED -> ImageStatusText("Cancelled", job.errorMessage?.takeIf { it != "Cancelled." })
}
