# ADR 003: Stream State Machine

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/chat-flow.md

---

## Decision

A single event flow (`StreamState`) coordinates all aspects of the chat lifecycle:
- Message persistence
- Token counting
- Tool execution
- Conversation compaction

Every async event in the chat lifecycle flows through this machine.

---

## Status

**Approved** - This decision is in effect for all new development.

---

## Rationale

### Why a State Machine?

| Event Handler Pattern | State Machine |
|-----------------------|---------------|
| Scattered logic | Centralized coordination |
| Race conditions | Deterministic transitions |
| Hard to test | Single flow, easy to trace |

**Verdict**: The `ChatManager` with `StreamState` is the brain of the chat system.

### Why Not Coroutines/Flows Alone?

Flows handle streaming well, but don't coordinate:
- When to persist a message
- When to execute tools
- When to compact conversation

The state machine answers these questions.

---

## State Definitions

```kotlin
sealed interface StreamState {
    data class Delta(val content: String, val thinkingContent: String? = null) : StreamState
    data class Complete(val message: MessageEntity) : StreamState
    data class Error(val error: String) : StreamState
    data class ToolCallsPending(val toolCalls: List<ToolCall>) : StreamState
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String) : StreamState
    data object Compacting : StreamState
}
```

### State Transitions

```
Delta ──▶ Delta* ──▶ (ToolCallsPending | Complete)
                      │              │
                      ▼              ▼
              ToolCallResult* ────┘
                      │
                      ▼
                  (Delta* ──▶ Complete)  [continuation loop]
                      
Compacting ──▶ Delta* ──▶ Complete

Error (terminal)
```

---

## Implementation Requirements

### ChatManager Responsibilities

1. **Collect deltas** into accumulated content/thinking/tool calls
2. **Persist complete messages** when `Complete` state emitted
3. **Handle tool call approval/execution**
4. **Trigger compaction** when needed
5. **Emit errors** on failures

### UI Responsibilities

- Display `Delta` content as it streams
- Show `Compacting` indicator during summary generation
- Handle `ToolCallsPending` with approval UI
- Display `Error` messages

---

## Trade-offs

### Pros
- Single source of truth for chat flow
- Easy to test (feed states, verify outcomes)
- Extensible (new states can be added)

### Cons
- Complex state machine in one class (ChatManager is large)

**Mitigation**: The ChatManager already handles this complexity. Don't split it further - cohesion is more important than size here.

---

## Compliance

All chat flow MUST:

- [ ] Use `StreamState` for all events
- [ ] Persist messages only on `Complete` state
- [ ] Handle tool calls through `ToolCallsPending`/`ToolCallResult`
- [ ] Emit `Compacting` during summary generation

**Enforcement**: Code review + tests that verify state transitions.
