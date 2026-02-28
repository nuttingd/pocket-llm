# ADR 002: Dual Inference Pipeline

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/inference-architecture.md

---

## Decision

The app will use a **unified streaming interface** that produces identical `ChatCompletionChunk` events from either:
1. Remote OpenAI-compatible APIs (via Ktor)
2. Local GGUF models via llama.cpp JNI

The consumer never knows - and never needs to know - where the tokens came from.

---

## Status

**Approved** - This decision is in effect for all new development.

---

## Rationale

### Why a Unified Interface?

| Separate Interfaces | Unified Interface |
|---------------------|-------------------|
| Code duplication | Single code path |
| Inconsistent behavior | Consistent experience |
| Complex UI logic | Simple UI |

**Verdict**: The Dual Inference Pipeline is the core architectural primitive that enables all other features.

### Why `ChatCompletionChunk`?

This format is:
- **Standardized**: Matches OpenAI API, works with any server
- **Minimal**: Just what's needed for streaming chat
- **Extensible**: Can add new fields (tool calls, reasoning) without breaking

---

## Architecture

```
User Request
    │
    ├──▶ OpenAiApiClient ────▶ Remote Server
    │       │                          │
    │       ▼                          ▼
    │   ChatCompletionChunk ◄─────── Streaming SSE
    │
    └──▶ LocalLlmClient ─────▶ llama.cpp (JNI)
            │                         │
            ▼                         ▼
        ChatCompletionChunk ◄─── Token-by-token stream

                    │
                    ▼
            ChatManager (unified consumer)
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `ChatManager` | Coordinates chat flow, persists messages, handles tools/compaction |
| `OpenAiApiClient` | Remote API client, produces `ChatCompletionChunk` |
| `LocalLlmClient` | Local LLM bridge, produces same `ChatCompletionChunk` |

---

## Trade-offs

### Pros
- Single code path for chat logic (simpler maintenance)
- Seamless transition between remote and local models
- Extensible: new inference methods can plug in same interface

### Cons
- Must support lowest-common-denominator features across all backends
- Local model constraints must be respected (RAM, context window)

**Mitigation**: Document backend-specific behavior in `ChatManager` comments.

---

## Implementation Requirements

### For New Inference Backends

To add a new inference method:

1. Implement interface that produces `Flow<ChatCompletionChunk>`
2. Add backend identifier to server selection logic
3. Handle backend-specific constraints (e.g., local model unload on low memory)

### Example Extension Point

```kotlin
interface InferenceProvider {
    fun streamChatCompletion(
        messages: List<ChatMessage>,
        temperature: Float?,
        maxTokens: Int?,
        topP: Float?
    ): Flow<ChatCompletionChunk>
}

// Add new provider:
class NewBackendClient : InferenceProvider { ... }
```

---

## Compliance

All inference paths MUST:

- [ ] Produce `Flow<ChatCompletionChunk>` with identical structure
- [ ] Handle same error cases (disconnected, rate limit, timeout)
- [ ] Support same parameters (temperature, maxTokens, topP)

**Enforcement**: Code review + tests that verify identical output from both backends.
