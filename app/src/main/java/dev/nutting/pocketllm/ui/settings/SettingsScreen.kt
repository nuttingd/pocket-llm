package dev.nutting.pocketllm.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.BuildConfig
import dev.nutting.pocketllm.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToServers: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Servers
            SectionHeader("Servers")

            Surface(
                onClick = onNavigateToServers,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Manage servers", style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Theme
            SectionHeader("Appearance")

            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            val themes = listOf("light", "dark", "system")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = state.themeMode == theme,
                        onClick = { viewModel.setThemeMode(theme) },
                        shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                    ) {
                        Text(theme.replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dynamic colors (Material You)")
                Switch(
                    checked = state.dynamicColorEnabled,
                    onCheckedChange = viewModel::setDynamicColorEnabled,
                )
            }

            SliderSetting(
                label = "Message font size",
                value = state.messageFontSizeSp.toFloat(),
                valueRange = 12f..24f,
                steps = 5,
                valueLabel = "${state.messageFontSizeSp} sp",
                onValueChange = { viewModel.setMessageFontSizeSp(it.roundToInt()) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Generation Defaults
            SectionHeader("Generation Defaults")

            OutlinedTextField(
                value = state.defaultSystemPrompt,
                onValueChange = viewModel::setDefaultSystemPrompt,
                label = { Text("Default system prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )

            SliderSetting(
                label = "Temperature",
                value = state.defaultTemperature,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultTemperature),
                onValueChange = { viewModel.setDefaultTemperature((it * 10).roundToInt() / 10f) },
            )

            SliderSetting(
                label = "Max tokens",
                value = state.defaultMaxTokens.toFloat(),
                valueRange = 256f..8192f,
                valueLabel = "${state.defaultMaxTokens}",
                onValueChange = { viewModel.setDefaultMaxTokens(it.roundToInt()) },
            )

            SliderSetting(
                label = "Top-P",
                value = state.defaultTopP,
                valueRange = 0f..1f,
                valueLabel = "%.2f".format(state.defaultTopP),
                onValueChange = { viewModel.setDefaultTopP((it * 100).roundToInt() / 100f) },
            )

            SliderSetting(
                label = "Frequency penalty",
                value = state.defaultFrequencyPenalty,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultFrequencyPenalty),
                onValueChange = { viewModel.setDefaultFrequencyPenalty((it * 10).roundToInt() / 10f) },
            )

            SliderSetting(
                label = "Presence penalty",
                value = state.defaultPresencePenalty,
                valueRange = 0f..2f,
                valueLabel = "%.1f".format(state.defaultPresencePenalty),
                onValueChange = { viewModel.setDefaultPresencePenalty((it * 10).roundToInt() / 10f) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Advanced
            SectionHeader("Advanced")

            SliderSetting(
                label = "Compaction threshold",
                value = state.compactionThresholdPct.toFloat(),
                valueRange = 50f..95f,
                steps = 8,
                valueLabel = "${state.compactionThresholdPct}%",
                onValueChange = { viewModel.setCompactionThresholdPct(it.roundToInt()) },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // About
            SectionHeader("About")

            Surface(
                onClick = { showAboutDialog = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("About Pocket LLM", style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private data class AboutLibrary(val name: String, val description: String, val url: String)

private val aboutLibraries = listOf(
    AboutLibrary("Jetpack Compose", "Modern declarative UI toolkit for Android", "https://developer.android.com/compose"),
    AboutLibrary("Material 3", "Material Design components for Compose", "https://m3.material.io/"),
    AboutLibrary("Room", "SQLite object-mapping library", "https://developer.android.com/jetpack/androidx/releases/room"),
    AboutLibrary("Ktor Client", "Multiplatform HTTP client framework", "https://ktor.io/"),
    AboutLibrary("Tink", "Multi-language, cross-platform cryptographic library", "https://developers.google.com/tink"),
    AboutLibrary("Multiplatform Markdown Renderer", "Markdown rendering for Compose", "https://github.com/mikepenz/multiplatform-markdown-renderer"),
)

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.app_github_url)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        },
        title = { Text("Pocket LLM") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.app_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                        )
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("View on GitHub")
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Libraries",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    aboutLibraries.forEach { lib ->
                        Column(
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(lib.url))
                                )
                            },
                        ) {
                            Text(
                                lib.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                lib.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
