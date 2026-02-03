package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
