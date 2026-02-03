package dev.nutting.pocketllm.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

class SettingsDataStore(context: Context) {

    private val dataStore = context.settingsDataStore

    // Keys
    private val themeMode = stringPreferencesKey("theme_mode")
    private val messageFontSizeSp = intPreferencesKey("message_font_size_sp")
    private val dynamicColorEnabled = booleanPreferencesKey("dynamic_color_enabled")
    private val defaultSystemPrompt = stringPreferencesKey("default_system_prompt")
    private val defaultTemperature = floatPreferencesKey("default_temperature")
    private val defaultMaxTokens = intPreferencesKey("default_max_tokens")
    private val defaultTopP = floatPreferencesKey("default_top_p")
    private val defaultFrequencyPenalty = floatPreferencesKey("default_frequency_penalty")
    private val defaultPresencePenalty = floatPreferencesKey("default_presence_penalty")
    private val lastActiveServerId = stringPreferencesKey("last_active_server_id")
    private val lastActiveConversationId = stringPreferencesKey("last_active_conversation_id")
    private val compactionThresholdPct = intPreferencesKey("compaction_threshold_pct")
    private val imageMaxDimensionPx = intPreferencesKey("image_max_dimension_px")
    private val imageJpegQuality = intPreferencesKey("image_jpeg_quality")

    // Getters
    fun getThemeMode(): Flow<String> = dataStore.data.map { it[themeMode] ?: "system" }
    fun getMessageFontSizeSp(): Flow<Int> = dataStore.data.map { it[messageFontSizeSp] ?: 16 }
    fun getDynamicColorEnabled(): Flow<Boolean> = dataStore.data.map { it[dynamicColorEnabled] ?: true }
    fun getDefaultSystemPrompt(): Flow<String> = dataStore.data.map { it[defaultSystemPrompt] ?: "" }
    fun getDefaultTemperature(): Flow<Float> = dataStore.data.map { it[defaultTemperature] ?: 0.7f }
    fun getDefaultMaxTokens(): Flow<Int> = dataStore.data.map { it[defaultMaxTokens] ?: 2048 }
    fun getDefaultTopP(): Flow<Float> = dataStore.data.map { it[defaultTopP] ?: 1.0f }
    fun getDefaultFrequencyPenalty(): Flow<Float> = dataStore.data.map { it[defaultFrequencyPenalty] ?: 0.0f }
    fun getDefaultPresencePenalty(): Flow<Float> = dataStore.data.map { it[defaultPresencePenalty] ?: 0.0f }
    fun getLastActiveServerId(): Flow<String> = dataStore.data.map { it[lastActiveServerId] ?: "" }
    fun getLastActiveConversationId(): Flow<String> = dataStore.data.map { it[lastActiveConversationId] ?: "" }
    fun getCompactionThresholdPct(): Flow<Int> = dataStore.data.map { it[compactionThresholdPct] ?: 75 }
    fun getImageMaxDimensionPx(): Flow<Int> = dataStore.data.map { it[imageMaxDimensionPx] ?: 1024 }
    fun getImageJpegQuality(): Flow<Int> = dataStore.data.map { it[imageJpegQuality] ?: 85 }

    // Setters
    suspend fun setThemeMode(value: String) { dataStore.edit { it[themeMode] = value } }
    suspend fun setMessageFontSizeSp(value: Int) { dataStore.edit { it[messageFontSizeSp] = value } }
    suspend fun setDynamicColorEnabled(value: Boolean) { dataStore.edit { it[dynamicColorEnabled] = value } }
    suspend fun setDefaultSystemPrompt(value: String) { dataStore.edit { it[defaultSystemPrompt] = value } }
    suspend fun setDefaultTemperature(value: Float) { dataStore.edit { it[defaultTemperature] = value } }
    suspend fun setDefaultMaxTokens(value: Int) { dataStore.edit { it[defaultMaxTokens] = value } }
    suspend fun setDefaultTopP(value: Float) { dataStore.edit { it[defaultTopP] = value } }
    suspend fun setDefaultFrequencyPenalty(value: Float) { dataStore.edit { it[defaultFrequencyPenalty] = value } }
    suspend fun setDefaultPresencePenalty(value: Float) { dataStore.edit { it[defaultPresencePenalty] = value } }
    suspend fun setLastActiveServerId(value: String) { dataStore.edit { it[lastActiveServerId] = value } }
    suspend fun setLastActiveConversationId(value: String) { dataStore.edit { it[lastActiveConversationId] = value } }
    suspend fun setCompactionThresholdPct(value: Int) { dataStore.edit { it[compactionThresholdPct] = value } }
    suspend fun setImageMaxDimensionPx(value: Int) { dataStore.edit { it[imageMaxDimensionPx] = value } }
    suspend fun setImageJpegQuality(value: Int) { dataStore.edit { it[imageJpegQuality] = value } }
}
