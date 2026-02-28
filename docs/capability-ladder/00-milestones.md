# Capability Ladder: Milestone Roadmap

**North Star Reference**: See ../north-star.md

This document defines the progression from current state (V1) to platform phase (V4).

---

## Milestone Timeline

| Milestone | Timeframe | Focus | Success Metric |
|-----------|-----------|-------|----------------|
| M0: Baseline | Now | What we have | Feature-complete V1 |
| M1: Orchestrator | Month 3 | Knowledge + Agents | User can maintain a personal knowledge base |
| M2: Federated | Month 6 | Privacy-aware learning | Improved models without data leakage |
| M3: Platform | Month 12 | Ecosystem | Users treat app as primary AI interface |

---

## Milestone Details

### M0: Baseline (Current State)

**Status**: In progress / Complete V1 features

**What we have**:
- OpenAI-compatible API client
- Local LLM inference via llama.cpp
- Streaming chat with conversation history
- Tool calling framework (calculator, web fetch)
- Context compaction
- Parameter presets

**Key primitives**:
1. Conversation Tree
2. Dual Inference Pipeline
3. Stream State Machine
4. Model Lifecycle
5. Parameter Space

**Quality Gates**:
- ./gradlew assembleDebug succeeds
- All BDD acceptance scenarios pass
- Accessibility passes TalkBack navigation

---

### M1: Orchestrator Phase (3 months)

**Theme**: Personal Knowledge Orchestrator

Users can maintain and query a personal knowledge base while delegating tasks to agents.

**Key Capabilities Added**:
1. Document RAG - Upload PDF/text, embed locally, semantic search
2. Agent Framework - Create custom agents with tool bundles
3. Context-Aware Tools - Tools that understand conversation history

**Quality Gates**:
- Document upload + search works offline
- Agent completes 3-step task without user intervention
- Search returns results in < 5 seconds on 1000+ documents

---

### M2: Federated Phase (6 months)

**Theme**: Privacy-Aware Learning Platform

The platform becomes smarter for each user while preserving complete privacy.

**Key Capabilities Added**:
1. Federated Learning - Opt-in anonymous statistics, distributed model improvements
2. Cross-Device Sync - Conversation state via Android/iOS system APIs
3. Advanced RAG - Document embeddings + semantic retrieval

**Quality Gates**:
- Federated updates improve response quality
- Cross-device sync works zero-config
- RAG recall rate > 80% on test documents

---

### M3: Platform Phase (12 months)

**Theme**: Open Agent Ecosystem

Users treat Pocket LLM as their primary AI interface with a complete ecosystem.

**Key Capabilities Added**:
1. Agent Network - Multiple concurrent agents, resource-aware scheduling
2. Community Tools - Shared tool library maintained by community
3. Offline Workstation - Predictive model loading, smart swapping

**Quality Gates**:
- Agent network completes complex tasks > 80% of time
- User can rebuild workflow from exported files
- System maintains responsive UI during extended inference

---

## Milestone Dependencies

M0 -> M1 -> M2 -> M3

Critical Path: M0 must be stable before M1 begins. Each milestone's quality gates must pass before proceeding.

---

## Go/No-Go Criteria

**GO if**:
1. All quality gates pass
2. North Star principles are upheld (local-first, privacy, etc.)
3. User testing shows clear value improvement
4. Technical debt is documented and tracked

**NO-GO if**:
1. Requires cloud dependency or data leakage
2. Performance degrades below targets
3. Accessibility fails TalkBack testing
4. User adoption stalls (< 50% retention)

---

## Tracking Milestone Progress

Checklist for each milestone:

- All acceptance criteria met
- North Star alignment reviewed
- Architecture Decision Records (ADRs) written
- Tests passing for new functionality
- Accessibility audit complete
- Performance benchmarks passed
- User testing feedback incorporated

---

*This ladder is the roadmap. Each rung must be solid before climbing to the next.*
