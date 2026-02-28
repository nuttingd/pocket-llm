# Design Patterns Catalog

This directory documents reusable design patterns and solutions in Pocket LLM.

---

## How to Use This Document

1. **Identify your problem** - What issue are you trying to solve?
2. **Check this catalog** - Does a pattern already exist for it?
3. **Adapt the pattern** - Customize it to your specific context
4. **Document if new** - If solving a recurring problem, add a new pattern

---

## Patterns

### Architecture Patterns

| Pattern | Description | When to Use |
|---------|-------------|-------------|
| Dual Inference Pipeline | Unified streaming interface for remote + local inference | Anywhere inference is needed |

### UI Patterns

| Pattern | Description | Location |
|---------|-------------|----------|
| Single UiState | All screen state in one data class | Every ViewModel |
| LazyColumn with animateScrollToItem | Chat scrolling behavior | Chat screens |
| State hoisting for forms | Form state in parent, inputs in child | Settings screens |

### Data Patterns

| Pattern | Description | Location |
|---------|-------------|----------|
| Room with FTS | Full-text search using virtual table | Message search |

---

## Anti-Patterns to Avoid

### 1. Premature Abstraction

**Problem**: Creating interfaces/classes before the pattern repeats.

**Solution**: Implement first occurrence, extract after second occurrence shows clear pattern.

**Example**: Repository layer only exists where multiple consumers exist (ServerRepository).

---

### 2. ViewModel Holding Context References

**Problem**: Activity/Fragment references in ViewModels cause memory leaks.

**Solution**: Use `Application` context only when necessary. Pass required data as parameters.

---

### 3. Unbounded Data Loading

**Problem**: Loading all messages at once for old conversations.

**Solution**: Implement pagination or load strategy with limits (e.g., last N messages).

---

## Pattern Evolution

Patterns evolve as the codebase grows:

1. **Occurrence 1**: Quick implementation, copy/paste if needed
2. **Occurrence 2**: Extract common pattern
3. **Occurrence 3+**: Document in this catalog

---

*This catalog grows organically with the codebase. Each pattern represents a lesson learned.*
