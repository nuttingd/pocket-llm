package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.domain.LocalModelState

/** Short load-state label for the selected local model, shown next to its name. */
internal data class LocalModelLabel(val text: String, val isError: Boolean = false, val isBusy: Boolean = false)

internal fun localModelLabel(state: LocalModelState, selectedModelId: String?): LocalModelLabel = when {
    state is LocalModelState.Loaded && state.modelId == selectedModelId -> LocalModelLabel("loaded")
    state is LocalModelState.Loading && state.modelId == selectedModelId -> LocalModelLabel("loading…", isBusy = true)
    state is LocalModelState.Failed && state.modelId == selectedModelId -> LocalModelLabel("failed to load", isError = true)
    else -> LocalModelLabel("loads when you send")
}
