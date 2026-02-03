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
)
