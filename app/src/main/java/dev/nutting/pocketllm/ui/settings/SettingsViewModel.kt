package dev.nutting.pocketllm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    themeMode = settingsRepository.getThemeMode().first(),
                    messageFontSizeSp = settingsRepository.getMessageFontSizeSp().first(),
                    dynamicColorEnabled = settingsRepository.getDynamicColorEnabled().first(),
                    defaultSystemPrompt = settingsRepository.getDefaultSystemPrompt().first(),
                    defaultTemperature = settingsRepository.getDefaultTemperature().first(),
                    defaultMaxTokens = settingsRepository.getDefaultMaxTokens().first(),
                    defaultTopP = settingsRepository.getDefaultTopP().first(),
                    defaultFrequencyPenalty = settingsRepository.getDefaultFrequencyPenalty().first(),
                    defaultPresencePenalty = settingsRepository.getDefaultPresencePenalty().first(),
                    compactionThresholdPct = settingsRepository.getCompactionThresholdPct().first(),
                )
            }
        }
    }

    fun setThemeMode(value: String) {
        _uiState.update { it.copy(themeMode = value) }
        viewModelScope.launch { settingsRepository.setThemeMode(value) }
    }

    fun setMessageFontSizeSp(value: Int) {
        _uiState.update { it.copy(messageFontSizeSp = value) }
        viewModelScope.launch { settingsRepository.setMessageFontSizeSp(value) }
    }

    fun setDynamicColorEnabled(value: Boolean) {
        _uiState.update { it.copy(dynamicColorEnabled = value) }
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(value) }
    }

    fun setDefaultSystemPrompt(value: String) {
        _uiState.update { it.copy(defaultSystemPrompt = value) }
        viewModelScope.launch { settingsRepository.setDefaultSystemPrompt(value) }
    }

    fun setDefaultTemperature(value: Float) {
        _uiState.update { it.copy(defaultTemperature = value) }
        viewModelScope.launch { settingsRepository.setDefaultTemperature(value) }
    }

    fun setDefaultMaxTokens(value: Int) {
        _uiState.update { it.copy(defaultMaxTokens = value) }
        viewModelScope.launch { settingsRepository.setDefaultMaxTokens(value) }
    }

    fun setDefaultTopP(value: Float) {
        _uiState.update { it.copy(defaultTopP = value) }
        viewModelScope.launch { settingsRepository.setDefaultTopP(value) }
    }

    fun setDefaultFrequencyPenalty(value: Float) {
        _uiState.update { it.copy(defaultFrequencyPenalty = value) }
        viewModelScope.launch { settingsRepository.setDefaultFrequencyPenalty(value) }
    }

    fun setDefaultPresencePenalty(value: Float) {
        _uiState.update { it.copy(defaultPresencePenalty = value) }
        viewModelScope.launch { settingsRepository.setDefaultPresencePenalty(value) }
    }

    fun setCompactionThresholdPct(value: Int) {
        _uiState.update { it.copy(compactionThresholdPct = value) }
        viewModelScope.launch { settingsRepository.setCompactionThresholdPct(value) }
    }
}
