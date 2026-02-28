<!--
  Sync Impact Report
  ===================
  Version change: 1.2.0 → 2.0.0
  Modified principles: I–VIII (added Rationale blocks to all)
  Added sections:
    - The Five Primitives (new architectural foundation section)
    - Development Philosophy (new meta-principles section)
    - Governance: added Compliance subsection (traceability requirement)
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

**Rationale**: A single module with a flat package structure keeps the
dependency graph legible and build times fast. Multiple modules add
Gradle configuration overhead, cross-module API surfaces, and navigation
indirection — costs that only pay off at a team scale this project doesn't
have. The single `UiState` pattern makes every screen's behavior
deterministic and inspectable from one place.

### II. Simplicity & No Premature Abstraction

No dependency injection framework. Manual construction and singleton
patterns MUST be used (via an `AppContainer` or equivalent). Repository
and service layers MUST exist only when they serve more than one consumer
or isolate a testable boundary. Do not introduce abstractions, interfaces,
or wrapper layers for components with a single implementation. YAGNI:
features and infrastructure MUST only be built when the current task
requires them.

**Rationale**: Every abstraction is a prediction about future change. Wrong
predictions create indirection that obscures the system without enabling
anything. In a single-developer project, the cost of adding a layer later
is low; the cost of maintaining a premature one is constant. Simplicity
compounds — each unnecessary layer removed makes every future change easier.

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

**Rationale**: High coverage numbers create a false sense of security and
a real maintenance burden. A test that verifies a getter returns what was
set protects nothing. A test that verifies the stream state machine emits
`Complete` after the final delta protects the core experience. Tests are
code — they have a carrying cost. Every test must earn its keep.

### IV. Conventional Commits & Semantic Release

All commits MUST follow the Conventional Commits specification
(`type(scope): description`). The `main` branch MUST use semantic-release
to determine version bumps. Version codes MUST follow the formula
`MAJOR*10000 + MINOR*100 + PATCH`. Breaking changes MUST include a
`BREAKING CHANGE:` footer. Commit types: `feat`, `fix`, `chore`, `docs`,
`refactor`, `test`, `perf`, `ci`.

**Rationale**: Automated release requires machine-readable commit history.
Conventional Commits make the changelog, the version number, and the
release notes derivable from the same source. This eliminates "what
version should this be?" debates and makes every release auditable back
to its constituent changes.

### V. Accessibility-First

Accessibility MUST NOT be treated as a polish task. All Compose UI
components MUST include `contentDescription` for non-text elements, use
`semantics` blocks where needed, and meet 48dp minimum touch targets from
initial implementation. Layouts MUST support dynamic font scaling without
breakage. Color contrast MUST meet WCAG AA (4.5:1 for normal text).

**Rationale**: Accessibility retrofitted is accessibility compromised. When
semantics are bolted on after visual design, they describe what the screen
looks like, not what it means. When built in from the start, accessibility
shapes the component model itself — and the result works better for
everyone, not just assistive technology users.

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

**Rationale**: Specs written in behavior language force clarity about what
the system does before how it does it. When a test says "given a local
model loaded, when the user sends a message, then tokens stream without
network access" — that is simultaneously the requirement, the test, and
the documentation. Ambiguity dies in the Given/When/Then.

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

**Rationale**: A chat interface is intimate. The user stares at it,
waiting for tokens. Every janky transition, every missing loading state,
every inconsistent margin breaks the illusion that they are talking to
something intelligent. Polish is not vanity — it is trust. A polished UI
says "someone cared about this," and that care transfers to confidence
in the AI behind it.

### VIII. Debuggable Error Handling

All caught exceptions that affect user-visible behavior MUST be logged
with `android.util.Log.e()` at the point of catch, including the
exception's stack trace (pass the exception as the third argument). Error
messages shown to users (toasts, snackbars, UI state) MUST have a
corresponding logcat entry at ERROR level with a tag matching the class
name. Domain-layer error paths (e.g., `StreamState.Error` emissions)
MUST log before emitting so errors are traceable even when the UI
consumer changes. Debug-level logging (`Log.d()`) SHOULD be used
sparingly for key lifecycle events: connection attempts, stream
start/end, and state transitions. Exceptions MUST NOT be silently
swallowed — if recovery is intentional, log at WARN level with
justification.

**Rationale**: On-device inference and remote API calls fail in different,
sometimes exotic ways — OOM kills, GGUF format mismatches, SSL errors,
context window overflows. Without disciplined logging, debugging becomes
"reproduce it and hope." Every error the user sees must have a logcat
breadcrumb trail that leads back to root cause without requiring the user
to explain what happened.

## The Five Primitives

Every feature connects back to the foundational architecture. When
building, the implementor MUST ask: which primitives does this
strengthen? Which does it ignore? Which would make this feature
transformative if they were connected?

1. **Conversation Tree** — Conversations contain messages organized as a
   tree with branching. Each message can have multiple children, enabling
   regeneration, exploration, and edit-and-continue without data loss.
   Tables: `conversations`, `messages` with `parent_message_id`.

