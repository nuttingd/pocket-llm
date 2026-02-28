# V1 Feature Compliance Mapping

**North Star Reference**: See ../north-star.md  
**Capability Ladder**: M0 (Baseline)

---

## Features

| Feature | North Star Alignment | Capability Ladder | Status |
|---------|---------------------|-------------------|--------|
| OpenAI-compatible API client | ✅ Remote option, local-first | M0 | Complete |
| Local LLM inference via llama.cpp | ✅ Core principle: on-device | M0 | Complete |
| Streaming responses | ✅ Low-latency chat experience | M0 | Complete |
| Conversation management | ✅ User owns conversation data | M0 | Complete |
| Per-conversation model selection | ✅ Flexibility without compromise | M0 | Complete |
| Message branching | ✅ Preserves user history | M0 | Complete |
| Image attachments | ⚠️ Remote option if model supports vision | M0 | Complete |
| Markdown rendering | ✅ Quality UX requirement | M0 | Complete |
| Thinking sections | ✅ Supports modern models | M0 | Complete |
| Tool calling framework | ✅ Extensible tool system | M0 | Complete |
| Context compaction | ✅ Resource-respectful (manages context window) | M0 | Complete |
| Parameter presets | ⚠️ Only local-first options available | M0 | Complete |
| Encrypted API keys | ✅ Privacy-first security | M0 | Complete |
| Material 3 UI | ✅ Quality UX requirement | M0 | Complete |

---

## North Star Principle Check

### ✅ Local-First

All features support local-only operation:
- Chat with local models works offline
- Conversation history stored on-device
- Tools (calculator, web fetch) work locally or gracefully degrade
- Parameter presets are user preferences

**Exception**: Web fetch tool requires internet - documented and optional.

---

### ✅ User Data Ownership

Conversations never leave device:
- All messages in Room database
- API keys encrypted at rest
- No telemetry by default
- Export functionality produces portable files

---

### ✅ Open Architecture

Features are shareable:
- Parameter presets can be exported
- Tool definitions use JSON schema (portable)
- Model files are GGUF format (community standard)

---

### ⚠️ Resource Respect

Current features respect resources, but M1 will add more:

| Feature | Impact | Mitigation |
|---------|--------|------------|
| Local model loading | RAM usage | Unload models on pressure |
| Context compaction | CPU/GPU during summary | Background thread |
| Image compression | CPU during resize | Optimize with native code |

---

## Capability Ladder Progress

### M0 (Current) - Baseline
**Status**: Complete features that define V1

These are the core chat client features:
- Chat flow with remote and local models
- Conversation management
- Tool calling framework
- Context compaction
- Parameter presets
- UI polish (Material 3, accessibility)

**Quality Gates Met**:
- ✅ `./gradlew assembleDebug` succeeds
- ✅ All BDD acceptance scenarios pass
- ✅ Accessibility passes TalkBack navigation

---

## Migration Path to M1

| Current Feature | Needs Enhancement for M1 | Why |
|----------------|-------------------------|-----|
| Context compaction | Add document RAG layer | Extend compaction to include documents |
| Tool calling framework | Formalize agent concept | Agents are specialized tools with memory |
| Parameter presets | Add context-aware suggestions | Tools suggest parameters based on conversation |

---

## Out of Scope for M0

| Feature | Reason |
|---------|--------|
| Document upload/search | Requires new primitives (embeddings, RAG) - M1 scope |
| Agent framework | Requires tool specialization - M1 scope |
| Federated learning | Privacy-aware model updates - M2 scope |
| Cross-device sync | Device-to-device coordination - M2 scope |

---

## Compliance Summary

### North Star Alignment: ✅ STRONG
- Local-first: All core features work offline
- Privacy-first: User data stays on device
- Open architecture: Shareable formats and workflows

### Capability Ladder Progress: ✅ M0 COMPLETE
- Core chat client is feature-complete
- Quality gates all pass
- Ready for user testing

---

## Next Steps to M1

1. **Add document RAG** - Upload PDF, embed locally, semantic search
2. **Formalize agent framework** - Create agents with tool bundles
3. **Context-aware tools** - Tools understand conversation history

See `../capability-ladder/01-m1-orchestrator.md` for details.

---

*This matrix ensures every feature serves our vision and progress is measurable.*

## Quality Metrics

| Metric | Target | Current Status |
|--------|--------|----------------|
| Offline functionality % | > 95% | ✅ Achieved |
| First chat started (< 2 min) | < 2 min | ✅ Achieved |
| User retention (30 day) | > 60% | Awaiting release data |

---

*Last updated: 2026-02-27*
