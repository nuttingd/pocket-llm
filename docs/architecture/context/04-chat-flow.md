# Chat Flow Architecture

**Last Updated**: 2026-02-27  
**Reference**: ADR 003 - Stream State Machine

---

## Overview

The chat flow is orchestrated by `ChatManager` through a **state machine** that coordinates:
- User messages
- Assistant responses (streaming)
- Tool execution
- Conversation compaction
- Error recovery

Every event flows through `StreamState`.

---

## Flow Diagram

```
User Message
    │
    ├──▶ Save user message
    │      │
    │      ▼
    │  StreamState.Delta(content="...")
    │      │
    │      ▼
    └──▶ Collect tokens → Assistant Message (StreamState.Complete)
              │
              ├──▶ Tool calls? ──Yes──▶ StreamState.ToolCallsPending
              │                               │
              │                               ▼
              │                        User approval UI
              │                               │
              │                               ▼
              │                    StreamState.ToolCallResult
              │                               │
              │                               ▼
              │                      Continue loop (assistant responds)
              │
              └──▶ No tool calls ──▶ Save assistant message (StreamState.Complete)

    │
    ├──▶ Context approaching limit?
              │
              Yes                              No
              │                                │
              ▼                                ▼
    StreamState.Compacting           Continue chatting
              │
              ▼
    Summary + Recent messages only
              │
              ▼
    Continue chatting (reduced context)
```

---

## State Machine Details

### StreamState Types

```kotlin
sealed interface StreamState {
    // Streaming content
    data class Delta(val content: String, val thinkingContent: String? = null) : StreamState
    
    // Complete message persisted
    data class Complete(val message: MessageEntity) : StreamState
    
    // Error occurred
    data class Error(val error: String) : StreamState
    
    // Assistant wants to call tools
    data class ToolCallsPending(val toolCalls: List<ToolCall>) : StreamState
    
    // Tool execution result
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String) : StreamState
    
    // Conversation is being compacted
    data object Compacting : StreamState
}
```

---

## State Transitions

### Normal Chat Flow
```
Delta → Delta* → Complete
```

### Tool Call Flow
```
Delta → ... → ToolCallsPending
              │
              ├─▶ User approves → ToolCallResult → Delta → ... → Complete
              └─▶ User declines → Complete (skip tool)
```

### Compaction Flow
```
Compacting → Delta → ... → Complete (with reduced context)
```

### Error Flow
```
Delta* → Error (terminal state)
```

---

## ChatManager Responsibilities

| Responsibility | State Trigger |
|----------------|---------------|
| Save user message | Before sending request |
| Collect deltas | Accumulate `Delta` states |
| Persist assistant message | On `Complete` |
| Show tool approval UI | On `ToolCallsPending` |
| Execute tools and send results | User approves → `ToolCallResult` |
| Continue conversation loop | After tool results sent |
| Trigger compaction | When context window > 75% |

---

## Message Persistence Strategy

### What Gets Persisted
- **User messages**: On `Complete`
- **Assistant messages**: On `Complete`
- **Tool messages**: Immediately after execution

### Tree Structure
```
Message A (parent)
├── Message B (child 1) - original response
│   └── Message C (grandchild) - regenerated
└── Message D (child 2) - edited continuation
```

### Active Branch Tracking
- `Conversation.activeLeafMessageId` tracks current path
- Regenerate/edit creates new branch, updates leaf
- Navigation between branches using `parentMessageId`

---

## Tool Call Flow in Detail

```kotlin
// Assistant response includes tool calls
StreamState.ToolCallsPending([toolCall1, toolCall2])

// UI shows approval dialog with tool names/arguments

// User approves (or declines)
val approved = showApprovalDialog(toolCalls)

if (approved) {
    // Execute each tool
    for (tc in toolCalls) {
        val result = ToolExecutor.execute(tc.function.name, tc.function.arguments)
        emit(StreamState.ToolCallResult(tc.id, tc.function.name, result))
    }
    
    // Continue loop - send results back to model
    chatMessages.add(AssistantMessage + ToolResults)
    continueLoop = true  // Loop sends results and waits for response
    
} else {
    // User declined - skip tools
    emit(StreamState.Complete(assistantMsg))
}
```

---

## Context Compaction Flow

```kotlin
// Check if compaction needed before sending request
val estimatedTokens = TokenCounter.estimateTokens(messages)
val threshold = contextWindow * 0.75f

if (estimatedTokens > threshold && branchMessages.size > 4) {
    emit(StreamState.Compacting)
    
    val summary = llm.summarize(messagesToCompact, priorSummary)
    
    // Replace compacted messages with summary + recent messages
    return listOf(summaryMessage) + recentMessages
}
```

---

## UI Integration

| State | UI Response |
|-------|-------------|
| `Delta` | Append to message bubble (streaming text) |
| `Complete` | Mark message as complete, save to database |
| `Error` | Show error snackbar |
| `ToolCallsPending` | Show tool approval dialog |
| `ToolCallResult` | Append tool result to conversation |
| `Compacting` | Show "Summarizing..." indicator |

---

## Error Recovery

| Error Type | Recovery Strategy |
|------------|-------------------|
| Network timeout | Retry with exponential backoff, show partial response if available |
| Model overload (429) | Show retry-after period, offer retry button |
| Local OOM | Unload model, suggest smaller model or remote server |
| Stream disconnected | Show partial response + error |

---

## State Machine Properties

### Deterministic
- Given same inputs, same outputs
- No race conditions in state transitions

### Testable
- Feed states into ChatManager, verify outcomes
- Mock responses are just `StreamState` emissions

### Extensible
- New states can be added without breaking existing code
- State handlers are isolated and testable

---

*This state machine is the brain of Pocket LLM - it coordinates all chat flow while keeping the UI simple.*

## Key Principle

> **The UI only handles rendering. The ChatManager owns the flow.** This separation makes both components simpler and more robust.
