package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.dao.MessageDao
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The input images of an edit: the attachments of the user message that asked for it. Attachments are stored as
 * base64 data URLs joined with `|` (already downscaled by the attachment compressor setting).
 */
suspend fun loadEditSources(messages: MessageDao, sourceMessageId: String?): List<ImageJobRunner.SourceImage> {
    val message = sourceMessageId?.let { messages.getById(it) } ?: return emptyList()
    return message.imageUris?.split("|").orEmpty().mapNotNull(::decodeDataUrl)
}

/** Decodes `data:<mime>;base64,<data>`; null for anything else. */
internal fun decodeDataUrl(url: String): ImageJobRunner.SourceImage? {
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val header = url.substring(5, comma)
    if (!header.endsWith(";base64")) return null
    val bytes = runCatching { Base64.getDecoder().decode(url.substring(comma + 1)) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val mimeType = header.removeSuffix(";base64").ifBlank { sniffImage(bytes)?.mimeType ?: return null }
    return ImageJobRunner.SourceImage(bytes, mimeType)
}

/**
 * The output size of an edit when the user hasn't chosen one: the source's aspect ratio at about the default
 * one-megapixel area, so a portrait photo isn't squashed into a square. The result is snapped by [resolve].
 */
fun editSizeFor(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    if (sourceWidth <= 0 || sourceHeight <= 0) return DEFAULT_SIDE to DEFAULT_SIDE
    val scale = sqrt(DEFAULT_SIDE.toDouble() * DEFAULT_SIDE / (sourceWidth.toDouble() * sourceHeight))
    return (sourceWidth * scale).roundToInt() to (sourceHeight * scale).roundToInt()
}
