package dev.nutting.pocketllm.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelsResponse(
    val data: List<ModelInfo>,
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    /**
     * What the server says the model is for. LiteLLM and OpenAI-compatible gateways send `image_generation`
     * or `embedding`; chat models usually omit it. Absent means the server doesn't classify its models.
     */
    val mode: String? = null,
) {
    /** The server marks this model for image generation. */
    val isImageModel: Boolean get() = mode == "image_generation"

    /** The server marks this model as an embedding model. */
    val isEmbeddingModel: Boolean get() = mode == "embedding"

    /** Usable for chat completions: not marked as an image or embedding model. */
    val isChatModel: Boolean get() = !isImageModel && !isEmbeddingModel
}

/**
 * The models a chat server should offer. A server that reports no [ModelInfo.mode] for anything (llama.cpp,
 * Ollama, most local servers) gets all its models back; one that does report modes gets only its chat models,
 * so image and embedding deployments don't appear in the chat picker.
 */
fun List<ModelInfo>.chatModels(): List<ModelInfo> =
    if (none { it.mode != null }) this else filter { it.isChatModel }

/**
 * The models an image server should offer. As with [chatModels], servers that don't classify models keep all
 * of them; servers that do are limited to the ones marked `image_generation`.
 */
fun List<ModelInfo>.imageModels(): List<ModelInfo> =
    if (none { it.mode != null }) this else filter { it.isImageModel }
