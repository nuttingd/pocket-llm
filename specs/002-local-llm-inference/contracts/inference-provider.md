# Internal Contract: InferenceProvider Interface

**Branch**: `002-local-llm-inference` | **Date**: 2026-02-07

This contract defines the internal interface between ChatManager and the inference backends (remote API and local llama.cpp). This is NOT an external API — it's an internal abstraction boundary.

---

## Interface: InferenceProvider

ChatManager delegates inference to an `InferenceProvider`. The provider returns the same `Flow<ChatCompletionChunk>` format regardless of whether inference happens remotely or locally.

```kotlin
interface InferenceProvider {
    /**
     * Stream a chat completion response.
     * Returns a Flow of ChatCompletionChunk (same format as OpenAI SSE chunks).
     * Local inference emits chunks synthetically to match the remote format.
     */
    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk>

    /**
     * Non-streaming chat completion (used for title generation, compaction summaries).
     */
    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse

    /**
     * Fetch available models.
     * Remote: calls /v1/models endpoint.
     * Local: returns list of downloaded local models.
     */
    suspend fun fetchModels(): List<ModelInfo>

    /**
     * Cancel any in-progress inference.
     * Remote: cancels the HTTP connection.
     * Local: sets the cancel flag in the native engine.
     */
    fun cancel()
}
```

---

## Implementation: RemoteInferenceProvider

Wraps the existing `OpenAiApiClient`. Thin adapter — delegates directly.

```kotlin
class RemoteInferenceProvider(
    private val apiClient: OpenAiApiClient,
    private val serverProfile: ServerProfileEntity,
    private val apiKey: String?,
) : InferenceProvider {
    // Delegates to apiClient.streamChatCompletion(), apiClient.chatCompletion(), etc.
}
```

---

## Implementation: LocalInferenceProvider

Wraps `LlmEngine`. Converts token-by-token JNI callbacks into `Flow<ChatCompletionChunk>`.

```kotlin
class LocalInferenceProvider(
    private val llmEngine: LlmEngine,
    private val localModel: LocalModel,
    private val modelsDir: File,
) : InferenceProvider {

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> {
        return channelFlow {
            // 1. Ensure model is loaded (ensureModelLoaded())
            // 2. Build prompt from request.messages using chat template
            // 3. Call llmEngine.inferText(prompt, request.maxTokens)
            //    OR llmEngine.infer(imageBytes, prompt, maxTokens) for vision
            // 4. Collect llmEngine.progress flow
            // 5. For each token, emit a ChatCompletionChunk with:
            //    - delta.content = tokenText
            //    - finishReason = null (until done)
            // 6. On completion, emit final chunk with finishReason = "stop"
            //    and usage = estimated token counts
        }
    }
}
```

### Chunk Format (synthetic, matching OpenAI SSE)

Each token emitted as:
```json
{
  "id": "local-{uuid}",
  "model": "{localModel.id}",
  "choices": [{
    "index": 0,
    "delta": { "content": "{token}" },
    "finish_reason": null
  }]
}
```

Final chunk:
```json
{
  "id": "local-{uuid}",
  "model": "{localModel.id}",
  "choices": [{
    "index": 0,
    "delta": {},
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": {estimated},
    "completion_tokens": {actual_count},
    "total_tokens": {sum}
  }
}
```

---

## Contract: Model Downloads (HuggingFace)

Model files are downloaded from HuggingFace via direct HTTPS URLs.

```
GET https://huggingface.co/{repo}/resolve/main/{filename}
Range: bytes={resumeFrom}-    (for resume support, FR-202)
```

### Response
- 200 OK: Full file (new download)
- 206 Partial Content: Resumed download from offset
- Content-Type: application/octet-stream

### GGUF Validation
After download, validate first 4 bytes:
```
Expected: 0x47 0x47 0x55 0x46 (ASCII "GGUF")
```

If validation fails, delete the file and report failure.

---

## Contract: JNI Native Methods

These are the JNI methods exposed by `llm_jni.cpp` to `LlmEngine.kt`:

| Method | Parameters | Returns | Notes |
|--------|------------|---------|-------|
| `nativeInit` | `backendPaths: Array<String>` | void | Initialize GGML backends |
| `nativeLoadModel` | `modelPath, projectorPath, nThreads, gpuOffloadPercent, contextSize` | Int (0=ok) | Load model + optional projector |
| `nativeInfer` | `imageBytes, prompt, maxTokens` | String | Vision + text inference |
| `nativeInferText` | `prompt, maxTokens` | String | Text-only inference |
| `nativeCancel` | (none) | void | Cancel in-progress generation |
| `nativeUnload` | (none) | void | Free model resources |
| `nativeSystemInfo` | (none) | String | GGML system info |
| `nativeDeviceInfo` | (none) | String | GPU/CPU capability info |
| `nativePerfInfo` | (none) | String | Inference performance metrics |

### Progress Callback (JNI → Kotlin)

Called by native code during inference:
```kotlin
fun onNativeProgress(phase: String, tokens: Int, tokenText: String)
```

Phases: `"image_processing"`, `"prompt_eval"`, `"generating"`

### Error Return Codes (nativeLoadModel)

| Code | Meaning |
|------|---------|
| 0 | Success |
| -1 | Engine poisoned (previous crash, needs unload first) |
| 1 | Model file load failed |
| 2 | Context creation failed |
| 3 | Projector load failed |
