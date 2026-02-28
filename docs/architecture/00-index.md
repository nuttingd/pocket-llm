# Architecture Documentation

This directory contains the living technical documentation for Pocket LLM.

---

## Structure

### decisions/
Architecture Decision Records (ADRs) for major architectural choices.

**Naming Convention**: `NNN-description.md` where NNN is sequential number.

### patterns/
Reusable design patterns and their implementations.

**What goes here**:
- Patterns that solve recurring problems
- Anti-patterns to avoid
- Integration patterns with external systems

### context/
Background information about the architecture.

**What goes here**:
- Rationale for key decisions
- System boundaries and interfaces
- Technology choices and trade-offs

---

## How to Use This Documentation

### For New Contributors

1. Read `north-star.md` to understand the vision
2. Read `capability-ladder/00-milestones.md` for progression
3. Read this index for technical overview
4. Follow links to specific ADRs/patterns as needed

### For LLM-Driven Development

When writing code:

1. **Check North Star**: Does this align with the vision?
2. **Check Capability Ladder**: Which milestone does this serve?
3. **Check Architecture**: Are there relevant ADRs or patterns?
4. **Write ADR if necessary**: Major changes need documented rationale

---

## Current Status

- [x] North Star specification created
- [x] Capability ladder milestones defined
- [ ] ADRs for key decisions (to be created)
- [ ] Patterns catalog (to be populated)

---

*This documentation is the single source of truth for how Pocket LLM works and why it works that way.*

## Next Steps

1. Document existing architecture as ADRs
2. Add patterns used in current implementation
3. Populate context for technology choices
