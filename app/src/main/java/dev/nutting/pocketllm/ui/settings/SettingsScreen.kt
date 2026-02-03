package dev.nutting.pocketllm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Theme
            SectionHeader("Appearance")

            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            val themes = listOf("light", "dark", "system")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = state.themeMode == theme,
                        onClick = { viewModel.setThemeMode(theme) },
                        shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                    ) {
                        Text(theme.replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dynamic colors (Material You)")
                Switch(
                    checked = state.dynamicColorEnabled,
                    onCheckedChange = viewModel::setDynamicColorEnabled,
                )
            }

            SliderSetting(
                label = "Message font size",
                value = state.messageFontSizeSp.toFloat(),
                valueRange = 12f..24f,
                steps = 5,
                valueLabel = "${state.messageFontSizeSp} sp",
                onValueChange = { viewModel.setMessageFontSizeSp(it.roundToInt()) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Generation Defaults
            SectionHeader("Generation Defaults")

            OutlinedTextField(
                value = state.defaultSystemPrompt,
                onValueChange = viewModel::setDefaultSystemPrompt,
                label = { Text("Default system prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )

            SliderSetting(
                label = "Temperature",
                value = state.defaultTemperature,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultTemperature),
                onValueChange = { viewModel.setDefaultTemperature((it * 10).roundToInt() / 10f) },
            )

            SliderSetting(
                label = "Max tokens",
                value = state.defaultMaxTokens.toFloat(),
                valueRange = 256f..8192f,
                valueLabel = "${state.defaultMaxTokens}",
                onValueChange = { viewModel.setDefaultMaxTokens(it.roundToInt()) },
            )

            SliderSetting(
                label = "Top-P",
                value = state.defaultTopP,
                valueRange = 0f..1f,
                valueLabel = "%.2f".format(state.defaultTopP),
                onValueChange = { viewModel.setDefaultTopP((it * 100).roundToInt() / 100f) },
            )

            SliderSetting(
                label = "Frequency penalty",
                value = state.defaultFrequencyPenalty,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultFrequencyPenalty),
                onValueChange = { viewModel.setDefaultFrequencyPenalty((it * 10).roundToInt() / 10f) },
            )

            SliderSetting(
                label = "Presence penalty",
                value = state.defaultPresencePenalty,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultPresencePenalty),
                onValueChange = { viewModel.setDefaultPresencePenalty((it * 10).roundToInt() / 10f) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Advanced
            SectionHeader("Advanced")

            SliderSetting(
                label = "Compaction threshold",
                value = state.compactionThresholdPct.toFloat(),
                valueRange = 50f..95f,
                steps = 8,
                valueLabel = "${state.compactionThresholdPct}%",
                onValueChange = { viewModel.setCompactionThresholdPct(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
