# Governance and Decision-Making

This document defines how decisions are made, documented, and tracked in Pocket LLM.

---

## The Three-Layer Architecture

Our documentation is structured to support LLM-driven development:

```
┌─────────────────────────────────────────────────────────────┐
│                    NORTH STAR (docs/north-star.md)          │
│  Vision, principles, non-negotiables                        │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              CAPABILITY LADDER (docs/capability-ladder/)    │
│  Milestones → Features → Tasks                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                 ARCHITECTURE (docs/architecture/)           │
│  Decisions + Patterns + Context                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Decision-Making Process

### Step 1: Align with North Star

Every decision must pass the **North Star Check**:

```kotlin
fun isAlignmentValid(feature: Feature): Boolean {
    return feature.localFirst() &&
           feature.privacyFirst() &&
           feature.openArchitecture() &&
           feature.resourceRespectful()
}
```

If a feature conflicts with a principle, it must be documented in the spec's Assumptions section.

---

### Step 2: Identify Capability Ladder Position

Which milestone does this serve?

| Milestone | Features In Scope |
|-----------|-------------------|
| M0 | Core chat client features (baseline) |
| M1 | Orchestrator (Knowledge + Agents) |
| M2 | Federated learning, cross-device sync |
| M3 | Platform ecosystem |

**Rule**: If it's not in the capability ladder, it's out of scope.

---

### Step 3: Check Architecture

Search for existing patterns and ADRs:

```bash
# Existing ADR?
grep -r "your-feature" docs/architecture/decisions/

# Existing pattern?
grep -r "your-use-case" docs/architecture/patterns/
```

If neither exists, create one.

---

## Architectural Decision Records (ADRs)

### What is an ADR?

An ADR documents a significant architectural decision with:
- **Problem** being solved
- **Solution** chosen
- **Alternatives** considered
- **Trade-offs** understood

### When to Create an ADR

Create an ADR when:

- [ ] Adding new major component
- [ ] Changing core architecture
- [ ] Introducing new patterns
- [ ] Making technology choices
- [ ] Establishing boundaries

**Don't create ADRs for**:
- Bug fixes (use commit messages)
- Small refactorings
- Documentation updates

---

### ADR Template

```markdown
# ADR NNN: Title

**Date**: YYYY-MM-DD  
**Status**: Draft / Approved / Superseded  
**Author**: Name  

---

## Problem

Describe the problem being solved.

## Solution

Describe the chosen solution.

## Alternatives Considered

List alternatives and why they were rejected.

## Trade-offs

What are the pros/cons of this decision?

## Implementation Requirements

How should this be implemented?

## Compliance Checklist

- [ ] Requirement 1
- [ ] Requirement 2
```

---

## Quality Gates

### Build Quality Gate

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lintDebug
```

All must pass before any PR.

### Accessibility Gate

Every new screen:
1. Test with TalkBack enabled
2. Verify 48dp minimum touch targets
3. Check dynamic font scaling works

### North Star Compliance Gate

PR checklist:
- [ ] Aligns with north-star.md
- [ ] Capability ladder milestone identified
- [ ] Architecture documentation updated (if needed)

---

## The LLM Development Loop

```
┌──────────────┐
│   USER PROMPT│
└──────┬───────┘
       │
       ▼
┌────────────────────────────────────────────────────────────┐
│ 1. CHECK NORTH STAR                                        │
│    "Does this align with our vision and principles?"      │
└──────┬─────────────────────────────────────────────────────┘
       │ Yes/No
       │
       ▼ (Yes)
┌────────────────────────────────────────────────────────────┐
│ 2. IDENTIFY CAPABILITY LADDER                              │
│    "Which milestone does this serve?"                     │
└──────┬─────────────────────────────────────────────────────┘
       │ M0/M1/M2/M3
       │
       ▼
┌────────────────────────────────────────────────────────────┐
│ 3. REVIEW ARCHITECTURE                                     │
│    "Are there relevant ADRs or patterns?"                 │
└──────┬─────────────────────────────────────────────────────┘
       │ Found/Not found
       │
       ▼ (Found)
┌────────────────────────────────────────────────────────────┐
│ 4. IMPLEMENT PER PATTERN                                   │
│    Follow established architecture                        │
└──────┬─────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────────┐
│ 5. TEST AND VERIFY                                         │
│    Run quality gates                                       │
└──────┬─────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────────┐
│ 6. DOCUMENT IF MAJOR CHANGES                               │
│    Create/Update ADRs                                     │
└────────────────────────────────────────────────────────────┘
```

