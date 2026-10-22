package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.remote.image.SdCppCapabilities
import dev.nutting.pocketllm.domain.image.DEFAULT_SIDE
import dev.nutting.pocketllm.domain.image.ImageParameters
import dev.nutting.pocketllm.domain.image.MAX_COUNT

/** Common sizes for current image models (all multiples of 32, about one megapixel). */
enum class ImageSizePreset(val label: String, val width: Int?, val height: Int?) {
    SQUARE("1:1", DEFAULT_SIDE, DEFAULT_SIDE),
    PORTRAIT("3:4", 896, 1152),
    LANDSCAPE("4:3", 1152, 896),
    TALL("9:16", 768, 1344),
    WIDE("16:9", 1344, 768),
    CUSTOM("Custom", null, null);

    companion object {
        /** The preset a saved size matches. No size at all means the app default, 1:1. */
        fun of(width: Int?, height: Int?): ImageSizePreset =
            if (width == null && height == null) SQUARE
            else entries.firstOrNull { it != CUSTOM && it.width == width && it.height == height } ?: CUSTOM
    }
}

/**
 * Image settings for the conversation, shown instead of the chat settings when an image model is selected.
 * Empty fields mean "server default". Fields only stable-diffusion.cpp understands are hidden for other servers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageParametersSheet(
    params: ImageParameters,
    dialect: ImageDialect,
    capabilities: SdCppCapabilities?,
    onParamsChanged: (ImageParameters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val native = dialect == ImageDialect.SD_CPP
    val defaults = capabilities?.imgGenDefaults
    val maxCount = minOf(MAX_COUNT, capabilities?.limits?.maxBatchCount ?: MAX_COUNT).coerceAtLeast(1)
    var customSize by remember { mutableStateOf(ImageSizePreset.of(params.width, params.height) == ImageSizePreset.CUSTOM) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Image Settings", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { customSize = false; onReset() }) { Text("Reset") }
            }
            if (!native) {
                Text(
                    "This server takes only the size and the number of images.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Size", style = MaterialTheme.typography.labelLarge)
            val selected = if (customSize) ImageSizePreset.CUSTOM else ImageSizePreset.of(params.width, params.height)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageSizePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selected == preset,
                        onClick = {
                            customSize = preset == ImageSizePreset.CUSTOM
                            if (preset != ImageSizePreset.CUSTOM) onParamsChanged(params.copy(width = preset.width, height = preset.height))
                        },
                        label = { Text(preset.label) },
                        modifier = Modifier.semantics {
                            contentDescription = if (preset.width != null) "${preset.label}, ${preset.width} by ${preset.height}" else "Custom size"
                        },
                    )
                }
            }
            if (selected == ImageSizePreset.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", params.width, String::toIntOrNull, DEFAULT_SIDE.toString(), Modifier.weight(1f)) {
                        onParamsChanged(params.copy(width = it))
                    }
                    NumberField("Height", params.height, String::toIntOrNull, DEFAULT_SIDE.toString(), Modifier.weight(1f)) {
                        onParamsChanged(params.copy(height = it))
                    }
                }
                Text(
                    "Sizes are rounded to a multiple of 32 and kept within the server's limits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${selected.width} × ${selected.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Images per prompt", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..maxCount).forEach { n ->
                    FilterChip(
                        selected = (params.count ?: 1) == n,
                        onClick = { onParamsChanged(params.copy(count = n.takeIf { it > 1 })) },
                        label = { Text("$n") },
                        modifier = Modifier.semantics { contentDescription = if (n == 1) "1 image" else "$n images" },
                    )
                }
            }

            if (native) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fixedSteps = capabilities?.fixedSteps
                    NumberField(
                        "Steps", if (fixedSteps != null) fixedSteps else params.steps, String::toIntOrNull,
                        defaults?.sampleParams?.sampleSteps?.toString() ?: "Server default", Modifier.weight(1f),
                        enabled = fixedSteps == null,
                        supportingText = fixedSteps?.let { "Fixed by the server's schedule" },
                    ) { onParamsChanged(params.copy(steps = it?.takeIf { s -> s > 0 })) }
                    NumberField(
                        "Guidance (CFG)", params.cfg, String::toFloatOrNull,
                        defaults?.sampleParams?.guidance?.txtCfg?.toString() ?: "Server default", Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal,
                    ) { onParamsChanged(params.copy(cfg = it)) }
                }
                capabilities?.fixedSteps?.let { steps ->
                    Text(
                        "This server always runs $steps steps: it was started with a fixed noise schedule (--sigmas), " +
                            "as Turbo models are. To choose the step count, start sd-server without --sigmas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Sampler", params.sampler, capabilities?.samplers.orEmpty(), defaults?.sampleParams?.sampleMethod, Modifier.weight(1f)) {
                        onParamsChanged(params.copy(sampler = it))
                    }
                    Choice("Scheduler", params.scheduler, capabilities?.schedulers.orEmpty(), defaults?.sampleParams?.scheduler, Modifier.weight(1f)) {
                        onParamsChanged(params.copy(scheduler = it))
                    }
                }
                NumberField("Seed", params.seed, String::toLongOrNull, "Random each time", Modifier.fillMaxWidth()) {
                    onParamsChanged(params.copy(seed = it?.takeIf { s -> s >= 0 }))
                }
                if (params.seed != null) {
                    TextButton(onClick = { onParamsChanged(params.copy(seed = null)) }) { Text("Use a random seed") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A numeric field that keeps what the user is typing (e.g. "7.") while saving the parsed value, and follows
 * changes made elsewhere (Reset, Reuse seed). Empty saves null, meaning the server default.
 */
@Composable
private fun <T : Number> NumberField(
    label: String,
    value: T?,
    parse: (String) -> T?,
    placeholder: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    enabled: Boolean = true,
    supportingText: String? = null,
    onValueChange: (T?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) {
        if (parse(text) != value) text = value?.toString().orEmpty()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val parsed = parse(input.trim())
            if (input.isBlank() || parsed != null) onValueChange(parsed)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        isError = text.isNotBlank() && parse(text.trim()) == null,
        enabled = enabled,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

/** Picks one of the server's options, or its default. Hidden when the server lists none. */
@Composable
private fun Choice(
    label: String,
    value: String?,
    options: List<String>,
    serverDefault: String?,
    modifier: Modifier,
    onChoose: (String?) -> Unit,
) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = serverDefault?.let { "Default ($it)" } ?: "Server default"
    Box(modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: ${value ?: defaultLabel}" },
            ) { Text(value ?: defaultLabel, maxLines = 1) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { expanded = false; onChoose(null) })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onChoose(option) })
            }
        }
    }
}
