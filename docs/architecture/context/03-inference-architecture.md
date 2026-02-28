# Inference Architecture

**Last Updated**: 2026-02-27  
**Reference**: ADR 002 - Dual Inference Pipeline

---

## Overview

Pocket LLM supports **two inference backends** that share a unified interface:

1. **Remote API** - Connect to any OpenAI-compatible server
2. **Local Model** - Run GGUF models on-device via llama.cpp

The `ChatManager` coordinates both paths through a single streaming interface.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        ChatManager                          │
│  (Coordinates chat flow, persists messages, handles tools)  │
└─────────────────┬───────────────────────────────────────────┘
                  │
        ┌─────────┴─────────┐
        │                   │
        ▼                   ▼
┌──────────────┐   ┌────────────────┐
│ OpenAiApiClient │   │ LocalLlmClient    │
│  (Remote API)     │   │  (llama.cpp JNI) │
└──────┬───────┘   └────────┬─────────┘
       │                    │
       ▼                    ▼
┌────────────────┐   ┌──────────────────────┐
│ Remote Server    │   │ llama.cpp + ggml     │
│  (Ollama, etc.)   │   │  (CPU/GPU)           │
└────────────────┘   └──────────────────────┘
```

---

## OpenAiApiClient

### Responsibilities
- Connect to remote server via Ktor
- Stream SSE responses as `Flow<ChatCompletionChunk>`
- Handle authentication, timeouts, retries

### Key Methods
```kotlin
fun streamChatCompletion(
    baseUrl: String,
    apiKey: String?,
    timeoutSeconds: Long,
    request: ChatCompletionRequest
): Flow<ChatCompletionChunk>

fun fetchModels(
    baseUrl: String,
    apiKey: String?,
    timeoutSeconds: Long
): List<ModelInfo>
```

---

## LocalLlmClient

### Responsibilities
- Bridge to llama.cpp via JNI (`LlmEngine`)
- Load/unload models on demand
- Stream token-by-token from local inference

### Key Methods
```kotlin
fun streamChatCompletion(
    messages: List<ChatMessage>,
    temperature: Float?,
    maxTokens: Int?,
    topP: Float?
): Flow<ChatCompletionChunk>

suspend fun ensureModelLoaded(modelId: String)
```

---

## ChatManager Integration

The `ChatManager` is the consumer that unifies both backends:

```kotlin
// Select source based on server ID
val chunkFlow = if (isLocal) {
    localLlmClient!!.streamChatCompletion(...)
} else {
    apiClient.streamChatCompletion(...)
}

chunkFlow.collect { chunk ->
    // Handle deltas, tool calls, completion uniformly
}
```

### Key Behaviors
1. **Message Persistence**: Save on `Complete` state only
2. **Tool Execution**: Interpolate between assistant message and result
3. **Context Compaction**: Automatically summarize when needed

---

## Inference Selection Logic

```kotlin
val isLocalServer: (String) -> Boolean = { it == LocalLlmClient.LOCAL_SERVER_ID }

// When sending a message:
if (isLocal(serverId)) {
    // Use local model
} else {
    // Use remote API
}
```

---

## Resource Management

### Remote Inference
- No local resource cost (server handles it)
- Network bandwidth required
- API costs may apply

### Local Inference
- **GPU offload**: Configurable percentage (default 80%)
- **Context window**: Per-model limit enforced
- **Memory pressure**: Unload models when needed

---

## Error Handling Comparison

| Error | Remote | Local |
|-------|--------|-------|
| Timeout | Retry with backoff | Cancel inference, show error |
| Rate limit | Wait + retry | N/A (no rate limits) |
| Connection lost | Show partial response + error | Cancelled by user |

---

## Performance Characteristics

| Metric | Remote | Local |
|--------|--------|-------|
| First token latency | 200-500ms | 100-300ms (after warmup) |
| Streaming throughput | Network-bound | CPU/GPU bound |
| Warm-up time | N/A | 2-5 seconds per model |

---

## Future Inference Backends

The Dual Inference Pipeline makes adding new backends straightforward:

```kotlin
interface InferenceProvider {
    fun streamChatCompletion(...): Flow<ChatCompletionChunk>
}

// Examples:
class RemoteOpenAiApiClient : InferenceProvider { ... }
class LocalLlmClient : InferenceProvider { ... }
class NewBackendClient : InferenceProvider { ... }  // Easy to add!
```

---

*This unified interface is the foundation that makes Pocket LLM flexible and future-proof.*

## Key Principle

> **The consumer never knows where tokens come from**. This enables seamless switching between remote and local models, and makes adding new inference methods trivial.
