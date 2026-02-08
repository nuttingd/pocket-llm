# Tasks: Local LLM Inference

**Input**: Design documents from `/specs/002-local-llm-inference/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/inference-provider.md, quickstart.md

**Tests**: Included per constitution principle III (value over volume). Tests target critical boundaries: provider selection, token streaming format, LocalModelStore persistence. No JNI bridge tests (requires device).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Native Build Infrastructure)

**Purpose**: Add llama.cpp submodule and configure NDK/CMake native build within the single `app/` module

- [ ] T001 Add llama.cpp git submodule at external/llama.cpp and update .gitmodules
- [ ] T002 Port CMakeLists.txt from snacktrack/llm/src/main/cpp/ to app/src/main/cpp/CMakeLists.txt — rename library to libpocketllm-llm.so, update paths to reference external/llama.cpp relative to repo root
- [ ] T003 Port llm_jni.cpp from snacktrack/llm/src/main/cpp/ to app/src/main/cpp/llm_jni.cpp — rename JNI package from dev.nutting.snacktrack.llm to dev.nutting.pocketllm.llm, remove JSON grammar constraints and NutritionExtraction schema, add contextSize parameter to nativeLoadModel, keep crash handlers/progress callbacks/cancel/sampling/chat template logic
- [ ] T004 Update app/build.gradle.kts — add ndk abiFilters arm64-v8a, add externalNativeBuild cmake path, add OkHttp 4.12.0 and WorkManager 2.11.0 dependencies
- [ ] T005 Verify native build compiles: run ./gradlew assembleDebug and confirm libpocketllm-llm.so is produced

**Checkpoint**: Native llama.cpp library compiles and is bundled in the APK

---

## Phase 2: Foundational (Data Layer + InferenceProvider Abstraction)

**Purpose**: Core data models, persistence, and the InferenceProvider interface that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 [P] Create LocalModel data class and DownloadStatus enum in app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalModel.kt — per data-model.md, include contextWindowSize field (default 2048)
- [ ] T007 [P] Create ModelRegistryEntry data class in app/src/main/java/dev/nutting/pocketllm/data/local/model/ModelRegistryEntry.kt
- [ ] T008 [P] Create ModelRegistry object with hardcoded SmolVLM2/Qwen3-VL/Gemma3 entries in app/src/main/java/dev/nutting/pocketllm/data/local/model/ModelRegistry.kt — port URLs and sizes from snacktrack ModelRegistry.kt
- [ ] T009 [P] Create LocalModelStore class in app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalModelStore.kt — DataStore Preferences persistence with JSON serialization of List<LocalModel>, methods: models Flow, save, delete, getById, updateStatus
- [ ] T010 Add new SettingsDataStore keys in app/src/main/java/dev/nutting/pocketllm/data/preferences/SettingsDataStore.kt — add active_local_model_id, gpu_offload_percent (default 0), inference_provider_type (default "remote")
- [ ] T011 Add isLocalInference column to MessageEntity in app/src/main/java/dev/nutting/pocketllm/data/local/entity/MessageEntity.kt — Boolean, default false
- [ ] T012 Add Room migration 6→7 in app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt — ALTER TABLE messages ADD COLUMN isLocalInference INTEGER NOT NULL DEFAULT 0, update version and entity list
- [ ] T013 Port LlmEngine.kt from snacktrack to app/src/main/java/dev/nutting/pocketllm/llm/LlmEngine.kt — rename package to dev.nutting.pocketllm.llm, rename System.loadLibrary to pocketllm-llm, keep State sealed class/InferenceProgress/native methods/progress SharedFlow/crash recovery, add contextSize parameter to loadModel()
- [ ] T014 Create InferenceProvider interface in app/src/main/java/dev/nutting/pocketllm/domain/InferenceProvider.kt — per contracts/inference-provider.md: streamChatCompletion(request): Flow<ChatCompletionChunk>, chatCompletion(request): ChatCompletionResponse, fetchModels(): List<ModelInfo>, cancel()
- [ ] T015 Create RemoteInferenceProvider in app/src/main/java/dev/nutting/pocketllm/domain/RemoteInferenceProvider.kt — wraps existing OpenAiApiClient, delegates all methods directly
- [ ] T016 Create LocalInferenceProvider in app/src/main/java/dev/nutting/pocketllm/domain/LocalInferenceProvider.kt — wraps LlmEngine, converts token-by-token JNI progress into Flow<ChatCompletionChunk> (synthetic chunks matching OpenAI format per contracts/inference-provider.md), implements ensureModelLoaded(), handles vision vs text inference based on message content
- [ ] T017 Refactor ChatManager in app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt — replace direct OpenAiApiClient.streamChatCompletion() calls with InferenceProvider.streamChatCompletion(), accept provider as parameter to sendMessage(), set isLocalInference on saved MessageEntity based on provider type
- [ ] T018 Update AppContainer in app/src/main/java/dev/nutting/pocketllm/AppContainer.kt — wire LocalModelStore, LlmEngine, LocalInferenceProvider, RemoteInferenceProvider; expose provider factory method
- [ ] T019 Write LocalModelStore tests in app/src/test/java/dev/nutting/pocketllm/data/local/model/LocalModelStoreTest.kt — test save/load/delete/updateStatus round-trip, test JSON serialization of DownloadStatus enum, test empty initial state

**Checkpoint**: Foundation ready — InferenceProvider abstraction in place, data layer complete, existing remote chat still works via RemoteInferenceProvider

---

## Phase 3: User Story 1 - Run a Model Locally on Device (Priority: P1) MVP

**Goal**: Users can download a registry model, select it, and chat on-device with streamed token-by-token responses

**Independent Test**: Download a registry model, select it, send a message, verify streamed response appears without any server connection

### Implementation for User Story 1

- [ ] T020 [US1] Create ModelManagementUiState data class in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementUiState.kt — registryModels, downloadedModels, activeModelId, activeDownloads map, errorMessage, device RAM info
- [ ] T021 [US1] Create ModelDownloadWorker in app/src/main/java/dev/nutting/pocketllm/util/ModelDownloadWorker.kt — port from snacktrack: OkHttp streaming download, GGUF magic validation (FR-203), foreground notification with progress (FR-208), download speed calculation, retry with exponential backoff (FR-206, max 3), storage check before download (FR-204)
- [ ] T022 [US1] Create ModelManagementViewModel in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementViewModel.kt — downloadModel() triggers WorkManager (FR-201), observe download progress via WorkInfo, selectModel() sets active model + provider type to local (FR-240), expose registry + downloaded model lists, check device RAM (FR-214)
- [ ] T023 [US1] Create ModelManagementScreen in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementScreen.kt — M3 card list of registry models with download buttons (FR-200), download progress indicators with percentage/speed, downloaded models with select/active indicator, device RAM display with warnings, loading/empty/error states, contentDescription on all interactive elements (FR-090/SC-107), 48dp touch targets
- [ ] T024 [US1] Add ModelManagement route to app/src/main/java/dev/nutting/pocketllm/ui/navigation/Routes.kt and wire destination in app/src/main/java/dev/nutting/pocketllm/ui/navigation/AppNavGraph.kt with slide transition
- [ ] T025 [US1] Add "Local Models" entry to SettingsScreen in app/src/main/java/dev/nutting/pocketllm/ui/settings/SettingsScreen.kt — navigates to ModelManagement route (FR-244)
- [ ] T026 [US1] Update ChatViewModel in app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt — read inference_provider_type from SettingsDataStore, construct LocalInferenceProvider or RemoteInferenceProvider accordingly, pass provider to ChatManager.sendMessage(), init LlmEngine on app startup with backend discovery
- [ ] T027 [US1] Write LocalInferenceProvider tests in app/src/test/java/dev/nutting/pocketllm/domain/LocalInferenceProviderTest.kt — test synthetic chunk format matches ChatCompletionChunk structure, test finish_reason "stop" on completion, test cancel propagation

**Checkpoint**: User Story 1 complete — users can download a model, select it, and chat on-device. Core MVP delivered.

---

## Phase 4: User Story 2 - Manage Downloaded and Imported Models (Priority: P1)

**Goal**: Users can view downloaded models, delete them to free storage, and import custom GGUF files

**Independent Test**: Download a model, verify it appears in list, delete it, verify storage freed. Import a GGUF from device storage, verify it works for inference.

### Implementation for User Story 2

- [ ] T028 [US2] Add delete model functionality to ModelManagementViewModel in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementViewModel.kt — deleteModel() removes files from models/ directory (FR-213), updates LocalModelStore, resets active model if deleted was active, reset to remote provider if no local models remain
- [ ] T029 [US2] Add import model functionality to ModelManagementViewModel — importModel() accepts model URI + optional projector URI, copies to models/ directory, validates GGUF magic (FR-212), creates LocalModel with isImported=true, two-step picker flow (FR-211)
- [ ] T030 [US2] Update ModelManagementScreen with delete and import UI in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementScreen.kt — delete button on each downloaded model card, import FAB/button with file picker, GGUF validation error snackbar, confirmation dialog before delete, imported models section

**Checkpoint**: User Story 2 complete — full model lifecycle management (download + delete + import)

---

## Phase 5: User Story 3 - Switch Between Local and Remote Inference (Priority: P1)

**Goal**: Users can seamlessly switch between local and remote models mid-conversation

**Independent Test**: Select local model, send message (on-device), switch to remote server, send another message (server), verify both in same conversation with correct indicators.

### Implementation for User Story 3

- [ ] T031 [US3] Update model selector in ChatViewModel in app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt — merge local downloaded models into availableModels list alongside remote server models (FR-240), distinguish local vs remote in model list, update inference_provider_type on switch (FR-241), persist selection
- [ ] T032 [US3] Update ChatScreen model selector UI in app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatScreen.kt — display local models with "Local" badge/label in model picker dropdown, show remote models with server name, visual distinction between local and remote
- [ ] T033 [US3] Add local inference indicator to MessageBubble in app/src/main/java/dev/nutting/pocketllm/ui/chat/MessageBubble.kt — display "Local" chip/label on messages where isLocalInference=true (FR-242), show model name for both local and remote messages

**Checkpoint**: User Story 3 complete — seamless local/remote switching within conversations

---

## Phase 6: User Story 4 - Resume Interrupted Downloads (Priority: P2)

**Goal**: Downloads can resume after interruption without re-downloading from scratch

**Independent Test**: Start download, kill app, reopen, verify download resumes from interrupted point.

### Implementation for User Story 4

- [ ] T034 [US4] Add HTTP Range header resume support to ModelDownloadWorker in app/src/main/java/dev/nutting/pocketllm/util/ModelDownloadWorker.kt — detect partial file on disk, send Range header for resume (FR-202), handle 206 Partial Content response, validate final file after resume completes
- [ ] T035 [US4] Add cellular connection detection and warning to ModelManagementViewModel in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementViewModel.kt — check ConnectivityManager for cellular, show warning dialog before download (FR-205), confirmCellularDownload() to proceed
- [ ] T036 [US4] Update ModelManagementScreen resume UI in app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementScreen.kt — show "Resume" button for partially downloaded models (FR-202), show downloaded/total progress for interrupted downloads, cancel button retains partial file (FR-207), cellular warning dialog

**Checkpoint**: User Story 4 complete — reliable download experience for large model files

---

## Phase 7: User Story 5 - Configure GPU Acceleration (Priority: P2)

**Goal**: Users can control GPU layer offloading for faster inference on supported devices

**Independent Test**: Adjust GPU offload slider, run inference, observe performance metric changes.

### Implementation for User Story 5

- [ ] T037 [US5] Add GPU offload and context window settings to SettingsScreen in app/src/main/java/dev/nutting/pocketllm/ui/settings/SettingsScreen.kt — GPU offload slider 0-100% under "Local Models" section (FR-227), context window size setting per model (FR-231), device info display from LlmEngine.nativeDeviceInfo(), hide/disable GPU slider if Vulkan unavailable
- [ ] T038 [US5] Add GPU offload change detection to LocalInferenceProvider in app/src/main/java/dev/nutting/pocketllm/domain/LocalInferenceProvider.kt — detect when gpu_offload_percent changes, trigger model reload on next inference (FR-228), detect when contextWindowSize changes and reload
- [ ] T039 [US5] Add inference performance metrics display to ChatScreen in app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatScreen.kt — show tokens/sec and inference phase during local generation (FR-224), display as subtle indicator below streaming message, hide for remote inference

**Checkpoint**: User Story 5 complete — GPU acceleration with user control

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Edge cases, error handling, and quality improvements across all stories

- [ ] T040 Handle edge case: insufficient storage before download in ModelManagementViewModel — check available storage with 100MB buffer (FR-204), show error if insufficient
- [ ] T041 Handle edge case: insufficient RAM warning in ModelManagementScreen — compare device RAM to model minimumRamMb (FR-214), show warning but allow proceed
- [ ] T042 Handle edge case: model switch during inference in ChatViewModel — cancel in-progress inference before switching models, preserve partial response
- [ ] T043 Handle edge case: send message before model loaded in ChatViewModel — show loading indicator, block send until LlmEngine.state is Ready
- [ ] T044 Handle edge case: native crash recovery in LlmEngine — verify signal handlers recover from SIGSEGV/SIGBUS (FR-229), log at ERROR level, show user-facing error, allow retry
- [ ] T045 Handle edge case: corrupt model file detection — validate GGUF magic on model load, offer re-download if invalid
- [ ] T046 Run ./gradlew assembleDebug, ./gradlew test, ./gradlew lintDebug — verify all pass with zero errors
- [ ] T047 Verify ModelManagementScreen with TalkBack enabled — all interactive elements announced, logical reading order, no unlabeled elements (SC-107)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 (native build must compile)
- **User Story 1 (Phase 3)**: Depends on Phase 2 — delivers MVP
- **User Story 2 (Phase 4)**: Depends on Phase 2 — can run in parallel with US1 (different files)
- **User Story 3 (Phase 5)**: Depends on Phase 2 + US1 T026 (ChatViewModel provider selection must exist)
- **User Story 4 (Phase 6)**: Depends on US1 T021 (ModelDownloadWorker must exist to add resume)
- **User Story 5 (Phase 7)**: Depends on Phase 2 + US1 T026 (settings + inference provider must exist)
- **Polish (Phase 8)**: Depends on all user stories being complete

### User Story Dependencies

```
Phase 1: Setup
    │
    ▼