2. **Dual Inference Pipeline** — A unified streaming interface that
   produces identical `ChatCompletionChunk` events from either remote
   OpenAI-compatible APIs or local GGUF models via llama.cpp. The
   consumer never knows — and never needs to know — where the tokens
   came from. Classes: `OpenAiApiClient`, `LocalLlmClient`,
   `InferenceProvider`.

3. **Stream State Machine** — A single event flow (`StreamState`: Delta,
   Complete, ToolCallsPending, ToolCallResult, Compacting, Error) that
   coordinates message persistence, token counting, tool execution, and
   conversation compaction. Every async event in the chat lifecycle flows
   through this machine. Class: `ChatManager`.

4. **Model Lifecycle** — Discovery, download (with resume), storage,
   metadata tracking, selection, loading, and memory-pressure unloading
   of local GGUF models. The full arc from "I found a model on the web"
   to "it's generating tokens on my phone." Classes: `LocalModelStore`,
   `ModelDownloadWorker`, `LlmEngine`.

5. **Parameter Space** — Temperature, max tokens, system prompt, top-p,
   and other generation parameters, stored as defaults in settings and
   overridable per conversation via presets. The bridge between "how I
   usually want the AI to behave" and "how I want it to behave right
   now." Stores: `SettingsDataStore`, `ParameterPresetEntity`.

**Quality Layers** (cross-cutting, not stored as separate primitives):

- **Privacy Boundary** — Local-first by design. No data leaves the
  device unless the user explicitly configures a remote server. API keys
  encrypted at rest via Tink. The user's conversations are theirs alone.
- **Resource Awareness** — This runs in someone's pocket. Memory
  pressure from loaded models, GPU offload decisions, battery impact of
  sustained inference, download bandwidth — every feature must respect
  the device's finite resources.

## Development Philosophy

### Feel the Conversation

Every technical decision affects the experience of talking to an
intelligent agent. "Streaming latency is 200ms" becomes "the AI pauses
awkwardly before responding." "The model loads in 3 seconds" becomes "the
user waits, wondering if it's broken." When evaluating an implementation,
translate the technical fact into the human experience it creates. The
feeling of the conversation is the product.

### Respect the Device

This is not a cloud service with infinite resources. It runs on a phone
someone carries in their pocket — with limited RAM, battery, and thermal
headroom. Every feature MUST consider its resource footprint. A model
download should not drain the battery. Inference should not make the
phone hot. Loading a model should not kill background apps. The device is
borrowed, not owned — treat it with respect.

### Build Primitives, Not Features

The surface problem is rarely the real problem. "The user can't switch
models mid-conversation" is a feature request; the deeper need is that
the Dual Inference Pipeline should be hot-swappable. When building, trace
the request to the primitive it reveals. Strengthen the primitive — the
feature follows. Five strong primitives compose into hundreds of
capabilities; a hundred ad-hoc features compose into nothing.

### Honest About Limits

When a local model can't handle a task, say so clearly — don't hallucinate
competence. When a download fails, show why — don't just say "error."
When the device can't load a 13B model, explain the constraint — don't
silently fail. The user is trusting a pocket-sized AI with their
conversations. That trust is maintained through transparency about what
the system can and cannot do.

## Technical Constraints

- **Language**: Kotlin (version managed by Kotlin Gradle Plugin)
- **JDK**: 21
- **Android SDK**: compileSdk 36, minSdk 28, targetSdk 36
- **UI**: Jetpack Compose with Compose BOM for version alignment
- **Build**: Gradle with Kotlin DSL, single `app/` module
- **Native**: llama.cpp via JNI (`pocketllm-llm`), CMake build, arm64-v8a
- **CI**: GitHub Actions, semantic-release on `main`
- **Package**: `dev.nutting.pocketllm`
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

This constitution defines non-negotiable principles for the Pocket LLM
project. It supersedes default implementation patterns when they conflict
with these principles.

**Amendment Procedure**:

1. Proposed amendments MUST state which principle is affected and why
   the change is necessary.
2. Amendments MUST include a migration assessment: which existing
   features or specs would need to change.
3. Version increments follow semantic versioning: MAJOR for principle
   removal or redefinition, MINOR for new principles or material
   expansion, PATCH for clarification or wording.

**Compliance**:

- All feature specifications MUST be reviewable against these principles.
  Each spec's requirements (FR-NNN) SHOULD trace to at least one
  principle.
- The plan template's "Constitution Check" section MUST be populated
  with gates derived from these principles before Phase 0 research
  begins.
- When a feature conflicts with a principle, the conflict MUST be
  documented in the spec's Assumptions section with justification.

All code review MUST verify compliance with these principles. Complexity
beyond what is described here MUST be justified in the plan's Complexity
Tracking table.

**Version**: 2.0.0 | **Ratified**: 2026-02-02 | **Last Amended**: 2026-02-27
