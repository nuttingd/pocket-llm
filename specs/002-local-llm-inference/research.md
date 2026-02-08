# Research: Local LLM Inference

**Branch**: `002-local-llm-inference` | **Date**: 2026-02-07

## 1. Native Inference Engine (llama.cpp)

**Decision**: Port the existing llama.cpp + JNI bridge from snacktrack's `llm/` module

**Rationale**:
- Already proven in production in the snacktrack app
- llama.cpp is the de facto standard for on-device GGUF inference on mobile
- JNI bridge already handles: model loading, multimodal inference (mtmd), text-only inference, cancel, unload, progress callbacks, crash recovery (SIGSEGV/SIGBUS)
- Supports GGML backends: CPU (multiple ARM variants), Vulkan GPU
- Dynamic backend loading (`GGML_BACKEND_DL=ON`) allows runtime detection of GPU support
- Flash attention enabled for performance
- Snacktrack's grammar-constrained JSON output is NOT needed — pocket-llm uses free-form chat generation; this code will be removed during the port

**Port Changes Required**:
- Rename JNI package from `dev.nutting.snacktrack.llm.LlmEngine` to `dev.nutting.pocketllm.llm.LlmEngine`
- Rename shared library from `libsnacktrack-llm.so` to `libpocketllm-llm.so`
- Remove JSON grammar constraint from `nativeInfer()` and `nativeInferText()` — use unconstrained generation
- Remove NutritionExtraction-specific JSON schema
- Add context window size parameter to `nativeLoadModel()` (currently hardcoded to 2048)
- Expose chat template application as-is (already in JNI bridge)
- Keep crash signal handlers, progress callbacks, cancel support, GGUF metadata reading

**Alternatives Considered**:
- MediaPipe LLM Inference: Google's on-device LLM solution. Limited model format support (TFLite only), no GGUF support, no GPU offload control, limited model selection.
- ONNX Runtime Mobile: Different model format, not compatible with the GGUF ecosystem.
- Starting from scratch with llama.cpp: Unnecessary — snacktrack's bridge is battle-tested.

**Dependencies**:
- llama.cpp as git submodule at `external/llama.cpp`
- NDK for native compilation (arm64-v8a)
- CMake for native build

---

## 2. Module Architecture: Single Module vs Library Module

**Decision**: Inline the native code into the existing `app/` module (no separate `llm/` library module)

**Rationale**:
- Constitution principle I mandates a single Gradle module (`app/`)
- Snacktrack uses a separate `llm/` library module, but pocket-llm's constitution explicitly forbids multi-module: "The app MUST remain a single Gradle module (`app/`). Feature separation MUST be achieved through packages, not modules."
- The JNI bridge, CMakeLists.txt, and C++ source files can live under `app/src/main/cpp/` instead of a separate module
- Package separation within `app/`: `dev.nutting.pocketllm.llm` for the engine, `dev.nutting.pocketllm.data.local.model` for LocalModel data, `dev.nutting.pocketllm.ui.modelmanagement` for the UI

**Alternatives Considered**:
- Separate `:llm` module (matching snacktrack): Violates constitution principle I. Would require a documented exception in Complexity Tracking.

---

## 3. Model Download Mechanism

**Decision**: OkHttp for model downloads (NOT Ktor), with WorkManager for background execution

**Rationale**:
- Ktor Client is already in the project for API calls, but model downloads need: resume support (HTTP Range headers), raw byte streaming for large files (1-3 GB), and progress tracking — OkHttp handles these more naturally for file downloads
- WorkManager provides: background execution surviving app kill, foreground service notifications, automatic retry with constraints, observable progress via `WorkInfo`
- Snacktrack already uses OkHttp + WorkManager for this — proven pattern
- OkHttp is already a transitive dependency (Ktor uses the OkHttp engine)

**Alternatives Considered**:
- Ktor for downloads: Possible but awkward for resume (manual Range header management), less proven for multi-GB file streaming.
- DownloadManager (system service): Less control over progress reporting, no GGUF validation integration, harder to cancel.

**Dependencies**:
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.work:work-runtime-ktx:2.11.0")
```

---

## 4. Local Model Metadata Storage

**Decision**: DataStore Preferences with JSON serialization (matching snacktrack's `LocalModelStore`)

**Rationale**:
- Local model metadata is small (registry + download status for a handful of models)
- DataStore Preferences is already used in the project for settings
- JSON serialization of `List<LocalModel>` via kotlinx-serialization is simple and sufficient
- Room is overkill for this — we're storing a flat list of ~10 models at most, not relational data
- Model metadata includes: download status, file paths, progress — all simple key-value data

**Alternatives Considered**:
- Room entity: Adds migration complexity to the existing database for a simple flat list. Not justified for <10 items.
- Proto DataStore: Overkill, would require protobuf schema definition.

---

## 5. Integration with ChatManager

**Decision**: Introduce an `InferenceProvider` interface to abstract local vs remote inference behind a common streaming API

**Rationale**:
- ChatManager currently calls `OpenAiApiClient.streamChatCompletion()` directly
- Local inference needs to produce the same `Flow<ChatCompletionChunk>` output format so ChatManager's tool calling, compaction, and message-saving logic works unchanged
- The `InferenceProvider` interface has two implementations: `RemoteInferenceProvider` (wraps existing OpenAiApiClient) and `LocalInferenceProvider` (wraps LlmEngine)
- ChatManager receives the provider from ChatViewModel based on the currently selected model type
- This is the minimum abstraction needed — Constitution principle II: "Repository and service layers MUST exist only when they serve more than one consumer or isolate a testable boundary"
- The interface isolates a testable boundary (mock inference in ChatManager tests) AND serves two consumers (remote and local)

**Alternatives Considered**:
- No interface, if/else in ChatManager: Would tightly couple ChatManager to both API client and LLM engine, making testing harder.
- Full strategy pattern with factory: Overkill — only two implementations.

---

## 6. GPU Offload & Device Capability Detection

**Decision**: Port snacktrack's approach — `nativeDeviceInfo()` JNI call for Vulkan detection, percentage-based GPU layer offload

**Rationale**:
- Already implemented and proven in snacktrack
- `nativeDeviceInfo()` checks for Vulkan backend availability at runtime
- GPU offload is expressed as a percentage (0-100%) converted to layer count: `n_gpu_layers = (total_layers * percent) / 100`
- Stored in SettingsDataStore as an integer preference
- Default: 0% (CPU-only) — safer default for pocket-llm since not all users will have Vulkan-capable devices. Users can enable GPU and increase the percentage in settings.

**Alternatives Considered**:
- Auto-detect and enable GPU: Risky — some Vulkan drivers are buggy on budget devices. Better to let users opt in.
- Fixed GPU layers instead of percentage: Less intuitive for users, different per model.

---

## Summary

| Area | Decision | Source |
|------|----------|--------|
| Inference Engine | llama.cpp via JNI (ported from snacktrack) | Existing code |
| Module Structure | Single `app/` module per constitution | Constitution I |
| Model Downloads | OkHttp + WorkManager | snacktrack pattern |
| Model Metadata | DataStore Preferences + JSON | Existing DataStore |
| Chat Integration | InferenceProvider interface | New abstraction |
| GPU Control | Percentage-based layer offload via JNI | snacktrack pattern |
