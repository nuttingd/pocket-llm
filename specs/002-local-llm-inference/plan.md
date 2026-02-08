# Implementation Plan: Local LLM Inference

**Branch**: `002-local-llm-inference` | **Date**: 2026-02-07 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-local-llm-inference/spec.md`

## Summary

Add on-device LLM inference to Pocket LLM by porting the llama.cpp JNI engine from snacktrack. Users can download models from a built-in registry (or import custom GGUF files), manage them via Settings > Local Models, and chat using on-device inference with optional GPU acceleration. Local and remote models are interchangeable mid-conversation via an InferenceProvider abstraction. The port removes snacktrack-specific extraction logic (JSON grammar constraints, NutritionExtraction parsing) and adapts the engine for free-form chat streaming.

## Technical Context

**Language/Version**: Kotlin (KGP 2.2.10), JDK 21, C++17 (llama.cpp native code via NDK)
**Primary Dependencies**: All from 001-llm-chat + llama.cpp (git submodule), OkHttp 4.12.0 (model downloads), WorkManager 2.11.0 (background downloads)
**Storage**: Room (existing, migration 6→7 for `isLocalInference` column), DataStore Preferences (model metadata as JSON), app-private external storage (GGUF model files)
**Testing**: JUnit 4 + Robolectric (existing), WorkManager testing utilities
**Target Platform**: Android (compileSdk 36, minSdk 28, targetSdk 36), arm64-v8a only (NDK)
**Project Type**: Single-module Android app (`app/`) — native code inlined via CMake
**Performance Goals**: First token <10s (SC-101), ≥3 tok/s on mid-range devices (SC-102), crash recovery <3s (SC-103)
**Constraints**: Offline-capable (no server needed), model files 1-3 GB each, arm64-v8a only, context window default 2048 tokens (user-configurable)
**Scale/Scope**: Single user, ~3 registry models + custom imports, 1 new screen (Model Management), modifications to 5-6 existing files

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVVM-Compose Single Module | PASS | Native code inlined into `app/` module via CMake (no separate `:llm` module). Feature separated by packages: `llm/`, `data/local/model/`, `ui/modelmanagement/` |
| II. Simplicity & No Premature Abstraction | PASS | InferenceProvider interface justified: serves two consumers (RemoteInferenceProvider + LocalInferenceProvider) and isolates a testable boundary. See Complexity Tracking. |
| III. Test Coverage - Value Over Volume | PASS | Tests target: provider selection logic, token streaming format conversion, LocalModelStore persistence, download resume logic. No testing of JNI bridge (requires device). |
| IV. Conventional Commits & Semantic Release | PASS | All commits follow `type(scope): description` |
| V. Accessibility-First | PASS | ModelManagementScreen: contentDescription on all buttons (download, delete, import, select), progress indicators announced, 48dp touch targets, model RAM warnings accessible |
| VI. BDD Spec-Driven Development | PASS | 5 user stories with Given/When/Then scenarios drive implementation |
| VII. Polished Material Design UX | PASS | ModelManagementScreen uses M3 components (cards, progress indicators, dialogs, snackbar for errors), loading/empty/error states for all sections |
| VIII. Debuggable Error Handling | PASS | Native crash recovery logged at ERROR level, download failures logged, model load errors logged. All catch blocks include `Log.e()` with TAG and throwable. |

**Post-Phase 1 Re-check**: All principles still pass. InferenceProvider interface is the only new abstraction — justified by two implementations and testable boundary isolation. No additional complexity introduced.

## Project Structure

### Documentation (this feature)

```text
specs/002-local-llm-inference/
├── plan.md              # This file
├── research.md          # Phase 0: technology decisions
├── data-model.md        # Phase 1: data model additions
├── quickstart.md        # Phase 1: setup guide and build order
├── contracts/           # Phase 1: internal interface contracts
│   └── inference-provider.md
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
app/src/main/java/dev/nutting/pocketllm/
├── ... (existing from 001-llm-chat)
├── data/
│   ├── local/
│   │   ├── PocketLlmDatabase.kt              # (modified) Add migration 6→7
│   │   ├── entity/
│   │   │   └── MessageEntity.kt              # (modified) Add isLocalInference column
│   │   └── model/
│   │       ├── LocalModel.kt                 # Data class + DownloadStatus enum
│   │       ├── ModelRegistryEntry.kt          # Registry entry data class
│   │       ├── ModelRegistry.kt               # Hardcoded model list
│   │       └── LocalModelStore.kt             # DataStore persistence
│   └── preferences/
│       └── SettingsDataStore.kt               # (modified) New keys
├── domain/
│   ├── ChatManager.kt                         # (modified) Use InferenceProvider
│   ├── InferenceProvider.kt                   # Interface
│   ├── RemoteInferenceProvider.kt             # Wraps OpenAiApiClient
│   └── LocalInferenceProvider.kt              # Wraps LlmEngine for chat
├── llm/
│   └── LlmEngine.kt                          # JNI wrapper (ported from snacktrack)
├── ui/
│   ├── modelmanagement/
│   │   ├── ModelManagementScreen.kt           # Full model management UI
│   │   ├── ModelManagementViewModel.kt        # Download/import/delete orchestration
│   │   └── ModelManagementUiState.kt          # UI state
│   ├── settings/
│   │   └── SettingsScreen.kt                  # (modified) Add Local Models entry + GPU slider
│   ├── chat/
│   │   └── ChatViewModel.kt                   # (modified) Provider selection
│   └── navigation/
│       ├── Routes.kt                          # (modified) Add ModelManagement route
│       └── AppNavGraph.kt                     # (modified) Add ModelManagement destination
├── util/
│   └── ModelDownloadWorker.kt                 # WorkManager download with progress
└── AppContainer.kt                            # (modified) Wire new dependencies

app/src/main/cpp/
├── CMakeLists.txt                             # Native build (ported from snacktrack)
└── llm_jni.cpp                                # JNI bridge (ported from snacktrack)

app/src/test/java/dev/nutting/pocketllm/
├── domain/
│   └── LocalInferenceProviderTest.kt
├── data/local/model/
│   └── LocalModelStoreTest.kt
└── util/
    └── ModelDownloadWorkerTest.kt

external/
└── llama.cpp/                                 # Git submodule
```

**Structure Decision**: Single `app/` module per constitution principle I. Native C++ code lives under `app/src/main/cpp/` with CMake build integration. The snacktrack `:llm` module is flattened into the app module. Package separation: `llm/` for engine, `data/local/model/` for model data, `ui/modelmanagement/` for UI.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| InferenceProvider interface | Two implementations (RemoteInferenceProvider + LocalInferenceProvider) that share the same ChatManager consumer. Isolates a testable boundary — ChatManager can be tested with a mock provider without real HTTP calls or JNI. | Direct if/else in ChatManager would duplicate streaming logic, make ChatManager harder to test (needs both API mock and JNI mock), and violate constitution II which permits interfaces "when they serve more than one consumer or isolate a testable boundary." Both conditions are met. |