Phase 2: Foundational ──────────────────────┐
    │                                        │
    ▼                                        ▼
Phase 3: US1 (MVP)         Phase 4: US2 (parallel)
    │
    ├──────────────┐
    ▼              ▼
Phase 5: US3    Phase 7: US5
    │
    ▼
Phase 6: US4 (extends US1 download)
    │
    ▼
Phase 8: Polish
```

### Within Each User Story

- Data models before ViewModels
- ViewModels before Screens
- Navigation wiring after Screen exists
- Tests after implementation (for this feature, since we're porting known-working code)

### Parallel Opportunities

- T006/T007/T008/T009: All data classes, different files — full parallel
- T011/T012: MessageEntity + migration can parallel with T006-T009
- T014/T015/T016: InferenceProvider + implementations after data classes
- US1 and US2 can proceed in parallel (different files, different concerns)
- US5 can proceed in parallel with US3 (different UI areas)

---

## Parallel Example: Phase 2 Foundational

```
# Parallel batch 1 (data classes, all different files):
T006: LocalModel.kt
T007: ModelRegistryEntry.kt
T008: ModelRegistry.kt
T009: LocalModelStore.kt
T010: SettingsDataStore.kt (new keys)
T011: MessageEntity.kt (new column)

# Sequential after batch 1:
T012: Room migration (depends on T011)
T013: LlmEngine.kt (depends on T004 native build)