---

## Conflict Resolution

### When North Star Conflicts with Feature Request

**Process**:
1. Document the conflict in spec's Assumptions
2. Explain why exception is necessary
3. Get maintainer approval
4. Add to ADR for future reference

**Example**:
```
Assumption: Web fetch tool requires internet connectivity.
Justification: Core chat functionality works offline; web fetch is optional.
Decision: Allow, with clear documentation of limitation.
```

---

### When Architecture Doesn't Match Request

**Process**:
1. Identify which primitive needs strengthening
2. Document the gap in an ADR
3. Implement the enhancement
4. Update capability ladder if milestone shifts

**Example**:
```
Request: Add document search
Gap: No RAG primitive exists yet
Action: Create Document RAG ADR, add to M1 capability ladder
```

---

## Versioning and Evolution

### North Star Versioning

- **MAJOR**: Principle removed or redefined
- **MINOR**: New principle added, major feature added
- **PATCH**: Clarification, wording improvements

### Capability Ladder Versioning

- **MAJOR**: Milestone reordered or removed
- **MINOR**: Milestone added, features added to milestone
- **PATCH**: Quality gate adjustment, wording improvements

### Architecture Versioning

- **MAJOR**: Breaking change to pattern/ADR
- **MINOR**: New pattern/ADR added
- **PATCH**: Clarification, typo fixes

---

## Compliance Audit

Every PR triggers this checklist:

```
PR Submission Checklist:
[ ] North Star alignment reviewed
[ ] Capability ladder milestone identified  
[ ] Architecture documentation updated (if needed)
[ ] ./gradlew assembleDebug passes
[ ] ./gradlew test passes
[ ] Accessibility audit complete
[ ] Lint has no errors

Merge Approval: [ ] Maintainer sign-off
```

---

## The "Why" Behind This Structure

### Why Three Layers?

| Layer | Purpose |
|-------|---------|
| North Star | **What** we're building and why |
| Capability Ladder | **When** we'll build it |
| Architecture | **How** we'll build it |

LLMs need all three to be effective.

### Why ADRs for Decisions?

- **Traceability**: Every decision has a paper trail
- **Onboarding**: New contributors can understand history
- **Reversibility**: Easy to see why and undo if needed

---

## Final Authority

In case of conflict:

1. **North Star** is highest authority (non-negotiables)
2. **Capability Ladder** defines scope (what's in/out)
3. **Architecture** defines implementation (how)

If there's a discrepancy:
- Document the conflict
- Update the higher-priority document first
- Communicate changes to team

---

*This governance structure ensures we build the right thing, the right way, at the right time.*

## Quick Reference: Decision Checklist

Before making any architectural decision:

```
✅ Does it uphold North Star?
   - Local-first? (no mandatory cloud)
   - Privacy-first? (user data stays on device)
   - Open architecture? (shareable, extensible)
   - Resource-respectful? (mobile-optimized)

✅ Which milestone does it serve?
   - M0: Baseline
   - M1: Orchestrator
   - M2: Federated
   - M3: Platform

✅ Are there existing ADRs or patterns to follow?

✅ Does implementation meet quality gates?
   - ./gradlew assembleDebug
   - ./gradlew test
   - Accessibility (TalkBack)
   - Lint checks

✅ Document if major change.
```

---

*Last updated: 2026-02-27*
