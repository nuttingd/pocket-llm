# ADR 005: Parameter Space Management

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/chat-flow.md

---

## Decision

Generation parameters are stored as:
1. **Global defaults** in Settings (DataStore)
2. **Per-conversation overrides** in Conversation entity
3. **Named presets** for quick selection (ParameterPresetEntity)

The UI layer manages parameter selection, while the domain layer receives explicit values.

---

## Status

**Approved** - Parameter system is in effect.

---

## Rationale

### Why Multiple Levels?

| Level | Purpose |
|-------|---------|
| Global defaults | Default behavior for new conversations |
| Per-conversation | Override specific conversations |
| Presets | Quick selection of common combinations |

This allows flexibility without complexity explosion.

### Where to Store Each Parameter Type?

| Parameter | Storage |
|-----------|---------|
| System prompt, temperature, maxTokens | Conversation entity (per-conversation) |
| Theme mode, message font size | DataStore Preferences (global settings) |
| Preset values | ParameterPresetEntity table |

---

## Parameters Managed

### Generation Parameters
- `temperature` (0.0 - 2.0)
- `maxTokens` (1 - model context window)
- `topP` (0.0 - 1.0)
- `frequencyPenalty` (-2.0 - 2.0)
- `presencePenalty` (-2.0 - 2.0)

### UI Parameters
- `themeMode` ("light", "dark", "system")
- `messageFontSizeSp` (Int, SP units)
- `dynamicColorEnabled` (Boolean)

---

## Preset System

```kotlin
@Entity(tableName = "parameter_presets")
data class ParameterPreset(
    @PrimaryKey val id: String,
    val name: String,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val frequencyPenalty: Float,
    val presencePenalty: Float,
    val isBuiltIn: Boolean
)
```

### Built-in Presets (read-only)
| Name | Temperature | Top-P | Max Tokens |
|------|-------------|-------|------------|
| Creative | 1.0 | 0.95 | 2048 |
| Precise | 0.2 | 0.5 | 2048 |
| Code | 0.1 | 0.3 | 4096 |
| Balanced | 0.7 | 0.8 | 2048 |

---

## Parameter Resolution Flow

```kotlin
// When sending a message:
val conversation = conversationRepository.getById(conversationId)
    .first() ?: throw IllegalStateException("Conversation not found")

// Resolve parameters with fallbacks:
val temperature = conversation.temperature ?: globalDefaults.temperature ?: 0.7f
val maxTokens = conversation.maxTokens ?: globalDefaults.maxTokens ?: 2048
val topP = conversation.topP ?: globalDefaults.topP ?: 1.0f

// Send to API or local model with resolved values
```

---

## Trade-offs

### Pros
- Users can customize behavior at multiple levels
- Presets simplify common parameter combinations
- Per-conversation override preserves context

### Cons
- Parameter resolution logic adds complexity
- UI must show which parameters are overridden

**Mitigation**: Clear UI indicators (e.g., "Using custom temperature: 0.5") and simple presets.

---

## Implementation Requirements

### New Conversation Creation
```kotlin
// In ConversationRepository.insertConversation():
val preset = parameterPresetDao.getDefaultPreset() // e.g., Balanced
conversation.temperature = preset?.temperature
conversation.topP = preset?.topP
conversation.maxTokens = preset?.maxTokens ?: 2048
```

### Preset Application
```kotlin
// In ChatViewModel.applyPreset(preset):
_uiState.update { it.copy(
    conversationParams = ConversationParams(
        temperature = preset.temperature,
        topP = preset.topP,
        maxTokens = preset.maxTokens,
        // ... other parameters
    )
) }
```

---

## Compliance

Parameter system MUST:

- [ ] Resolve with correct fallback chain (conversation → global → default)
- [ ] Display overridden parameters clearly in UI
- [ ] Allow user to reset to defaults per conversation
- [ ] Support preset creation/deletion

**Enforcement**: Code review + tests that verify parameter resolution.

---

## Future Enhancements

Potential additions:
1. **Context-aware presets** - Presets based on task type (coding, writing, etc.)
2. **Auto-parameter tuning** - Learn optimal parameters per conversation
3. **Presets library** - Shareable preset files

These are M1/M2 features (Context-Aware Tools, Federated Learning).