# Parallel batch 2 (InferenceProvider + implementations):
T014: InferenceProvider.kt
T015: RemoteInferenceProvider.kt (after T014)
T016: LocalInferenceProvider.kt (after T013 + T014)

# Sequential:
T017: ChatManager refactor (after T014-T016)
T018: AppContainer wiring (after all above)
T019: Tests (after T009)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (native build)
2. Complete Phase 2: Foundational (data + InferenceProvider)
3. Complete Phase 3: User Story 1 (download model + chat locally)
4. **STOP and VALIDATE**: Download a model, select it, send a message, verify streamed local response
5. Deploy/demo if ready — this is the core value proposition

### Incremental Delivery

1. Setup + Foundational → Native build compiles, InferenceProvider abstraction works
2. Add US1 → Download + local chat → Test independently → **MVP!**
3. Add US2 → Delete + import → Test independently
4. Add US3 → Local/remote switching → Test independently
5. Add US4 → Download resume → Test independently
6. Add US5 → GPU acceleration → Test independently
7. Polish → Edge cases, TalkBack, final QA

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Native build (T002-T003) is the most complex task — porting llm_jni.cpp requires removing grammar constraints and adding contextSize parameter
- T016 (LocalInferenceProvider) is the key integration task — converts JNI token callbacks to Flow<ChatCompletionChunk>
- T017 (ChatManager refactor) is the riskiest change — modifying the core chat flow. Existing remote tests must still pass.
- All edge case tasks (T040-T045) can be parallelized as they touch different code paths
