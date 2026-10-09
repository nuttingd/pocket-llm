package dev.nutting.pocketllm.ui.modelmanagement

import dev.nutting.pocketllm.domain.LocalModelState
import dev.nutting.pocketllm.llm.LlmEngine

internal data class LoadText(val text: String, val isError: Boolean = false)

/** Engine card summary: which model is in memory, loading, or failed (with the reason). */
internal fun engineStatusText(
    modelState: LocalModelState,
    engineState: LlmEngine.State,
    modelName: (String) -> String,
): List<LoadText> = when (modelState) {
    is LocalModelState.Loaded -> listOf(
        LoadText(
            if (engineState is LlmEngine.State.Inferring) "Generating with ${modelName(modelState.modelId)}"
            else "Loaded: ${modelName(modelState.modelId)}"
        )
    )
    is LocalModelState.Loading -> listOf(LoadText("Loading ${modelName(modelState.modelId)}…"))
    is LocalModelState.Failed -> listOf(
        LoadText("Failed to load ${modelName(modelState.modelId)}", isError = true),
        LoadText(modelState.reason, isError = true),
    )
    LocalModelState.NotLoaded -> listOf(LoadText("No model loaded. The active model loads when you send a message."))
}

/** Per-model load state line, or null when this model isn't the one loaded/loading/failed. */
internal fun modelLoadText(modelId: String, modelState: LocalModelState): LoadText? = when {
    modelState is LocalModelState.Loaded && modelState.modelId == modelId -> LoadText("Loaded in memory")
    modelState is LocalModelState.Loading && modelState.modelId == modelId -> LoadText("Loading…")
    modelState is LocalModelState.Failed && modelState.modelId == modelId -> LoadText(modelState.reason, isError = true)
    else -> null
}
