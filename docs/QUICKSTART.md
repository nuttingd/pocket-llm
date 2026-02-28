# Quick Start: Contributing to Pocket LLM

Welcome! This guide helps you get up and running quickly.

---

## Prerequisites

- JDK 21+
- Android SDK 36
- Min SDK 28 (Android 9)
- Git

---

## First Steps

### 1. Read the North Star

```
Read: docs/north-star.md
```

This is our compass - everything we build must align with these principles.

**Key principles to remember**:
- ✅ Local-first (all core features work offline)
- ✅ Privacy-first (user data never leaves device)
- ✅ Open architecture (shareable, extensible)
- ✅ Resource-respectful (mobile-optimized)

### 2. Understand the Progression

```
Read: docs/capability-ladder/00-milestones.md
```

We're currently at **M0 (Baseline)** - feature-complete V1.

**Next milestone**: M1 (Orchestrator Phase) - Knowledge + Agents

### 3. Review Architecture

```
Read: docs/architecture/index.md
```

Focus on:
- `decisions/` - ADRs for key architectural choices
- `patterns/` - Reusable design patterns
- `context/` - Background information

---

## Development Workflow

### When Adding a Feature

```bash
# 1. Check North Star alignment
cat docs/north-star.md | grep "local-first\|privacy"

# 2. Identify which milestone (M0, M1, M2, or M3)
cat docs/capability-ladder/00-milestones.md

# 3. Search for existing patterns
grep -r "your-use-case" docs/architecture/patterns/

# 4. Implement following the pattern
# ... code ...

# 5. Document if major change
# Add ADR in docs/architecture/decisions/
```

### When Fixing a Bug

```bash
# 1. Identify which component is affected
# Check: app/src/main/java/dev/nutting/pocketllm/

# 2. Look for relevant ADRs
grep -r "component-name" docs/architecture/decisions/

# 3. Add test covering the bug scenario
# Check: app/src/test/java/
```

---

## Building and Testing

```bash
# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# Lint checks
./gradlew lintDebug

# Connected Android tests (requires device/emulator)
./gradlew connectedAndroidTest
```

**Prerequisite**: `./gradlew assembleDebug` must succeed before any PR.

---

## Code Review Checklist

Before submitting a PR:

- [ ] North Star alignment reviewed (does it uphold local-first, privacy, etc.?)
- [ ] Capability ladder milestone identified
- [ ] Relevant ADRs followed or created
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Tests pass for new functionality
- [ ] Accessibility passes TalkBack test
- [ ] Lint has no errors

---

## Common Tasks

### Adding a New Screen

1. Create ViewModel with single `UiState`
2. Create Compose screen using Material 3
3. Add navigation route in `AppNavGraph.kt`
4. Implement accessibility (contentDescription, touch targets)
5. Test with TalkBack enabled

**Pattern**: See `app/src/main/java/dev/nutting/pocketllm/ui/chat/` for examples.

### Adding a New Repository

1. Create DAO in `data/local/dao/`
2. Create repository in `data/repository/`
3. Add to `AppContainer`
4. Inject into ViewModel

**Pattern**: See `ServerRepository` and `MessageRepository`.

### Extending the Chat Flow

1. Update `StreamState` if new event type needed
2. Update `ChatManager` to handle new state
3. Update UI to render new state

**Pattern**: See existing `StreamState` implementations.

---

## Architecture Deep Dives

### Dual Inference Pipeline

**Where**: `domain/ChatManager.kt`, `data/remote/OpenAiApiClient.kt`, `domain/LocalLlmClient.kt`

**What**: Unified streaming interface for remote and local models.

**Key point**: Consumer never knows where tokens come from!

### Stream State Machine

**Where**: `domain/ChatManager.kt` - `StreamState` sealed interface

**What**: Coordinates all chat events through a single flow.

**States**:
- `Delta` - Streaming token
- `Complete` - Message persisted
- `Error` - Failure occurred
- `ToolCallsPending` - Assistant wants to call tools
- `ToolCallResult` - Tool execution result
- `Compacting` - Conversation summary in progress

---

## Troubleshooting

### Build fails with "SDK not found"

```bash
# Set ANDROID_HOME or create local.properties
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

### Tests fail with "No tests found"

```bash
# Run specific test class
./gradlew test --tests "YourTestClass"
```

### Lint errors

```bash
# See detailed lint report
open app/build/reports/lint/lint.html
```

---

## Getting Help

1. **Check docs first** - `docs/` contains comprehensive documentation
2. **Read ADRs** - Architectural decisions are documented
3. **Ask!** - Better to clarify than guess wrong

---

## Quick Reference Cards

### North Star Principles (Remember These)

```
✅ Local-first: All core features work offline
✅ Privacy-first: User data never leaves device
✅ Open architecture: Shareable, extensible
✅ Resource-respectful: Mobile-optimized
```

### Capability Ladder (Where Are We?)

```
M0 (Now): Baseline - Feature-complete V1
M1 (+3 mo): Orchestrator - Knowledge + Agents
M2 (+6 mo): Federated - Privacy-aware learning
M3 (+12 mo): Platform - Open ecosystem
```

### Architecture Pillars

```
✅ Single UiState per ViewModel
✅ Dual Inference Pipeline (remote + local unified)
✅ Stream State Machine (ChatManager)
✅ Model Lifecycle (download, load, unload)
✅ Parameter Space (temperature, max tokens, system prompt)
```

---

## Next Steps

1. **Pick a task** - Look at issues or capability ladder
2. **Review docs** - Read relevant ADRs and patterns
3. **Implement** - Follow established patterns
4. **Test** - Run all tests before PR
5. **Document** - Add ADR for major changes

---

*Welcome to Pocket LLM! Let's build the world's most private, capable on-device AI platform together.*

## Support Resources

- **Documentation**: `docs/` directory
- **Architecture Decisions**: `docs/architecture/decisions/`
- **Patterns Catalog**: `docs/architecture/patterns/`
- **North Star**: `docs/north-star.md`

---

*Last updated: 2026-02-27*
