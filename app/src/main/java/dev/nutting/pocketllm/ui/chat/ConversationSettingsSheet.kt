package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import kotlin.math.roundToInt

data class ConversationParameters(
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsSheet(
    params: ConversationParameters,
    defaults: ConversationParameters,
    onParamsChanged: (ConversationParameters) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    availableTools: List<ToolDefinitionEntity> = emptyList(),
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    presets: List<ParameterPresetEntity> = emptyList(),
    onApplyPreset: (ParameterPresetEntity) -> Unit = {},
    onSaveAsPreset: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Conversation Settings", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onResetToDefaults) { Text("Reset") }
            }

            if (presets.isNotEmpty()) {
                PresetPicker(
                    presets = presets,
                    onApplyPreset = onApplyPreset,
                    onSaveAsPreset = onSaveAsPreset,
                )
            }

            OutlinedTextField(
                value = params.systemPrompt ?: "",
                onValueChange = { onParamsChanged(params.copy(systemPrompt = it.ifBlank { null })) },
                label = {
                    val isOverridden = params.systemPrompt != null
                    Text(if (isOverridden) "System prompt (overridden)" else "System prompt (default)")
                },
                placeholder = { Text(defaults.systemPrompt ?: "None") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            ParamSlider(
                label = "Temperature",
                value = params.temperature ?: defaults.temperature ?: 0.7f,
                isOverridden = params.temperature != null,
                valueRange = 0f..2f,
                format = { "%.1f".format(it) },
                onValueChange = { onParamsChanged(params.copy(temperature = (it * 10).roundToInt() / 10f)) },
            )

            ParamSlider(
                label = "Max tokens",
                value = (params.maxTokens ?: defaults.maxTokens ?: 2048).toFloat(),
                isOverridden = params.maxTokens != null,
                valueRange = 256f..8192f,
                format = { it.roundToInt().toString() },
                onValueChange = { onParamsChanged(params.copy(maxTokens = it.roundToInt())) },
            )

            ParamSlider(
                label = "Top-P",
                value = params.topP ?: defaults.topP ?: 1.0f,
                isOverridden = params.topP != null,
                valueRange = 0f..1f,
                format = { "%.2f".format(it) },
                onValueChange = { onParamsChanged(params.copy(topP = (it * 100).roundToInt() / 100f)) },
            )

            ParamSlider(
                label = "Frequency penalty",
                value = params.frequencyPenalty ?: defaults.frequencyPenalty ?: 0f,
                isOverridden = params.frequencyPenalty != null,
                valueRange = 0f..2f,
                format = { "%.1f".format(it) },
                onValueChange = { onParamsChanged(params.copy(frequencyPenalty = (it * 10).roundToInt() / 10f)) },
            )

            ParamSlider(
                label = "Presence penalty",
                value = params.presencePenalty ?: defaults.presencePenalty ?: 0f,
                isOverridden = params.presencePenalty != null,
                valueRange = 0f..2f,
                format = { "%.1f".format(it) },
                onValueChange = { onParamsChanged(params.copy(presencePenalty = (it * 10).roundToInt() / 10f)) },
            )

            if (availableTools.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Tools", style = MaterialTheme.typography.titleSmall)
                availableTools.forEach { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tool.isEnabledByDefault,
                            onCheckedChange = { onToggleTool(tool.id, it) },
                            modifier = Modifier.semantics { contentDescription = "Toggle ${tool.name}" },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    isOverridden: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$label${if (isOverridden) " *" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isOverridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(format(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun PresetPicker(
    presets: List<ParameterPresetEntity>,
    onApplyPreset: (ParameterPresetEntity) -> Unit,
    onSaveAsPreset: (String) -> Unit,
) {
    var showPresetMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savePresetName by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { showPresetMenu = true },
                modifier = Modifier.semantics { contentDescription = "Select preset" },
            ) { Text("Presets") }
            DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            showPresetMenu = false
                            onApplyPreset(preset)
                        },
                    )
                }
            }
        }
        TextButton(onClick = { showSaveDialog = true }) { Text("Save as preset") }
    }

    if (showSaveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Preset") },
            text = {
                OutlinedTextField(
                    value = savePresetName,
                    onValueChange = { savePresetName = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (savePresetName.isNotBlank()) {
                            onSaveAsPreset(savePresetName)
                            savePresetName = ""
                            showSaveDialog = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            },
        )
    }
}
