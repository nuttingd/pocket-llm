package dev.nutting.pocketllm.data.remote.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinitionParam>? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    @Serializable(with = ChatContentSerializer::class)
    val content: ChatContent,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable(with = ChatContentSerializer::class)
sealed interface ChatContent {
    data class Text(val text: String) : ChatContent
    data class Parts(val parts: List<ContentPart>) : ChatContent
}

@Serializable
sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String) : ContentPart

    @Serializable
    @SerialName("image_url")
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl,
    ) : ContentPart
}

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = null,
)

object ChatContentSerializer : KSerializer<ChatContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatContent")

    override fun serialize(encoder: Encoder, value: ChatContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is ChatContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is ChatContent.Parts -> {
                val array = buildJsonArray {
                    for (part in value.parts) {
                        when (part) {
                            is ContentPart.TextPart -> add(buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            })
                            is ContentPart.ImagePart -> add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", part.imageUrl.url)
                                    part.imageUrl.detail?.let { put("detail", it) }
                                })
                            })
                        }
                    }
                }
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ChatContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive -> ChatContent.Text(element.jsonPrimitive.content)
            else -> {
                val parts = element.jsonArray.map { partElement ->
                    val obj = partElement as kotlinx.serialization.json.JsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "image_url" -> {
                            val imageUrlObj = obj["image_url"] as kotlinx.serialization.json.JsonObject
                            ContentPart.ImagePart(
                                ImageUrl(
                                    url = imageUrlObj["url"]!!.jsonPrimitive.content,
                                    detail = imageUrlObj["detail"]?.jsonPrimitive?.content,
                                )
                            )
                        }
                        else -> ContentPart.TextPart(
                            text = obj["text"]?.jsonPrimitive?.content ?: "",
                        )
                    }
                }
                ChatContent.Parts(parts)
            }
        }
    }
}
