# API Contracts: OpenAI-Compatible Endpoints

**Branch**: `001-llm-chat` | **Date**: 2026-02-02

These contracts define the HTTP interface between Pocket LLM and any OpenAI-compatible server. The app is a consumer of these APIs, not a producer.

---

## 1. List Models

**FR-002**: Fetch and display available models.

```
GET {baseUrl}/v1/models
Authorization: Bearer {apiKey}  (optional, per server config)
```

### Response (200 OK)

```json
{
  "object": "list",
  "data": [
    {
      "id": "llama3:8b",
      "object": "model",
      "created": 1700000000,
      "owned_by": "library"
    }
  ]
}
```

### Kotlin Model

```kotlin
@Serializable
data class ModelsResponse(
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null
)
```

### Error Cases
- 401 Unauthorized: Invalid or missing API key
- Connection refused: Server not running
- Timeout: Server unresponsive (use per-server timeout FR-006)

---

## 2. Chat Completions (Non-Streaming)

**FR-004, FR-010**: Send messages, receive full response.

```
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}  (optional)
Content-Type: application/json
```

### Request Body

```json
{
  "model": "llama3:8b",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "Hello"}
  ],
  "temperature": 0.7,
  "max_tokens": 2048,
  "top_p": 1.0,
  "frequency_penalty": 0.0,
  "presence_penalty": 0.0,
  "stream": false
}
```

### Kotlin Request Model

```kotlin
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: ChatContent
)

// Content can be a string or an array (for vision/multimodal)
@Serializable(with = ChatContentSerializer::class)
sealed interface ChatContent {
    @Serializable
    data class Text(val text: String) : ChatContent

    @Serializable
    data class Parts(val parts: List<ContentPart>) : ChatContent
}

@Serializable
sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String) : ContentPart

    @Serializable
    @SerialName("image_url")
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl
    ) : ContentPart
}

@Serializable
data class ImageUrl(
    val url: String,  // "data:image/jpeg;base64,..." or URL
    val detail: String? = null  // "auto", "low", "high"
)
```

### Response (200 OK)

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1700000000,
  "model": "llama3:8b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 12,
    "completion_tokens": 8,
    "total_tokens": 20
  }
}
```

### Kotlin Response Model

```kotlin
@Serializable
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)
```

---

## 3. Chat Completions (Streaming / SSE)

**FR-010, FR-011, FR-012**: Stream response token-by-token.

Same endpoint as above with `"stream": true`.

```
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}  (optional)
Content-Type: application/json
Accept: text/event-stream
```

### SSE Event Stream

```
data: {"id":"chatcmpl-abc","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}

data: [DONE]
```

### Kotlin Streaming Model

```kotlin
@Serializable
data class ChatCompletionChunk(
    val id: String,
    val model: String? = null,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null  // Only present on final chunk (some servers)
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null
)
```

### Stream Termination
- `data: [DONE]` signals end of stream
- `finish_reason: "stop"` indicates normal completion
- `finish_reason: "tool_calls"` indicates model wants to call tools
- `finish_reason: "length"` indicates max_tokens reached

---

## 4. Tool Calling

**FR-040 through FR-047**: Function calling round-trip.

### Tool Definition (sent in request)

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "calculator",
        "description": "Evaluate a mathematical expression",
        "parameters": {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "The math expression to evaluate"
            }
          },
          "required": ["expression"]
        }
      }
    }
  ]
}
```

### Kotlin Tool Models

```kotlin
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject  // JSON Schema
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String  // JSON string to be parsed
)

@Serializable
data class ToolCallChunk(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallChunk? = null
)

@Serializable
data class FunctionCallChunk(
    val name: String? = null,
    val arguments: String? = null  // Streamed incrementally
)
```

### Tool Result (sent back as a message)

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "42"
}
```

### Round-Trip Flow
1. App sends request with `tools` array
2. Model returns `finish_reason: "tool_calls"` with tool call(s) in response
3. App displays tool call(s) to user, requests approval (FR-043)
4. App executes approved tool(s) locally
5. App sends tool result(s) as `role: "tool"` messages
6. Model continues with final response
7. For parallel tool calls (FR-047): all tool results sent together before model continues

---

## 5. Thinking / Reasoning Content

**FR-018**: Detect and display reasoning content.

### Format Variations

Some servers return thinking content via:

**Option A**: `<think>` XML tags in content
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "<think>\nLet me work through this step by step...\n</think>\n\nThe answer is 42."
    }
  }]
}
```

**Option B**: Separate `reasoning_content` field (DeepSeek format)
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "The answer is 42.",
      "reasoning_content": "Let me work through this step by step..."
    }
  }]
}
```

### Detection Logic
1. Check for `reasoning_content` field in response/delta
2. If not present, check for `<think>...</think>` tags in content
3. Extract thinking content into separate `thinkingContent` field on Message entity
4. Display in collapsible section (collapsed by default)

---

## 6. Vision / Multimodal

**FR-080 through FR-083**: Image attachment.

### Request Format (multimodal message)

```json
{
  "model": "llava:latest",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "What's in this image?"},
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,/9j/4AAQ..."
          }
        }
      ]
    }
  ]
}
```

### Image Processing Pipeline (FR-081)
1. User selects image (gallery, camera, clipboard)
2. Resize to max 1024px on long side (configurable)
3. Compress to JPEG quality 85% (configurable)
4. Base64-encode
5. Embed as `image_url` content part with `data:image/jpeg;base64,...` URI

---

## Error Response Format

All endpoints may return errors in this format:

```json
{
  "error": {
    "message": "Invalid API key",
    "type": "authentication_error",
    "code": "invalid_api_key"
  }
}
```

### Kotlin Error Model

```kotlin
@Serializable
data class ApiErrorResponse(
    val error: ApiError
)

@Serializable
data class ApiError(
    val message: String,
    val type: String? = null,
    val code: String? = null
)
```

### HTTP Status Handling
| Status | Behavior |
|--------|----------|
| 200 | Success |
| 401 | Show "Invalid API key" error |
| 404 | Show "Endpoint not found" (wrong server URL?) |
| 429 | Retry with exponential backoff, show retry status (FR-019) |
| 500 | Show server error, allow retry |
| 503 | Retry with exponential backoff (FR-019) |
| Timeout | Show timeout error with configurable timeout value (FR-006) |
