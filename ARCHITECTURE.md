# Pocket LLM Architecture Documentation

This document provides an overview of Pocket LLM's architecture and where to find detailed information.

---

## Quick Start

### For New Developers

1. **Read this file** - Overview of architecture
2. **Read docs/north-star.md** - Vision and principles (non-negotiables)
3. **Read docs/QUICKSTART.md** - Developer onboarding guide
4. **Start coding!**

### For LLM-Driven Development

When adding features:

```
1. Check North Star alignment (docs/north-star.md)
2. Identify capability ladder milestone (docs/capability-ladder/)
3. Review architecture patterns (docs/architecture/patterns/)
4. Implement following established patterns
5. Document major changes with ADRs (docs/architecture/decisions/)
```

---

## Architecture Overview

Pocket LLM is built around **five core primitives** that form the foundation of all features:

### 1. Conversation Tree
Messages organized as a tree with branching support:
- Regenerate creates new branch
- Edit creates new branch from that point
- Active branch tracked via `activeLeafMessageId`

**Location**: `app/data/local/entity/ConversationEntity.kt`, `MessageEntity.kt`

### 2. Dual Inference Pipeline
Unified streaming interface for remote + local models:
- `ChatManager` coordinates both paths
- Same `ChatCompletionChunk` events regardless of source
- Consumer never knows where tokens came from

**Location**: `domain/ChatManager.kt`, `data/remote/OpenAiApiClient.kt`, `domain/LocalLlmClient.kt`

### 3. Stream State Machine
Single event flow coordinating all chat lifecycle:
- Delta → Complete (normal flow)
- ToolCallsPending → ToolCallResult (tool flow)
- Compacting → Complete (summary flow)

**Location**: `ChatManager.StreamState` sealed interface

### 4. Model Lifecycle
Complete arc for local models:
- Discovery → Download → Storage → Selection → Loading → Inference → Unloading

**Location**: `data/local/model/LocalModelStore.kt`, `llm/LlmEngine.kt`

### 5. Parameter Space
Generation parameters at multiple levels:
- Global defaults (settings)
- Per-conversation overrides
- Named presets for quick selection

**Location**: `data/preferences/SettingsDataStore.kt`, `data/local/entity/ParameterPresetEntity.kt`

---

## Documentation Structure

```
docs/
├── north-star.md              # Vision & principles (READ FIRST)
├── capability-ladder/         # Roadmap (M0→M3 progression)
│   ├── 00-milestones.md       # High-level overview
│   ├── 01-m1-orchestrator.md  # Phase 1: Knowledge + Agents
│   ├── 02-m2-federated.md     # Phase 2: Privacy-aware learning
│   └── 03-m3-platform.md      # Phase 3: Open ecosystem
├── architecture/              # Technical documentation
│   ├── decisions/             # Architectural Decision Records (ADRs)
│   ├── patterns/              # Reusable design patterns
│   └── context/               # Background information
└── spec-compliance/           # Feature-to-principle mapping
```

---

## Key Design Decisions

### 1. MVVM with Compose, Single Module
- **ADR**: `docs/architecture/decisions/001-mvvm-compose-single-module.md`
- **Why**: Single module = faster builds, simpler navigation, flat dependency graph
- **Impact**: All UI uses Jetpack Compose with single `UiState` per ViewModel

### 2. Dual Inference Pipeline
- **ADR**: `docs/architecture/decisions/002-dual-inference-pipeline.md`
- **Why**: Single code path for chat logic, seamless remote/local transition
- **Impact**: Same streaming interface regardless of backend

### 3. Stream State Machine
- **ADR**: `docs/architecture/decisions/003-stream-state-machine.md`
- **Why**: Centralized coordination, deterministic transitions, easy to test
- **Impact**: All chat flow flows through `ChatManager` with `StreamState`

---

## Quality Gates

All PRs must pass:

```bash
./gradlew assembleDebug    # Build succeeds
./gradlew test             # Tests pass
./gradlew lintDebug        # No lint errors
```

And verify:

- [ ] North Star alignment reviewed
- [ ] Accessibility passes TalkBack navigation
- [ ] Capability ladder milestone identified

---

## Next Steps

1. **Read north-star.md** - Understand our vision and non-negotiables
2. **Check capability-ladder/** - See where we're going
3. **Review architecture/** - Learn how things work
4. **Start coding!**

---

## Getting Help

- **Documentation**: `docs/` directory (comprehensive)
- **Architecture decisions**: `docs/architecture/decisions/`
- **Patterns catalog**: `docs/architecture/patterns/`

If still unclear, ask! Better to clarify than guess wrong.

---

*This documentation is the single source of truth for Pocket LLM.*

## Version

**Last updated**: 2026-02-27  
**Documentation version**: 1.0.0
