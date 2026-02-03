package dev.nutting.pocketllm.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter

@Composable
fun MessageInput(
    isStreaming: Boolean,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    modifier: Modifier = Modifier,
    onSendMessageWithImages: ((String, List<Uri>) -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val attachedImages = remember { mutableStateListOf<Uri>() }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        attachedImages.addAll(uris)
    }

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (attachedImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(attachedImages.toList()) { uri ->
                        ImageThumbnail(
                            uri = uri,
                            onRemove = { attachedImages.remove(uri) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!isStreaming) {
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Attach image" },
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    placeholder = { Text("Type a message...") },
                    enabled = !isStreaming,
                    maxLines = 6,
                )
                if (isStreaming) {
                    IconButton(
                        onClick = onStopGeneration,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Stop generating" },
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (text.isNotBlank() || attachedImages.isNotEmpty()) {
                                if (attachedImages.isNotEmpty() && onSendMessageWithImages != null) {
                                    onSendMessageWithImages(text.trim(), attachedImages.toList())
                                } else {
                                    onSendMessage(text.trim())
                                }
                                text = ""
                                attachedImages.clear()
                            }
                        },
                        enabled = text.isNotBlank() || attachedImages.isNotEmpty(),
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Send message" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnail(
    uri: Uri,
    onRemove: () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        Image(
            painter = rememberAsyncImagePainter(uri),
            contentDescription = "Attached image",
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Icon(
            Icons.Default.Close,
            contentDescription = "Remove image",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clickable(onClick = onRemove),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
