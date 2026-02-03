<!--
  Sync Impact Report
  ===================
  Version change: 1.0.0 → 1.1.0
  Modified principles:
    - III. Test Coverage: refined to emphasize value over volume
  Added sections:
    - VI. BDD Spec-Driven Development (new principle)
    - VII. Polished Material Design UX (new principle)
  Removed sections: none
  Templates requiring updates:
    - .specify/templates/plan-template.md ✅ no changes needed (generic gates)
    - .specify/templates/spec-template.md ✅ no changes needed (BDD already used)
    - .specify/templates/tasks-template.md ✅ no changes needed (generic structure)
  Follow-up TODOs: none
-->

# Pocket LLM Constitution

## Core Principles

### I. MVVM-Compose Single Module

All UI MUST be built with Jetpack Compose and Material 3. State MUST flow
through a single `UiState` data class per ViewModel. The app MUST remain a
single Gradle module (`app/`). Feature separation MUST be achieved through
packages, not modules. ViewModels MUST NOT hold references to Android
framework classes (Activity, Context) — use `Application` only when
unavoidable.

### II. Simplicity & No Premature Abstraction

No dependency injection framework. Manual construction and singleton
patterns MUST be used (via an `AppContainer` or equivalent). Repository
and service layers MUST exist only when they serve more than one consumer
or isolate a testable boundary. Do not introduce abstractions, interfaces,
or wrapper layers for components with a single implementation. YAGNI:
features and infrastructure MUST only be built when the current task
requires them.

### III. Test Coverage — Value Over Volume

Tests MUST target what matters: critical business logic, data
transformations, parsers, state management, and integration boundaries.
Do NOT chase line coverage metrics. Every test MUST justify its existence
by protecting against a real failure mode. Tests MUST use JUnit 4 +
Robolectric for local JVM tests — instrumented tests only when hardware
or framework interaction is required. Test file placement MUST mirror
source package structure under `app/src/test/`. Mocking MUST be kept
minimal; prefer fakes and in-memory implementations. Trivial getters,
simple data classes, and pure UI layout code MUST NOT be unit tested.

### IV. Conventional Commits & Semantic Release

All commits MUST follow the Conventional Commits specification
(`type(scope): description`). The `main` branch MUST use semantic-release
to determine version bumps. Version codes MUST follow the formula
`MAJOR*10000 + MINOR*100 + PATCH`. Breaking changes MUST include a
`BREAKING CHANGE:` footer. Commit types: `feat`, `fix`, `chore`, `docs`,
`refactor`, `test`, `perf`, `ci`.

### V. Accessibility-First

Accessibility MUST NOT be treated as a polish task. All Compose UI
components MUST include `contentDescription` for non-text elements, use
`semantics` blocks where needed, and meet 48dp minimum touch targets from
initial implementation. Layouts MUST support dynamic font scaling without
breakage. Color contrast MUST meet WCAG AA (4.5:1 for normal text).

### VI. BDD Spec-Driven Development

Development MUST be driven by the BDD acceptance scenarios defined in
the feature specification. Each user story's Given/When/Then scenarios
are the source of truth for what to build and how to verify it. Tests
MUST be written in BDD style describing behavior, not implementation.
Test names MUST read as specifications (e.g., `given server configured
when user sends message then response streams progressively`). Do NOT
write tests that are not traceable to a spec scenario or edge case.
Implementation MUST NOT begin on a story until its acceptance scenarios
are reviewed and understood.

### VII. Polished Material Design UX

The app MUST follow current Material Design 3 guidelines and modern
Android UX conventions. This includes: proper use of Material 3
components (TopAppBar, NavigationDrawer, BottomSheet, FAB, Snackbar),
appropriate motion and transitions between screens, consistent spacing
and typography using the Material type scale, proper loading/empty/error
states for every screen, edge-to-edge layout with correct inset handling,
and predictable back navigation. The UI MUST feel native and polished —
not like a web wrapper or prototype. Animations MUST be purposeful and
follow Material motion principles (container transforms, shared axis).

## Technical Constraints

- **Language**: Kotlin (version managed by Kotlin Gradle Plugin)
- **JDK**: 21
- **Android SDK**: compileSdk 36, minSdk 28, targetSdk 36
- **UI**: Jetpack Compose with Compose BOM for version alignment
- **Build**: Gradle with Kotlin DSL, single `app/` module
- **CI**: GitHub Actions, semantic-release on `main`
- **Package**: `dev.nutting.template` (to be renamed per feature needs)
- **Secrets**: API keys and credentials MUST NOT be committed. Use
  `keystore.properties` (gitignored) or environment variables.

## Quality Gates

- `./gradlew assembleDebug` MUST succeed with zero errors before any PR.
- `./gradlew test` MUST pass all unit tests before any PR.
- `./gradlew lintDebug` MUST report zero errors (warnings acceptable
  with justification).
- Every PR MUST follow Conventional Commits in all included commits.
- Every new screen MUST be manually verified with TalkBack enabled
  before the story is marked complete.
- Every user story MUST have its BDD acceptance scenarios passing
  before the story is marked complete.
- UI MUST be reviewed against Material Design 3 guidelines for each
  new screen or component.

## Governance

This constitution supersedes ad-hoc decisions. Amendments require:

1. A description of the change and its rationale.
2. Version bump following semantic versioning (MAJOR for principle
   removal/redefinition, MINOR for additions, PATCH for clarifications).
3. Update to this file and propagation to any affected templates or
   guidance documents.

All code review MUST verify compliance with these principles. Complexity
beyond what is described here MUST be justified in the plan's Complexity
Tracking table.

**Version**: 1.1.0 | **Ratified**: 2026-02-02 | **Last Amended**: 2026-02-02
