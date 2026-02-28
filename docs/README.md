# Pocket LLM Documentation

This directory contains all documentation for the Pocket LLM project.

---

## Quick Start

### For New Contributors

1. **Read the North Star** (`north-star.md`) - Understand our vision and non-negotiables
2. **Check the Capability Ladder** (`capability-ladder/`) - See where we're headed
3. **Review Architecture** (`architecture/`) - Learn how things work and why
4. **Start coding** - Follow the patterns and ADRs

### For LLM-Driven Development

When writing code:

```
1. Check north-star.md
   └─ Does this align with our vision?

2. Check capability-ladder/00-milestones.md
   └─ Which milestone does this serve?

3. Check architecture/
   ├─ decisions/ - Are there relevant ADRs?
   ├─ patterns/ - Can I reuse a pattern?
   └─ context/ - What's the background?

4. Document your decision if major
   └─ Add new ADR or update existing one
```

---

## Documentation Structure

```
docs/
├── north-star.md              # Vision and non-negotiables (READ FIRST)
├── capability-ladder/
│   ├── 00-milestones.md       # High-level progression
│   ├── 01-m1-orchestrator.md  # M1 details (Orchestrator Phase)
│   ├── 02-m2-federated.md     # M2 details (Federated Phase)
│   └── 03-m3-platform.md      # M3 details (Platform Phase)
└── architecture/
    ├── index.md               # Architecture docs overview
    ├── decisions/             # Architectural Decision Records
    │   ├── 001-mvvm-compose-single-module.md
    │   ├── 002-dual-inference-pipeline.md
    │   └── 003-stream-state-machine.md
    ├── patterns/              # Reusable design patterns
    │   └── 00-index.md
    └── context/               # Background and rationale
        ├── 00-index.md
        ├── 01-technical-stack.md
        ├── 02-system-boundaries.md
        ├── 03-inference-architecture.md
        └── 04-chat-flow.md
```

---

## Documents

### North Star (`north-star.md`)

**What it is**: The compass for all development.

**Contains**:
- Vision statement
- Core principles (non-negotiables)
- Success criteria by milestone
- Out of scope boundaries
- Quality gates and metrics

**Read this when**: You need to understand what we're building and why.

---

### Capability Ladder (`capability-ladder/`)

**What it is**: The roadmap from V1 to V4.

**Contains**:
- Milestone progression (M0 → M3)
- Feature definitions for each phase
- Quality gates and acceptance criteria

**Read this when**: You need to understand where we are going and how we'll measure success.

---

### Architecture Documentation (`architecture/`)

**What it is**: How the system works and why it's built that way.

**Subdirectories**:
- `decisions/` - ADRs for major architectural choices
- `patterns/` - Reusable design solutions
- `context/` - Background information

**Read this when**: You need to understand implementation details or add new features.

---

## The LLM Development Workflow

### Step 1: Align with North Star

Before writing code, ask:

> **Does this align with our vision?**
> - Local-first? (no mandatory cloud)
> - Privacy-first? (user data never leaves device)
> - Open architecture? (shareable, extensible)
> - Resource-respectful? (mobile-optimized)

If the answer is no, reconsider or document the exception.

---

### Step 2: Identify Your Milestone

Look in `capability-ladder/`:

| Milestone | Focus | Current Status |
|-----------|-------|----------------|
| M0 | Baseline (V1 features) | In progress |
| M1 | Orchestrator (Knowledge + Agents) | Not started |
| M2 | Federated (Privacy learning) | Not started |
| M3 | Platform (Ecosystem) | Not started |

---

### Step 3: Check Architecture

Search for existing patterns:

```bash
# Look for relevant ADRs
grep -r "your-feature" docs/architecture/decisions/

# Look for patterns
grep -r "your-use-case" docs/architecture/patterns/
```

If you find a relevant pattern, adapt it. If not, create a new one.

---

### Step 4: Implement

Follow the established patterns:
- Use single `UiState` per ViewModel
- Conform to Dual Inference Pipeline interface
- Flow through Stream State Machine for chat
- Respect all non-negotiables

---

### Step 5: Document Your Decision

If adding major new architecture:

1. Create ADR in `docs/architecture/decisions/NNN-description.md`
2. Add to this index if it's a foundational decision

---

## Quality Gates Checklist

Before PR submission:

- [ ] North Star alignment reviewed (see above)
- [ ] Capability ladder milestone identified
- [ ] Relevant ADRs followed or created
- [ ] Code follows documented patterns
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Tests pass for new functionality
- [ ] Accessibility passes TalkBack test
- [ ] Lint has no errors

---

## Contributing Guidelines

### For Features

1. **File a spec issue** - Describe the user story with BDD scenarios
2. **Review north-star.md** - Ensure alignment
3. **Implement per pattern** - Follow established architecture
4. **Write tests** - Cover critical paths and edge cases
5. **Update docs** - Add ADR for major changes

### For Refactoring

1. **Check patterns first** - Don't invent new if existing works
2. **Test thoroughly** - Ensure behavior unchanged
3. **Document rationale** - Why this change now?

---

## Maintenance

### Document Updates

When architecture changes:

1. Update affected ADRs with new info
2. Update patterns catalog if new pattern emerges
3. Update context docs if understanding changes

### Versioning

- North Star: MAJOR version for principle changes
- Capability Ladder: MINOR version for milestone additions
- Architecture: PATCH version for clarifications

---

## Questions?

Check these resources in order:

1. **north-star.md** - Vision and principles
2. **capability-ladder/00-milestones.md** - Where we're going
3. **architecture/context/** - How things work
4. **Code comments** - Implementation details

If still unclear, ask! Better to clarify than guess.

---

*This documentation is the single source of truth for Pocket LLM.*

## Current Version

- North Star: 1.0.0 (2026-02-27)
- Capability Ladder: M0 in progress
- Architecture: In active development

---

*Last updated: 2026-02-27*
