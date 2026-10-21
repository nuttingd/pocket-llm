package dev.nutting.pocketllm.ui.image

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.rememberAsyncImagePainter
import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import kotlinx.coroutines.launch
import java.io.File

/** Full-screen view of a generated image with pinch-to-zoom, Edit, Reuse seed, Save to gallery and Share. */
@Composable
fun ImageViewer(
    image: GeneratedImageEntity,
    onDismiss: () -> Unit,
    onReuseSeed: ((Long) -> Unit)? = null,
    onEdit: ((File) -> Unit)? = null,
) {
    val context = LocalContext.current
    val file = remember(image.filePath) { File(context.filesDir, image.filePath) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    fun save() = scope.launch {
        val message = try {
            ImageExport.saveToGallery(context, file, image.mimeType)
            "Saved to Pictures/Pocket LLM"
        } catch (e: Exception) {
            "Couldn't save the image: ${e.message}"
        }
        snackbar.showSnackbar(message)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else scope.launch { snackbar.showSnackbar("Storage permission is needed to save images") }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                painter = rememberAsyncImagePainter(file),
                contentDescription = "Generated image: ${image.prompt}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                    }
                    .transformable(transform)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                Row {
                    if (onEdit != null) {
                        IconButton(onClick = { onEdit(file) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit this image", tint = Color.White)
                        }
                    }
                    val seed = image.seed
                    if (onReuseSeed != null && seed != null) {
                        IconButton(onClick = {
                            onReuseSeed(seed)
                            scope.launch { snackbar.showSnackbar("Seed $seed will be used for the next images") }
                        }) { Icon(Icons.Default.Casino, contentDescription = "Reuse seed $seed", tint = Color.White) }
                    }
                    IconButton(onClick = {
                        val needsPermission = ImageExport.needsStoragePermission &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
                    }) { Icon(Icons.Default.Download, contentDescription = "Save to gallery", tint = Color.White) }
                    IconButton(onClick = {
                        context.startActivity(ImageExport.shareIntent(context, file, image.mimeType, image.prompt))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share image", tint = Color.White) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp)) {
                Text(
                    image.prompt,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                SnackbarHost(snackbar)
            }
        }
    }
}
