package dev.nutting.pocketllm.data.repository

import dev.nutting.pocketllm.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val dataStore: SettingsDataStore,
) {
    fun getThemeMode(): Flow<String> = dataStore.getThemeMode()
    fun getMessageFontSizeSp(): Flow<Int> = dataStore.getMessageFontSizeSp()
    fun getDynamicColorEnabled(): Flow<Boolean> = dataStore.getDynamicColorEnabled()
    fun getDefaultSystemPrompt(): Flow<String> = dataStore.getDefaultSystemPrompt()
    fun getDefaultTemperature(): Flow<Float> = dataStore.getDefaultTemperature()
    fun getDefaultMaxTokens(): Flow<Int> = dataStore.getDefaultMaxTokens()
    fun getDefaultTopP(): Flow<Float> = dataStore.getDefaultTopP()
    fun getDefaultFrequencyPenalty(): Flow<Float> = dataStore.getDefaultFrequencyPenalty()
    fun getDefaultPresencePenalty(): Flow<Float> = dataStore.getDefaultPresencePenalty()
    fun getLastActiveServerId(): Flow<String> = dataStore.getLastActiveServerId()
    fun getLastActiveConversationId(): Flow<String> = dataStore.getLastActiveConversationId()
    fun getCompactionThresholdPct(): Flow<Int> = dataStore.getCompactionThresholdPct()
    fun getImageMaxDimensionPx(): Flow<Int> = dataStore.getImageMaxDimensionPx()
    fun getImageJpegQuality(): Flow<Int> = dataStore.getImageJpegQuality()

    suspend fun setThemeMode(value: String) = dataStore.setThemeMode(value)
    suspend fun setMessageFontSizeSp(value: Int) = dataStore.setMessageFontSizeSp(value)
    suspend fun setDynamicColorEnabled(value: Boolean) = dataStore.setDynamicColorEnabled(value)
    suspend fun setDefaultSystemPrompt(value: String) = dataStore.setDefaultSystemPrompt(value)
    suspend fun setDefaultTemperature(value: Float) = dataStore.setDefaultTemperature(value)
    suspend fun setDefaultMaxTokens(value: Int) = dataStore.setDefaultMaxTokens(value)
    suspend fun setDefaultTopP(value: Float) = dataStore.setDefaultTopP(value)
    suspend fun setDefaultFrequencyPenalty(value: Float) = dataStore.setDefaultFrequencyPenalty(value)
    suspend fun setDefaultPresencePenalty(value: Float) = dataStore.setDefaultPresencePenalty(value)
    suspend fun setLastActiveServerId(value: String) = dataStore.setLastActiveServerId(value)
    suspend fun setLastActiveConversationId(value: String) = dataStore.setLastActiveConversationId(value)
    suspend fun setCompactionThresholdPct(value: Int) = dataStore.setCompactionThresholdPct(value)
    suspend fun setImageMaxDimensionPx(value: Int) = dataStore.setImageMaxDimensionPx(value)
    suspend fun setImageJpegQuality(value: Int) = dataStore.setImageJpegQuality(value)
}
