package dev.nutting.pocketllm.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val error: ApiError,
)

@Serializable
data class ApiError(
    val message: String,
    val type: String? = null,
    val code: String? = null,
)
