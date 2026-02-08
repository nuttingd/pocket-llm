# Data Model: Local LLM Inference

**Branch**: `002-local-llm-inference` | **Date**: 2026-02-07

## DataStore Preferences (Local Model Metadata)

Local model metadata is stored in DataStore Preferences as a JSON-serialized list. This avoids adding Room entities and migrations for what is essentially a flat list of <10 items.

### Serialized Data: LocalModel

Stored under key `local_models` as a JSON array string.

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | Unique model identifier (registry ID or generated UUID for imports) |
| `name` | String | Display name (e.g., "SmolVLM2 2.2B") |
| `parameterCount` | String | Human-readable (e.g., "2.2B") |
| `quantization` | String | Quantization level (e.g., "Q4_K_M") |
| `modelFileName` | String | GGUF file name on disk |
| `projectorFileName` | String | Projector GGUF file name (empty string if none) |
| `modelSizeBytes` | Long | Expected model file size |
| `projectorSizeBytes` | Long | Expected projector file size (0 if none) |
| `downloadStatus` | String (enum) | `NOT_DOWNLOADED`, `DOWNLOADING`, `COMPLETE`, `FAILED` |
| `downloadedBytes` | Long | Bytes downloaded so far (for resume progress display) |
| `sourceUrl` | String? | Download URL for model file (null for imports) |
| `projectorSourceUrl` | String? | Download URL for projector file (null for imports) |
| `isImported` | Boolean | True if user-imported, false if from registry |
| `minimumRamMb` | Int | Minimum device RAM in MB to run this model |
| `contextWindowSize` | Int | User-configurable context window (default 2048) (FR-231) |

**Kotlin Data Class**:

```kotlin
@Serializable
data class LocalModel(
    val id: String,
    val name: String,
    val parameterCount: String,
    val quantization: String,
    val modelFileName: String,
    val projectorFileName: String,
    val modelSizeBytes: Long,
    val projectorSizeBytes: Long,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val sourceUrl: String? = null,
    val projectorSourceUrl: String? = null,
    val isImported: Boolean = false,
    val minimumRamMb: Int = 4096,
    val contextWindowSize: Int = 2048,
) {
    val totalSizeBytes: Long get() = modelSizeBytes + projectorSizeBytes
}

@Serializable
enum class DownloadStatus {
    NOT_DOWNLOADED, DOWNLOADING, COMPLETE, FAILED
}
```

---

## DataStore Preferences (New Keys for Settings)

Added to existing `SettingsDataStore`:

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `local_models` | String (JSON) | "[]" | Serialized `List<LocalModel>` |
| `active_local_model_id` | String | "" | Currently selected local model ID |
| `gpu_offload_percent` | Int | 0 | GPU layer offload 0-100% (FR-227) |
| `inference_provider_type` | String | "remote" | `"remote"` or `"local"` — which provider is active |

---

## Model Registry (Hardcoded)

Bundled with the app, not persisted. Defines available models for download.

```kotlin
data class ModelRegistryEntry(
    val id: String,
    val name: String,
    val description: String,
    val parameterCount: String,
    val quantization: String,
    val modelDownloadUrl: String,
    val projectorDownloadUrl: String,
    val modelFileName: String,
    val projectorFileName: String,
    val modelSizeBytes: Long,
    val projectorSizeBytes: Long,
    val minimumRamMb: Int,
)
```

**Seed Data** (ported from snacktrack):

| Name | Params | Quant | Model Size | Projector Size | Min RAM |
|------|--------|-------|------------|----------------|---------|
| SmolVLM2 2.2B Instruct | 2.2B | Q4_K_M | 1.11 GB | 593 MB | 4 GB |
| Qwen3-VL 4B Instruct | 4B | Q4_K_M | 2.5 GB | 454 MB | 6 GB |
| Gemma 3 4B IT | 4B | Q4_K_M | 2.49 GB | 851 MB | 6 GB |

All downloaded from HuggingFace (ggml-org / model maintainer repositories).

---

## Message Entity Changes

The existing `MessageEntity` already has `serverProfileId` and `modelId` columns. For local inference, we need to distinguish local vs remote messages.

**New column on MessageEntity** (Room migration 6→7):

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `isLocalInference` | Boolean | NOT NULL, default false | True if generated on-device (FR-242) |

This column enables the UI to show a "local" indicator on messages. Existing messages default to `false` (remote).

---

## File Storage Layout

Model files stored in app-private external storage (FR-216):

```
{getExternalFilesDir(null)}/models/
├── smolvlm2-2.2b-instruct-q4_k_m.gguf
├── smolvlm2-2.2b-instruct-mmproj-f16.gguf
├── qwen3-vl-4b-instruct-q4_k_m.gguf
├── qwen3-vl-4b-instruct-mmproj-q8_0.gguf
├── gemma-3-4b-it-q4_k_m.gguf
├── gemma-3-4b-it-mmproj-q8_0.gguf
└── {imported-model-files}.gguf
```

---

## ER Diagram Update

```
ServerProfile 1──────0..* Conversation
                          │
                          ├──1..* Message (tree via parentMessageId)
                          │       └── isLocalInference (new field)
                          ├──0..* CompactionSummary
                          └──0..* ConversationToolEnabled ──* ToolDefinition

ParameterPreset (standalone, applied to Conversation or global defaults)

LocalModel (DataStore, standalone - not in Room)
  └── Selected via SettingsDataStore.active_local_model_id
```

---

## GGUF Validation

Files are validated by checking the first 4 bytes for the GGUF magic number:

```
Magic bytes: 0x47 0x47 0x55 0x46 (little-endian: 0x46554747)
ASCII: "GGUF"
```

Validation is performed:
- After download completes (FR-203)
- Before accepting imported files (FR-212)
- On startup when checking for corrupt files
