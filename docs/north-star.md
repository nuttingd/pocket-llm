# Pocket LLM North Star Specification

**Version**: 1.0.0  
**Date**: 2026-02-27  
**Status**: Ratified  
**Amendment Process**: See Governance section below

---

## Vision Statement

To be the world's most private, capable on-device LLM platform—where users own their data completely while accessing capabilities that rival cloud-based assistants.

We are not building a chat client. We are building an **operating system for personal intelligence**.

This app will run entirely on the user's device with no mandatory cloud dependency, yet deliver capabilities that exceed what any remote API can provide because:
- No network latency or bandwidth limits
- Complete understanding of user context (past conversations, documents, tools)
- Models optimized for *this* device and *this* user's usage patterns
- True privacy by design—not an afterthought

---

## Core Principles (Non-Negotiables)

### 1. Local-First by Architecture, Not Compromise

**Requirement**: All core functionality must work without internet connectivity.

| Feature | Must Work Offline | Rationale |
|---------|-------------------|-----------|
| Chat with local models | Yes | User's primary interface to AI |
| Conversation history | Yes | Core data that defines the app |
| Tool execution (calculator) | Yes | Basic functionality |
| Image attachments (local only) | Yes | Visual communication is human |
| Parameter presets | Yes | User preferences belong to user |

| Feature | Requires Cloud | Alternative if Offline |
|---------|---------------|----------------------|
| Remote model inference | No internet needed | Use local models or warn user |
| Web fetch tool | Optional feature | Disable gracefully with message |
| Model download | One-time setup | Pre-download on Wi-Fi, cache locally |

**Success Metric**: 95% of chat interactions work offline once initial model is downloaded.

---

### 2. User Data Ownership (Absolute Privacy)

**Requirement**: User's conversations, documents, and custom tools never leave their device unless explicitly shared by user.

- Conversations stored only on-device
- API keys encrypted at rest with Tink
- No telemetry or usage analytics without explicit opt-in
- No tracking of search terms, prompts, or patterns
- Export functionality must produce self-contained files

**Out of Scope**: Cloud backup, sync across devices (use system backups)

**Success Metric**: Zero user data stored on any server owned by us or third parties.

---

### 3. Open Architecture (Shareability)

**Requirement**: Users should be able to share and discover tools, agents, and models without central authority.

- Export/import parameter presets
- Export/import tool definitions (JSON)
- Model files are standard GGUF format (community standard)
- Conversation export as markdown (portable)
- No proprietary formats

**Success Metric**: Users can share a complete agent workflow between devices without our involvement.

---

### 4. Resource Respect (Mobile by Design)

**Requirement**: Every feature must respect device constraints—battery, RAM, thermal.

| Constraint | Target |
|------------|--------|
| App launch time | < 2 seconds |
| Model load time | < 5 seconds (on-device) |
| Streaming latency | < 1 token/second delay |
| Background memory | < 50 MB when idle |
| Battery impact | No more than messaging apps |

**Success Metric**: User reports "the phone doesn't get hot" and "battery lasts all day."

---

## Success Criteria

### V1 (Now) — Current State
- OpenAI-compatible API client working
- Local LLM inference via llama.cpp
- Streaming chat with conversation history
- Tool calling framework
- Context compaction

**Baseline for measurement**

---

### V2 (3 months) — Orchestrator Phase
Users can:

1. **Maintain a personal knowledge base**
   - Upload and search documents
   - Link conversations to documents
   - Semantic search across all content

2. **Delegate tasks to agents**
   - Create custom agents with specific tools
   - Agent executes multi-step workflows
   - User approves key decisions

3. **Build context-aware tooling**
   - Tools that understand conversation history
   - Context-aware parameter selection
   - Auto-suggested actions based on patterns

**Quality Gates for V2:**
- Document upload + search works offline
- Agent can complete a 3-step task without user intervention
- Search returns results in < 5 seconds on 1000+ documents

---

### V3 (6 months) — Federated Phase
The platform becomes smarter *for each user* while preserving privacy:

1. **Federated learning**
   - User opt-in anonymous statistics collected
   - Improved models distributed via peer-to-peer
   - Personal model optimizations learned locally

2. **Cross-device continuity**
   - Conversation state syncs via system APIs (Android/iOS)
   - Phone → tablet → laptop seamless transition
   - All sync encrypted end-to-end

3. **Advanced RAG**
   - Document embeddings on-device
   - Semantic retrieval from personal corpus
   - Context-aware responses drawn from user's knowledge base

