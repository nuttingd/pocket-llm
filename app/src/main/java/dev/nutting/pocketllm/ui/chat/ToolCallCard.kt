package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.remote.model.ToolCall

@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    status: ToolCallStatus = ToolCallStatus.Pending,
    onApprove: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "Tool call: ${toolCall.function.name}" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Tool: ${toolCall.function.name}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                toolCall.function.arguments,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (status) {
                ToolCallStatus.Pending -> {
                    if (onApprove != null && onDecline != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.semantics { contentDescription = "Approve tool call" },
                            ) { Text("Approve") }
                            OutlinedButton(
                                onClick = onDecline,
                                modifier = Modifier.semantics { contentDescription = "Decline tool call" },
                            ) { Text("Decline") }
                        }
                    }
                }
                ToolCallStatus.Running -> {
                    Text(
                        "Running...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                is ToolCallStatus.Complete -> {
                    Text(
                        "Result: ${status.result.take(200)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                is ToolCallStatus.Error -> {
                    Text(
                        "Error: ${status.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

sealed interface ToolCallStatus {
    data object Pending : ToolCallStatus
    data object Running : ToolCallStatus
    data class Complete(val result: String) : ToolCallStatus
    data class Error(val error: String) : ToolCallStatus
}
