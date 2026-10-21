package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.remote.image.imageJson
import dev.nutting.pocketllm.domain.image.ResolvedImageParameters
import kotlinx.coroutines.delay
import java.io.File

/** An assistant reply that is (or will be) generated images: its job, if known, and the images saved so far. */
data class ImageReplyState(val job: ImageJobEntity?, val images: List<GeneratedImageEntity>)

/** What an image reply's buttons do. */
class ImageReplyActions(
    val onOpen: (GeneratedImageEntity) -> Unit,
    val onRetry: (jobId: String) -> Unit,
    val onCancel: (jobId: String) -> Unit,
)

/** Phase text (announced on change), optional detail, and a progress fraction (null = indeterminate). */
internal data class ImageStatusText(val phase: String, val detail: String? = null, val progress: Float? = null)

internal fun describeImageJob(job: ImageJobEntity): ImageStatusText = when (job.status) {
    ImageJobStatus.QUEUED_LOCAL -> ImageStatusText("Starting…")
    ImageJobStatus.SUBMITTED -> ImageStatusText(job.queuePosition?.takeIf { it > 0 }?.let { "Queued (position $it)" } ?: "Queued")
    ImageJobStatus.GENERATING -> {
        val step = job.step
        val total = job.totalSteps
        if (step != null && total != null && total > 0) {
            val pass = job.pass?.takeIf { it > 1 }?.let { " · pass $it" } ?: ""
            ImageStatusText("Generating", "Step $step of $total$pass", (step.toFloat() / total).coerceIn(0f, 1f))
        } else {
            ImageStatusText("Generating")
        }
    }
    ImageJobStatus.SAVING -> ImageStatusText("Saving…")
    ImageJobStatus.SUCCEEDED -> ImageStatusText("Done")
    ImageJobStatus.FAILED -> ImageStatusText("Couldn't generate the image", job.errorMessage)
    ImageJobStatus.CANCELLED -> ImageStatusText("Cancelled", job.errorMessage?.takeIf { it != "Cancelled." })
}

/** "1024×1024 · 8 steps · seed 4417 · 48s": the settings worth knowing to reproduce an image. */
internal fun imageCaption(image: GeneratedImageEntity): String {
    val params = runCatching { imageJson.decodeFromString(ResolvedImageParameters.serializer(), image.paramsJson) }.getOrNull()
    return buildList {
        add("${image.width}×${image.height}")
        params?.steps?.let { add("$it steps") }
        image.seed?.let { add("seed $it") }
        image.durationMs?.let { add(formatElapsed(it / 1000)) }
    }.joinToString(" · ")
}

@Composable
fun ImageReplyContent(
    state: ImageReplyState,
    actions: ImageReplyActions,
    filesDir: File = LocalContext.current.filesDir,
) {
    val job = state.job
    when {
        state.images.isNotEmpty() -> GeneratedImages(state.images, filesDir, actions.onOpen)
        job == null || job.status == ImageJobStatus.SUCCEEDED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BrokenImage, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Image unavailable", style = MaterialTheme.typography.bodyMedium)
        }
        !job.status.isTerminal -> PendingImage(job, actions)
        else -> FinishedWithoutImage(job, actions)
    }
}

@Composable
private fun GeneratedImages(images: List<GeneratedImageEntity>, filesDir: File, onOpen: (GeneratedImageEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.size == 1) {
            val image = images.single()
            ImageThumbnail(image, filesDir, onOpen, Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1), matchHeightConstraintsFirst = false))
        } else {
            images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { image ->
                        ImageThumbnail(image, filesDir, onOpen, Modifier.weight(1f).aspectRatio(1f), crop = true)
                    }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            imageCaption(images.first()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ImageThumbnail(
    image: GeneratedImageEntity,
    filesDir: File,
    onOpen: (GeneratedImageEntity) -> Unit,
    modifier: Modifier,
    crop: Boolean = false,
) {
    Image(
        painter = rememberAsyncImagePainter(File(filesDir, image.filePath)),
        contentDescription = "Generated image: ${image.prompt}. Double-tap to open.",
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Image, onClickLabel = "Open image") { onOpen(image) },
    )
}

@Composable
private fun PendingImage(job: ImageJobEntity, actions: ImageReplyActions) {
    val text = describeImageJob(job)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.phase,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                formatElapsed(((now - job.createdAt) / 1000).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val progress = text.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.detail ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { actions.onCancel(job.id) },
                modifier = Modifier.semantics { contentDescription = "Stop generating this image" },
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                Text("Stop")
            }
        }
    }
}

@Composable
private fun FinishedWithoutImage(job: ImageJobEntity, actions: ImageReplyActions) {
    val text = describeImageJob(job)
    Column {
        Text(
            text.phase,
            style = MaterialTheme.typography.bodyMedium,
            color = if (job.status == ImageJobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        text.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        TextButton(onClick = { actions.onRetry(job.id) }) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
            Text(if (job.status == ImageJobStatus.FAILED) "Retry" else "Try again")
        }
    }
}
