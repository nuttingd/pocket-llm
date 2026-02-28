# ADR 001: MVVM with Compose, Single Module

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/technical-stack.md

---

## Decision

The app will use a **single Gradle module** (`app/`) with Jetpack Compose and Material 3 for all UI. State will flow through a single `UiState` data class per ViewModel.

Feature separation will be achieved through **packages**, not modules:
- `ui/chat/` - Chat screen
- `ui/conversations/` - Conversation list
- `ui/settings/` - Settings screens
- etc.

ViewModels will not hold references to Android framework classes (Activity, Context) except for Application when unavoidable.

---

## Status

**Approved** - This decision is in effect for all new development.

---

## Rationale

### Why Single Module?

| Multi-Module | Single Module |
|--------------|---------------|
| Cross-module API surfaces | Flat dependency graph |
| Gradle configuration overhead | Faster build times |
| Navigation indirection | Simpler navigation |

**Verdict**: For a single-developer project, the cost of maintaining module boundaries outweighs the benefits. A flat package structure is more legible.

### Why Single `UiState` per ViewModel?

| Multiple States | Single State |
|-----------------|--------------|
| State consistency issues | Deterministic behavior |
| Hard to debug | Inspectable from one place |

**Verdict**: The single `UiState` pattern makes every screen's behavior deterministic and inspectable.

### Why No DI Framework?

| DI Framework | Manual Construction |
|--------------|---------------------|
| Hidden dependencies | Explicit dependencies |
| Complex setup | Simple singleton patterns |

**Verdict**: In a small codebase, manual construction keeps the dependency graph legible. Use `AppContainer` for dependency management.

---

## Trade-offs

### Pros
- Fast build times (no cross-module dependencies)
- Flat dependency graph is easier to understand
- Simpler navigation (single module = single back stack)
- Easier testing (dependencies are explicit)

### Cons
- Module boundaries might need refactoring if team grows significantly
- Some separation of concerns is achieved through discipline rather than enforceable boundaries

**Mitigation**: Code reviews will enforce package-level separation. If the team grows, we can always extract modules later.

---

## Alternatives Considered

### Alternative 1: Multi-Module Architecture

**Why rejected**: The Gradle overhead and cross-module complexity don't justify benefits for a single-developer project.

### Alternative 2: Multiple States per ViewModel

**Why rejected**: State consistency issues lead to subtle bugs. Single `UiState` is more robust.

### Alternative 3: Hilt/Dagger DI Framework

**Why rejected**: Adds complexity without meaningful benefit. Manual construction keeps dependencies explicit.

---

## How to Implement

### New ViewModel Pattern

```kotlin
data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val currentStreamingContent: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,
    // ... other fields
)

class ChatViewModel(
    private val chatManager: ChatManager,
    private val messageRepository: MessageRepository,
) {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
}
```

### Dependency Management

```kotlin
class AppContainer(
    private val application: Application
) {
    val database by lazy { PocketLlmDatabase.getInstance(application) }
    val serverRepository by lazy { ServerRepository(database.serverProfileDao()) }
    val messageRepository by lazy { MessageRepository(database.messageDao()) }
    // ... other repositories
}
```

---

## Compliance

Every new screen MUST:

- [ ] Use Jetpack Compose with Material 3
- [ ] Have a single `UiState` data class
- [ ] Use manual dependency injection via `AppContainer`
- [ ] Not hold references to Activity/Context (use Application only when necessary)

**Enforcement**: Code review checklist.
