package dev.nutting.pocketllm.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.nutting.pocketllm.data.local.entity.MessageEntity

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 16,
    onCopy: ((String) -> Unit)? = null,
    onRegenerate: ((MessageEntity) -> Unit)? = null,
    onEdit: ((MessageEntity) -> Unit)? = null,
    onDelete: ((MessageEntity) -> Unit)? = null,
    branchInfo: BranchInfo? = null,
    onNavigateBranch: ((String, Int) -> Unit)? = null,
) {
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
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
        var showMenu by remember { mutableStateOf(false) }

        Box {
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
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    )
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
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                        )
                    }
                    message.imageUris?.split("|")?.forEach { dataUrl ->
                        Image(
                            painter = rememberAsyncImagePainter(dataUrl),
                            contentDescription = "Attached image, tap to preview",
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { previewImageUrl = dataUrl }
                                .padding(top = 4.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else {
                    Markdown(
                        content = message.content,
                        colors = markdownColor(),
                        typography = markdownTypography(
                            text = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                            paragraph = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                        ),
                    )
                }
                if (!isUser && message.totalTokens != null) {
                    TokenUsageFooter(message = message)
                }
            }
        }

            MessageActionMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                isUser = isUser,
                onCopy = { onCopy?.invoke(message.content); showMenu = false },
                onRegenerate = { onRegenerate?.invoke(message); showMenu = false },
                onEdit = { onEdit?.invoke(message); showMenu = false },
                onDelete = { onDelete?.invoke(message); showMenu = false },
            )
        }

        if (branchInfo != null && branchInfo.totalSiblings > 1) {
            BranchNavigator(
                branchInfo = branchInfo,
                onNavigate = { offset -> onNavigateBranch?.invoke(message.parentMessageId ?: "", offset) },
            )
        }
    }

    if (previewImageUrl != null) {
        ImagePreviewDialog(
            imageUrl = previewImageUrl!!,
            onDismiss = { previewImageUrl = null },
        )
    }
}

@Composable
private fun ImagePreviewDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberAsyncImagePainter(imageUrl),
                contentDescription = "Full-screen image preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

data class BranchInfo(
    val currentIndex: Int,
    val totalSiblings: Int,
)

@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isUser: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = onCopy,
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )
        if (!isUser) {
            DropdownMenuItem(
                text = { Text("Regenerate") },
                onClick = onRegenerate,
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
        if (isUser) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = onEdit,
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = onDelete,
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )
    }
}

@Composable
private fun BranchNavigator(
    branchInfo: BranchInfo,
    onNavigate: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(0.85f),
    ) {
        IconButton(
            onClick = { onNavigate(-1) },
            enabled = branchInfo.currentIndex > 0,
            modifier = Modifier.semantics { contentDescription = "Previous branch" },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(
            "${branchInfo.currentIndex + 1} of ${branchInfo.totalSiblings}",
            style = MaterialTheme.typography.labelSmall,
        )
        IconButton(
            onClick = { onNavigate(1) },
            enabled = branchInfo.currentIndex < branchInfo.totalSiblings - 1,
            modifier = Modifier.semantics { contentDescription = "Next branch" },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
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
