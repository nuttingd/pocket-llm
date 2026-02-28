# System Boundaries

**Last Updated**: 2026-02-27  
**Reference**: ADRs 001, 002

---

## External Interfaces

Pocket LLM interacts with several external systems:

### 1. Android OS

| Interface | Purpose |
|-----------|---------|
| ContentResolver | Access media files for image attachments |
| WorkManager | Background model downloads |
| DataStore | Settings persistence |
| EncryptedFile | Encrypted API key storage (Tink) |

---

### 2. Remote APIs

| Endpoint | Purpose | Authentication |
|----------|---------|----------------|
| `/v1/models` | Fetch available models | Optional Bearer token |
| `/v1/chat/completions` | Stream chat completions | Optional Bearer token |

**Protocol**: OpenAI-compatible JSON over HTTPS/SSE

---

### 3. Local Models (llama.cpp)

| Interface | Purpose |
|-----------|---------|
| JNI bridge (`LlmEngine`) | Load/unload models, run inference |
| Model files (.gguf) | Binary model weights |
| Model metadata (.json) | Model info (context size, parameters) |

---

### 4. User Data

| Storage | Encrypted | Purpose |
|---------|-----------|---------|
| Room Database | Optional (SQLCipher) | Conversations, messages |
| DataStore Preferences | No | Settings |
| EncryptedDataStore (Tink) | Yes | API keys |

---

## Boundary Definitions

### What's Inside Pocket LLM
- Chat interface and flow
- Conversation management
- Tool execution (calculator, web fetch)
- Local inference via llama.cpp
- Parameter presets
- Message actions (regenerate, edit, branch)

### What's Outside Pocket LLM (Use System)
- File system access → Android Storage Access Framework
- Network connectivity → Android ConnectivityManager
- Notifications → Android NotificationManager
- Backup/restore → Android Auto Backup for Apps

---

## Integration Patterns

### API Client Pattern

```kotlin
interface OpenAiApiClient {
    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long,
        request: ChatCompletionRequest
    ): Flow<ChatCompletionChunk>
}
```

**Boundary**: Pure Kotlin, no Android dependencies.

---

### Local Model Client Pattern

```kotlin
class LocalLlmClient(
    private val llmEngine: LlmEngine,
    // ... other dependencies
) {
    fun streamChatCompletion(...): Flow<ChatCompletionChunk> { ... }
}
```

**Boundary**: JNI layer isolated in `llm/` module.

---

## Security Boundaries

| Data Type | Storage Location | Encryption |
|-----------|------------------|------------|
| Conversations | Room (app storage) | Optional SQLCipher |
| API Keys | EncryptedDataStore | Tink AES |
| User Settings | DataStore Preferences | None (non-sensitive) |
| Local Models | External files | File permissions only |

---

## Error Boundaries

| Error Source | Handling Strategy |
|--------------|------------------|
| Network failures | Retry with exponential backoff, show error UI |
| Model loading failure | Graceful degradation to remote models or error message |
| Storage full | Notify user, allow cleanup |

---

*The app is a good citizen in the Android ecosystem - it uses system services, doesn't duplicate functionality, and respects user data boundaries.*

## Future Boundary Expansions

Potential future interfaces:
- **Voice input**: Use Android SpeechRecognizer API
- **Cross-device sync**: Use nearby connection APIs
- **Cloud backup**: Optional opt-in service (never default)
