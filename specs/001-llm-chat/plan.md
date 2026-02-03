# Implementation Plan: LLM Chat Client

**Branch**: `001-llm-chat` | **Date**: 2026-02-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-llm-chat/spec.md`

## Summary

Build an Android LLM chat client (Pocket LLM) targeting self-hosted and local LLMs via the OpenAI-compatible API. The app supports server configuration, model listing, streaming chat with markdown rendering, conversation management with tree-based message branching, context compaction, tool calling, multimodal input, and reasoning display. Built as a single-module Jetpack Compose + Material 3 app using MVVM, Room for persistence, Ktor for HTTP/SSE streaming, and DataStore + Tink for encrypted credential storage.

## Technical Context

**Language/Version**: Kotlin (managed by Kotlin Gradle Plugin 2.2.10), JDK 21
**Primary Dependencies**: Jetpack Compose (BOM 2026.01.00), Material 3, Room 2.8.4, Ktor Client 3.4.0, Navigation Compose 2.9.7, kotlinx-serialization-json 1.8.0, mikepenz markdown-renderer 0.39.2, Tink 1.13.0, DataStore 1.1.7
**Storage**: Room (SQLite) for conversations/messages, DataStore Preferences for settings, encrypted DataStore (Tink) for API keys
**Testing**: JUnit 4 + Robolectric for local JVM tests, Ktor MockEngine for HTTP tests, Room in-memory database for DAO tests
**Target Platform**: Android (compileSdk 36, minSdk 28, targetSdk 36)
**Project Type**: Single-module Android app (`app/`)
**Performance Goals**: First token displayed <1s after server begins responding (SC-002), app launch to usable state <2s (SC-010), 100+ conversations without list performance degradation (SC-003)
**Constraints**: All data stored locally on device, no cloud sync. Offline-capable for viewing existing conversations. API keys never stored in plaintext.
**Scale/Scope**: Single user, multiple servers, unlimited conversations. ~10 screens (chat, conversation list, server config, settings, search, export).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVVM-Compose Single Module | PASS | Single `app/` module, Compose + M3, UiState per ViewModel, no framework class refs in VMs |
| II. Simplicity & No Premature Abstraction | PASS | Manual DI via AppContainer, repository only where multiple consumers exist (see Complexity Tracking) |
| III. Test Coverage - Value Over Volume | PASS | Tests target DAO queries, streaming parser, ChatManager logic, ViewModel state. No trivial getter tests. |
| IV. Conventional Commits & Semantic Release | PASS | All commits follow `type(scope): description` format |
| V. Accessibility-First | PASS | contentDescription on all non-text elements, 48dp touch targets, dynamic font scaling, TalkBack verification per story (FR-090 through FR-093) |
| VI. BDD Spec-Driven Development | PASS | 16 user stories with Given/When/Then scenarios drive implementation and test naming |
| VII. Polished Material Design UX | PASS | M3 components (TopAppBar, NavigationDrawer, BottomSheet, FAB, Snackbar), adaptive navigation, edge-to-edge, Material motion, loading/empty/error states |

**Post-Phase 1 Re-check**: All principles still pass. Repository pattern used for ServerRepository, ConversationRepository, MessageRepository, and SettingsRepository. ServerRepository and ConversationRepository each serve multiple consumers (ViewModels + ChatManager). MessageRepository serves ChatViewModel and search. SettingsRepository serves multiple settings consumers. Justified in Complexity Tracking below.

## Project Structure

### Documentation (this feature)

```text
specs/001-llm-chat/
├── plan.md              # This file
├── research.md          # Phase 0: technology decisions
├── data-model.md        # Phase 1: Room entities and schema
├── quickstart.md        # Phase 1: setup guide and package structure
├── contracts/           # Phase 1: OpenAI API contracts
│   └── openai-compatible-api.md
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
app/src/main/java/dev/nutting/pocketllm/
├── PocketLlmApplication.kt
├── AppContainer.kt
├── MainActivity.kt
├── data/
│   ├── local/
│   │   ├── PocketLlmDatabase.kt
│   │   ├── dao/
│   │   │   ├── ServerProfileDao.kt
│   │   │   ├── ConversationDao.kt
│   │   │   ├── MessageDao.kt
│   │   │   ├── CompactionSummaryDao.kt
│   │   │   ├── ParameterPresetDao.kt
│   │   │   └── ToolDefinitionDao.kt
│   │   └── entity/
│   │       ├── ServerProfileEntity.kt
│   │       ├── ConversationEntity.kt
│   │       ├── MessageEntity.kt
│   │       ├── CompactionSummaryEntity.kt
│   │       ├── ParameterPresetEntity.kt
│   │       ├── ToolDefinitionEntity.kt
│   │       └── ConversationToolEnabledEntity.kt
│   ├── remote/
│   │   ├── OpenAiApiClient.kt
│   │   └── model/
│   │       ├── ChatCompletionRequest.kt
│   │       ├── ChatCompletionResponse.kt
│   │       ├── ChatCompletionChunk.kt
│   │       ├── ModelsResponse.kt
│   │       └── ToolCallModels.kt
│   ├── preferences/
│   │   ├── SettingsDataStore.kt
│   │   └── EncryptedDataStore.kt
│   └── repository/
│       ├── ServerRepository.kt
│       ├── ConversationRepository.kt
│       ├── MessageRepository.kt
│       └── SettingsRepository.kt
├── domain/
│   ├── ChatManager.kt
│   └── tool/
│       ├── ToolExecutor.kt
│       ├── CalculatorTool.kt
│       └── WebFetchTool.kt
├── ui/
│   ├── navigation/
│   │   ├── Routes.kt
│   │   └── AppNavGraph.kt
│   ├── chat/
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── ChatUiState.kt
│   │   ├── MessageBubble.kt
│   │   ├── MessageInput.kt
│   │   ├── ThinkingSection.kt
│   │   ├── ToolCallCard.kt
│   │   └── BranchNavigator.kt
│   ├── conversations/
│   │   ├── ConversationListScreen.kt
│   │   ├── ConversationListViewModel.kt
│   │   └── ConversationListUiState.kt
│   ├── server/
│   │   ├── ServerConfigScreen.kt
│   │   ├── ServerConfigViewModel.kt
│   │   └── ServerConfigUiState.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   ├── SettingsViewModel.kt
│   │   └── SettingsUiState.kt
│   └── theme/
│       └── Theme.kt
└── util/
    ├── TokenCounter.kt
    └── ImageCompressor.kt

app/src/test/java/dev/nutting/pocketllm/
├── data/
│   ├── local/dao/
│   └── remote/
├── domain/
│   ├── ChatManagerTest.kt
│   └── tool/
└── ui/
    ├── chat/ChatViewModelTest.kt
    └── conversations/ConversationListViewModelTest.kt
```

**Structure Decision**: Single-module Android app per constitution principle I. All feature separation via packages (`data`, `domain`, `ui`, `util`). No multi-module, no DI framework. Manual construction via `AppContainer` singleton.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Repository layer (ServerRepository, ConversationRepository, MessageRepository, SettingsRepository) | Each repository serves 2+ consumers: ViewModels + ChatManager + search. Repositories encapsulate DAO + DataStore + remote API coordination. | Direct DAO access in ViewModels would duplicate query logic across ChatViewModel, ConversationListViewModel, and ChatManager. Constitution II explicitly permits repositories "when they serve more than one consumer or isolate a testable boundary." |
