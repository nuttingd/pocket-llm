package dev.nutting.pocketllm.domain

/** Which on-device model is in memory, for display. */
sealed interface LocalModelState {
    data object NotLoaded : LocalModelState
    data class Loading(val modelId: String) : LocalModelState
    data class Loaded(val modelId: String) : LocalModelState
    data class Failed(val modelId: String, val reason: String) : LocalModelState
}
