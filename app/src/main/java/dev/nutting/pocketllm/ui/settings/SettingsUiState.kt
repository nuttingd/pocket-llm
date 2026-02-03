package dev.nutting.pocketllm.ui.settings

data class SettingsUiState(
    val themeMode: String = "system",
    val messageFontSizeSp: Int = 16,
    val dynamicColorEnabled: Boolean = true,
    val defaultSystemPrompt: String = "",
    val defaultTemperature: Float = 0.7f,
    val defaultMaxTokens: Int = 2048,
    val defaultTopP: Float = 1.0f,
    val defaultFrequencyPenalty: Float = 0.0f,
    val defaultPresencePenalty: Float = 0.0f,
    val compactionThresholdPct: Int = 75,
)
