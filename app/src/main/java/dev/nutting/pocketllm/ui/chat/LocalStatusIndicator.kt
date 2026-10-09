package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.llm.InferenceStatus
import kotlinx.coroutines.delay
import java.util.Locale

/** Label, optional detail line, and progress fraction (null = indeterminate) for a local engine status. */
internal data class LocalStatusText(val label: String, val detail: String? = null, val progress: Float? = null)

internal fun describeLocalStatus(status: InferenceStatus?): LocalStatusText = when (status) {
    null -> LocalStatusText("Starting…")
    InferenceStatus.LoadingModel -> LocalStatusText("Loading model…")
    is InferenceStatus.EncodingImage -> LocalStatusText(
        label = if (status.count > 1) "Reading image ${status.index} of ${status.count}…" else "Reading image…",
        detail = "Images take a while to process on-device",
    )
    is InferenceStatus.ProcessingPrompt -> LocalStatusText(
        label = "Processing prompt…",
        detail = buildString {
            append(String.format(Locale.US, "%,d / %,d tokens", status.done, status.total))
            if (status.cached > 0) append(String.format(Locale.US, " · %,d cached", status.cached))
        },
        progress = if (status.total > 0) status.done.toFloat() / status.total else null,
    )
    is InferenceStatus.Generating -> LocalStatusText("Generating…")
}

internal fun formatElapsed(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

/** Shown in place of the typing dots while an on-device reply has no text yet. */
@Composable
fun LocalStatusIndicator(
    status: InferenceStatus?,
    startedAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    val text = describeLocalStatus(status)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = startedAtMs?.let { ((now - it) / 1000).coerceAtLeast(0) }

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
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (elapsed != null) {
                        Text(
                            formatElapsed(elapsed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                val progress = text.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                text.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