**Quality Gates for V3:**
- Federated model updates improve response quality (measured by user rating)
- Cross-device sync works with zero configuration
- RAG recall rate > 80% on test documents

---

### V4 (12 months) — Platform Phase
Users treat Pocket LLM as their primary AI interface:

1. **Agent network**
   - Multiple concurrent agents working on different tasks
   - Agent-to-agent communication
   - Resource-aware task scheduling (battery-conscious execution)

2. **Community ecosystem**
   - Shared tool library (community-maintained)
   - Model recommendations based on usage patterns
   - Exportable conversation archives with full context

3. **Offline workstation**
   - Predictive model preloading based on calendar/usage
   - Smart model swapping for different tasks
   - Work mode that optimizes for focus (no distractions)

**Quality Gates for V4:**
- Agent network completes complex multi-step task > 80% of time
- User can rebuild their entire workflow from exported files
- System maintains responsive UI during extended inference sessions

---

## Out of Scope (Explicit Boundaries)

### Never (Core Philosophy)
| Feature | Reason |
|---------|--------|
| Cloud backup/sync | Violates local-first principle; use system backups |
| Social features (sharing chats publicly) | Not the user's chat to share; conversation is personal |
| Browser extension | Focus on mobile first; desktop can be future phase |
| iOS version | Android only for now (resources focused) |
| Web app version | Local-first requires native capabilities |

### Maybe Later (Depends on Community)
| Feature | Condition |
|---------|-----------|
| Web fetch tool improvements | Only if user opt-in analytics show demand |
| Voice input/output | Requires significant ML model addition |
| Code execution sandbox | Security implications; community review needed |
| Third-party plugin store | May violate local-first principle |

---

## Success Metrics (Measurable Outcomes)

### Performance
| Metric | Target | Measurement |
|--------|--------|-------------|
| App launch time | < 2s | From cold start to first screen |
| Model load time | < 5s | Downloaded GGUF → generating tokens |
| Streaming latency | < 1 token/s delay | Measured from server response to display |
| Search time (1000 docs) | < 5s | Full-text + semantic search |
| Chat memory usage | < 100 MB | Active conversation with history |

### User Experience
| Metric | Target | Measurement |
|--------|--------|-------------|
| First chat started | < 2 min | From install to first streamed response |
| Offline functionality % | > 95% | Conversations that work without internet |
| User retention (30 day) | > 60% | Active users after 30 days |
| Accessibility pass rate | 100% | All screens pass TalkBack navigation |

### Technical Quality
| Metric | Target | Measurement |
|--------|--------|-------------|
| Test coverage (value tests) | > 80% | Critical paths tested, not trivial getters |
| Lint errors | 0 | Zero errors before any PR |
| Crash rate | < 0.1% | Sessions without crash per 1000 sessions |

---

## Non-Functional Requirements

### Privacy & Security
- All user data encrypted at rest (Room with SQLCipher if needed)
- API keys stored in encrypted DataStore (Tink AES)
- No analytics without explicit opt-in
- Transparent about what's on-device vs. what requires remote

### Accessibility
- TalkBack fully supported on all screens
- 48dp minimum touch targets
- Dynamic font scaling works without layout breakage
- Color contrast meets WCAG AA (4.5:1)

### Reliability
- Graceful degradation when features unavailable
- Clear error messages with actionable recovery
- No silent failures—every user-facing error has logcat trace

---

## Governance

### Amendment Process

1. **Proposal**: Any contributor may propose an amendment with rationale
2. **Review**: Assess impact on existing specs, tests, and documentation
3. **Vote**: Core maintainer approval required for major changes
4. **Version**: Increment per semver (MAJOR for breaking changes)
5. **Update**: Update this document and all affected specs

### Compliance Audit

Every PR must answer:
- Does this align with the North Star vision?
- Which principle does it strengthen or weaken?
- Does it introduce any out-of-scope dependencies?

If a feature conflicts with a principle, it must be documented in the spec's Assumptions section with justification.

---

## Visionary End State

In 5 years, Pocket LLM will be:

> The only AI platform where you can say "I trust this completely" because:
> - Your most sensitive conversations are on your device
> - You own all your data and can export it at any time
> - The system learns from *you* without sharing with anyone else
> - It works anywhere—plane, basement, protest, remote village
> - It's open architecture so the community can improve it together

This isn't just an app. This is the foundation for a new category: **Personal AI operating systems**.

We start today with one conversation at a time.

---

*This document is the compass. Every line of code should be checked against it.*
