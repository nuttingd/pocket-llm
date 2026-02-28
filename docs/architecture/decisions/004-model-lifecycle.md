# ADR 004: Model Lifecycle Management

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/inference-architecture.md

---

## Decision

Local GGUF models undergo a complete lifecycle:
1. **Discovery** - Find models on the web
2. **Download** - With resume support via WorkManager
3. **Storage** - In app external files directory
4. **Metadata tracking** - Store in DataStore Preferences
5. **Selection** - Choose model per conversation
6. **Loading** - Load into llama.cpp with GPU offload
7. **Inference** - Generate tokens
8. **Unloading** - Free memory on pressure or idle

---

## Status

**Approved** - This lifecycle is in effect.

---

## Rationale

### Why Not Just Bundle Models?

| Bundled | On-Demand Download |
|---------|-------------------|
| Large APK size | Small initial download |
| No model variety | User chooses models |
| Hard to update | Easy updates |

**Verdict**: Downloadable models give users flexibility while keeping app size small.

### Why DataStore Preferences?

| Room Database | DataStore Preferences |
|---------------|----------------------|
| Complex queries needed | Simple key-value |
| Overkill for metadata | Perfect for model list |

**Verdict**: DataStore is lightweight and perfect for model metadata storage.

---

## Lifecycle States

```kotlin
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    COMPLETE,
    FAILED
}

data class LocalModel(
    val id: String,
    val name: String,
    val modelFileName: String,
    val downloadStatus: DownloadStatus,
    val downloadedBytes: Long,
    // ... other metadata
)
```

---

## Key Behaviors

### Download with Resume
- Use WorkManager for background downloads
- Track progress in bytes
- Support resuming partial downloads
- Handle network interruptions gracefully

### GPU Offload Control
- Configurable percentage (default 80%)
- Applied at model load time
- Adjusted dynamically based on memory pressure

### Memory Pressure Handling
- Monitor device memory
- Unload least-recently-used models
- Gracefully degrade when insufficient RAM

---

## Trade-offs

### Pros
- Users choose which models to download
- App stays small (only UI)
- Models can be updated independently

### Cons
- Initial download requires internet
- Storage space needed for models
- Memory management complexity

**Mitigation**: Clear error messages, progress indicators, and graceful degradation.

---

## Implementation Requirements

### New Model Download Flow

1. **Discover model** (from GGUF file URL)
2. **Validate model** (parse GGUF header)
3. **Download with WorkManager**
4. **Store metadata** in DataStore
5. **Enable in UI** for selection

### Model Load Flow

```kotlin
// Before inference:
ensureModelLoaded(modelId) {
    val model = localModelStore.getById(modelId)
    
    // Check if already loaded
    if (llmEngine.isReady() && loadedModelId == modelId) return
    
    // Unload previous if needed
    if (llmEngine.isReady()) llmEngine.unload()
    
    // Load new model
    llmEngine.loadModel(
        modelPath,
        gpuOffloadPercent = localModelStore.gpuOffloadPercent
    )
}
```

---

## Compliance

All model operations MUST:

- [ ] Use WorkManager for downloads (not raw threads)
- [ ] Store metadata in DataStore Preferences
- [ ] Handle download failures gracefully
- [ ] Unload models on low memory
- [ ] Support GPU offload configuration

**Enforcement**: Code review + integration tests.

---

## Future Enhancements

Potential additions to the lifecycle:
1. **Model caching** - Pre-load likely-needed models
2. **Smart swapping** - Predictive model selection based on usage patterns
3. **Compression** - Compress downloaded GGUF files
4. **Verification** - Cryptographic verification of downloads

These are M3 features (Predictive Loading).
