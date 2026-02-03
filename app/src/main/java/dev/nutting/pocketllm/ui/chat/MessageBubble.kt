package dev.nutting.pocketllm.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.nutting.pocketllm.data.local.entity.MessageEntity

@Composable
fun MessageBubble(
    message: MessageEntity,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = alignment,
    ) {
        Text(
            text = if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .semantics {
                    contentDescription = "${if (isUser) "You" else "Assistant"}: ${message.content}"
                },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser && message.thinkingContent != null) {
                    ThinkingSection(
                        thinkingContent = message.thinkingContent,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Markdown(
                        content = message.content,
                        colors = markdownColor(),
                        typography = markdownTypography(),
                    )
                }
                if (!isUser && message.totalTokens != null) {
                    TokenUsageFooter(message = message)
                }
            }
        }
    }
}

@Composable
private fun TokenUsageFooter(message: MessageEntity) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = "${message.totalTokens} tokens",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
        AnimatedVisibility(visible = expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message.promptTokens?.let {
                    Text(
                        "Prompt: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                message.completionTokens?.let {
                    Text(
                        "Completion: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingMessageBubble(
    content: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(
                text = content + "\u258c",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
