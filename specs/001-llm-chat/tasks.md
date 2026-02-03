# Tasks: LLM Chat Client

**Input**: Design documents from `/specs/001-llm-chat/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openai-compatible-api.md, quickstart.md

**Tests**: Included where they protect critical logic (BDD-style per constitution VI, value-over-volume per constitution III). Not included for trivial UI wiring or pure layout code.

**Organization**: Tasks grouped by user story. Accessibility (US15) is a cross-cutting concern applied within each story per constitution V, not a separate phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Paths relative to repository root

---

## Phase 1: Setup

**Purpose**: Rename template, add Gradle plugins and dependencies, verify build

- [X] T001 Rename package from `dev.nutting.template` to `dev.nutting.pocketllm` across all source files, `app/build.gradle.kts` (namespace, applicationId), `AndroidManifest.xml`, and resource references
- [X] T002 Add KSP and kotlinx-serialization plugins to root `build.gradle.kts` and `app/build.gradle.kts` per `specs/001-llm-chat/quickstart.md`
- [X] T003 Add all dependencies (Room, Ktor, DataStore, Tink, Navigation, markdown-renderer, kotlinx-serialization, test deps) to `app/build.gradle.kts` per `specs/001-llm-chat/quickstart.md`
- [X] T004 Verify build compiles: `./gradlew assembleDebug && ./gradlew test && ./gradlew lintDebug` must all pass with zero errors

**Checkpoint**: Project compiles with all new dependencies. No functional changes yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can begin

**CRITICAL**: No user story work can begin until this phase is complete

### Data Layer

- [X] T005 [P] Create `ServerProfileEntity.kt` Room entity in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` per `specs/001-llm-chat/data-model.md` ServerProfile schema
- [X] T006 [P] Create `ConversationEntity.kt` Room entity in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` per `specs/001-llm-chat/data-model.md` Conversation schema (include `activeLeafMessageId`, nullable parameter overrides, `lastServerProfileId` FK with SET NULL)
- [X] T007 [P] Create `MessageEntity.kt` Room entity in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` per `specs/001-llm-chat/data-model.md` Message schema (self-referencing `parentMessageId` FK, `depth`, `childCount`, indices on `conversationId` and `parentMessageId`)
- [X] T008 [P] Create `ServerProfileDao.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/dao/` with CRUD operations, `getAll(): Flow<List<ServerProfileEntity>>`, `getById(id: String): Flow<ServerProfileEntity?>`
- [X] T009 [P] Create `ConversationDao.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/dao/` with CRUD, `getAllByUpdatedAt(): Flow<List<ConversationEntity>>`, `updateTitle()`, `updateActiveLeaf()`, `updateParameters()`
- [X] T010 [P] Create `MessageDao.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/dao/` with insert, delete, `getChildMessages(parentId)`, `getActiveBranch(leafId)` using recursive CTE, `getConversationRootMessages(conversationId)`, `updateChildCount()`, `Flow` return types
- [X] T011 Create `PocketLlmDatabase.kt` Room database definition in `app/src/main/java/dev/nutting/pocketllm/data/local/` with entities `ServerProfileEntity`, `ConversationEntity`, `MessageEntity` and DAOs. Version 1.
- [X] T012 [P] Create API request/response models in `app/src/main/java/dev/nutting/pocketllm/data/remote/model/`: `ChatCompletionRequest.kt`, `ChatCompletionResponse.kt`, `ChatCompletionChunk.kt`, `ModelsResponse.kt` per `specs/001-llm-chat/contracts/openai-compatible-api.md` sections 1-3
- [X] T013 [P] Create `ApiErrorResponse.kt` in `app/src/main/java/dev/nutting/pocketllm/data/remote/model/` per contracts error response format
- [X] T014 Create `OpenAiApiClient.kt` in `app/src/main/java/dev/nutting/pocketllm/data/remote/` — Ktor `HttpClient` with OkHttp engine, SSE plugin, content negotiation (kotlinx-serialization with `ignoreUnknownKeys = true`). Methods: `fetchModels(baseUrl, apiKey?, timeout): List<ModelInfo>`, `streamChatCompletion(baseUrl, apiKey?, timeout, request): Flow<ChatCompletionChunk>` using `serverSentEventsSession`, handle `[DONE]` termination, cancellation via structured concurrency
- [X] T015 [P] Create `EncryptedDataStore.kt` in `app/src/main/java/dev/nutting/pocketllm/data/preferences/` — Tink `StreamingAead` with Android Keystore master key, custom `Serializer<Preferences>` for encrypted DataStore. Methods: `saveApiKey(serverId, key)`, `getApiKey(serverId): Flow<String?>`, `deleteApiKey(serverId)`
- [X] T016 [P] Create `SettingsDataStore.kt` in `app/src/main/java/dev/nutting/pocketllm/data/preferences/` — Preferences DataStore with typed keys per `specs/001-llm-chat/data-model.md` Preferences DataStore section (`theme_mode`, `default_temperature`, `default_max_tokens`, `last_active_server_id`, etc.)

### Repositories

- [X] T017 [P] Create `ServerRepository.kt` in `app/src/main/java/dev/nutting/pocketllm/data/repository/` — wraps `ServerProfileDao` + `EncryptedDataStore` + `OpenAiApiClient`. Methods: CRUD for server profiles, `fetchModels(serverId)`, `validateConnection(serverId)`, `saveApiKey()`, `getApiKey()`
- [X] T018 [P] Create `ConversationRepository.kt` in `app/src/main/java/dev/nutting/pocketllm/data/repository/` — wraps `ConversationDao`. Methods: create, rename, delete, `getAllSorted(): Flow`, `getById()`, `updateParameters()`, `updateActiveLeaf()`
- [X] T019 [P] Create `MessageRepository.kt` in `app/src/main/java/dev/nutting/pocketllm/data/repository/` — wraps `MessageDao`. Methods: `insertMessage()`, `getActiveBranch(leafId): Flow`, `getChildren(parentId)`, `deleteMessage()`, `getConversationRootMessages()`
- [X] T020 [P] Create `SettingsRepository.kt` in `app/src/main/java/dev/nutting/pocketllm/data/repository/` — wraps `SettingsDataStore`. Flow-based getters and suspend setters for all preference keys

### Application Skeleton

- [X] T021 Create `PocketLlmApplication.kt` in `app/src/main/java/dev/nutting/pocketllm/` — `Application` subclass, initialize Tink in `onCreate()`, create `AppContainer` singleton
- [X] T022 Create `AppContainer.kt` in `app/src/main/java/dev/nutting/pocketllm/` — manual DI container holding `PocketLlmDatabase`, `OpenAiApiClient`, all repositories, `SettingsDataStore`, `EncryptedDataStore`. Lazy initialization.
- [X] T023 Register `PocketLlmApplication` in `app/src/main/AndroidManifest.xml` (`android:name`)
- [X] T024 [P] Create `Routes.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/navigation/` — `@Serializable` route definitions: `ServerConfigRoute`, `ChatRoute(conversationId: String?)`, `ConversationListRoute`, `SettingsRoute`
- [X] T025 Create `AppNavGraph.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/navigation/` — `NavHost` with composable destinations for each route, start destination logic (if no servers → `ServerConfigRoute`, else → `ChatRoute`)
- [X] T026 Update `Theme.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/theme/` — rename from TemplateTheme to PocketLlmTheme, keep dynamic color and light/dark support, wire to `SettingsRepository.themeMode` Flow
- [X] T027 Update `MainActivity.kt` — replace scaffold content with `AppNavGraph`, get `AppContainer` from `Application`, pass to NavGraph

### Tests

- [X] T028 [P] Create `MessageDaoTest.kt` in `app/src/test/java/dev/nutting/pocketllm/data/local/dao/` — Robolectric + Room in-memory DB tests: `given root message inserted when getActiveBranch called then returns single message`, `given branching messages when getChildMessages called then returns correct children`, `given deep tree when getActiveBranch with leaf then returns full path to root`
- [X] T029 [P] Create `OpenAiApiClientTest.kt` in `app/src/test/java/dev/nutting/pocketllm/data/remote/` — Ktor MockEngine tests: `given valid server when fetchModels called then returns model list`, `given streaming response when streamChatCompletion collected then emits chunks in order`, `given DONE event when streaming then flow completes`, `given server error when fetching then throws with error message`

**Checkpoint**: Foundation ready — database, HTTP client, encrypted storage, navigation, and DI container all functional. `./gradlew assembleDebug` and `./gradlew test` pass.

---

## Phase 3: User Story 1 — Connect to LLM Server and Start Chatting (Priority: P1) MVP

**Goal**: User configures a server, selects a model, sends a message, and sees a streamed response.

**Independent Test**: Configure server URL → select model → type message → verify streamed response appears token-by-token.

**Acceptance Scenarios**: US1.1 (first launch redirect), US1.2 (fetch models), US1.3 (streaming chat), US1.4 (error on invalid URL), US1.5 (API key auth), US1.6 (switch server/model mid-conversation)

### Tests

- [X] T030 [P] [US1] Create `ChatManagerTest.kt` in `app/src/test/java/dev/nutting/pocketllm/domain/` — `given server configured when sendMessage called then streams response chunks to state`, `given streaming in progress when stopGeneration called then flow is canceled and partial response preserved`, `given server error when sendMessage called then error state emitted`, `given mid-conversation server switch when sendMessage then new server used and history preserved`

### Implementation

- [X] T031 [P] [US1] Create `ServerConfigUiState.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/server/` — data class with `servers: List<ServerProfile>`, `editingServer: ServerProfile?`, `models: List<ModelInfo>`, `selectedModelId: String?`, `isLoading: Boolean`, `error: String?`, `isFirstLaunch: Boolean`
- [X] T032 [P] [US1] Create `ChatUiState.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — data class with `messages: List<Message>`, `currentStreamingContent: String`, `isStreaming: Boolean`, `selectedServer: ServerProfile?`, `selectedModelId: String?`, `availableModels: List<ModelInfo>`, `error: String?`, `conversationId: String?`, `conversationTitle: String`
- [X] T033 [US1] Create `ChatManager.kt` in `app/src/main/java/dev/nutting/pocketllm/domain/` — orchestrates sending messages: build message list from active branch, include system prompt, call `OpenAiApiClient.streamChatCompletion()`, collect chunks into accumulated content, save completed message to Room, handle errors and cancellation. Methods: `sendMessage(conversationId, content, serverId, modelId): Flow<StreamState>`, `stopGeneration()`
- [X] T034 [US1] Create `ServerConfigViewModel.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/server/` — exposes `ServerConfigUiState` via `StateFlow`, methods: `addServer(name, url, apiKey?)`, `editServer()`, `deleteServer()`, `validateAndFetchModels(serverId)`, `selectModel(modelId)`. First-launch detection via `SettingsRepository.lastActiveServerId`
- [X] T035 [US1] Create `ServerConfigScreen.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/server/` — M3 Compose UI: welcome message on first launch (US1.1), server name/URL/API key form with validation, model list dropdown, test connection button, save/cancel actions. `contentDescription` on all interactive elements, 48dp touch targets (FR-090, FR-091)
- [X] T036 [US1] Create `ChatViewModel.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — exposes `ChatUiState` via `StateFlow`, methods: `sendMessage(content)`, `stopGeneration()`, `switchServer(serverId)`, `switchModel(modelId)`, `loadConversation(conversationId?)`. Creates new conversation on first message, auto-generates title by truncating first user message (FR-022)
- [X] T037 [US1] Create `MessageInput.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — M3 text field with send button, stop button (when streaming), disabled state during streaming. `contentDescription` for all buttons
- [X] T038 [US1] Create `MessageBubble.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — displays user/assistant messages with role indicator, timestamp, plain text content (markdown rendering added in US6). Different styling per role. Accessibility: full message announced as single unit by TalkBack
- [X] T039 [US1] Create `ChatScreen.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — M3 Scaffold with TopAppBar (conversation title, server/model selector), LazyColumn of `MessageBubble` items, `MessageInput` at bottom, streaming indicator (FR-011), auto-scroll on new content, server/model picker in TopAppBar for mid-conversation switching (FR-014a, US1.6). Edge-to-edge layout with correct inset handling
- [X] T040 [US1] Wire `ServerConfigScreen` and `ChatScreen` into `AppNavGraph.kt` — first-launch redirect logic: if no servers configured navigate to `ServerConfigRoute`, else navigate to `ChatRoute`. Back navigation from server config to chat after saving.

**Checkpoint**: MVP complete. User can configure a server, select a model, send a message, and see a streamed plain-text response. `./gradlew assembleDebug` and `./gradlew test` pass.

---

## Phase 4: User Story 2 — Manage Conversations (Priority: P1)

**Goal**: User can create, switch, rename, delete conversations. Conversations persist across app restarts.

**Independent Test**: Create multiple conversations → switch between them → rename one → delete another → close and reopen app → verify persistence.

**Acceptance Scenarios**: US2.1 (new chat), US2.2 (conversation list), US2.3 (rename), US2.4 (delete), US2.5 (persistence)

### Tests

- [ ] T041 [P] [US2] Create `ConversationListViewModelTest.kt` in `app/src/test/java/dev/nutting/pocketllm/ui/conversations/` — `given conversations exist when viewModel initialized then sorted by updatedAt desc`, `given conversation when renamed then title updates in list`, `given conversation when deleted then removed from list`

### Implementation

- [ ] T042 [P] [US2] Create `ConversationListUiState.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/conversations/` — data class with `conversations: List<ConversationSummary>` (id, title, lastMessage preview, updatedAt), `isLoading: Boolean`
- [ ] T043 [US2] Create `ConversationListViewModel.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/conversations/` — exposes `ConversationListUiState` via `StateFlow`, methods: `createNewConversation()`, `renameConversation(id, newTitle)`, `deleteConversation(id)`, `selectConversation(id)`
- [ ] T044 [US2] Create `ConversationListScreen.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/conversations/` — M3 NavigationDrawer or ModalDrawerSheet content: LazyColumn of conversations with title and last-message preview, sorted by most recent (FR-023). Swipe-to-delete or long-press menu for rename/delete. "New Chat" FAB or button. Empty state when no conversations. `contentDescription` on all interactive elements
- [ ] T045 [US2] Integrate conversation drawer into `ChatScreen.kt` — add drawer toggle in TopAppBar, `ModalNavigationDrawer` wrapping the chat scaffold, navigate to selected conversation, "New Chat" creates empty conversation and navigates to it
- [ ] T046 [US2] Update `ChatViewModel.kt` to auto-generate LLM title after first response completes (FR-022) — send title-generation request to LLM with first user+assistant exchange, update conversation title asynchronously
- [ ] T047 [US2] Wire `ConversationListRoute` into `AppNavGraph.kt`, update `SettingsRepository` to persist `last_active_conversation_id`

**Checkpoint**: Users can manage multiple conversations. Persistence verified across app restarts.

---

## Phase 5: User Story 3 — Configure Multiple Servers (Priority: P2)

**Goal**: User manages multiple server profiles and switches between them.

**Independent Test**: Add two servers → switch between them → verify each shows its own models.

**Acceptance Scenarios**: US3.1 (add profile), US3.2 (switch server), US3.3 (edit profile), US3.4 (delete profile), US3.5 (orphaned conversations accessible)

### Implementation

- [ ] T048 [US3] Update `ServerConfigScreen.kt` to show server list with add/edit/delete — M3 list with server name, URL preview, connection status indicator. Tap to edit, swipe to delete. Add button for new server.
- [ ] T049 [US3] Update `ServerConfigViewModel.kt` to manage multiple server profiles — load all servers, handle add/edit/delete, validate that deleting a server does not affect existing conversations (FK SET NULL)
- [ ] T050 [US3] Update `ChatScreen.kt` TopAppBar server selector to show all configured servers as a dropdown, switching updates model list and saves `lastServerProfileId` on conversation

**Checkpoint**: Multi-server management works. Orphaned conversations remain accessible after server deletion.

---

## Phase 6: User Story 4 — System Prompts and Chat Parameters (Priority: P2)

**Goal**: User customizes system prompt and generation parameters globally and per-conversation.

**Independent Test**: Set system prompt and temperature → send message → verify parameters are sent in API request.

**Acceptance Scenarios**: US4.1 (system prompt), US4.2 (inherit global defaults), US4.3 (adjust params), US4.4 (indicate overrides)

### Implementation

- [ ] T051 [P] [US4] Create conversation settings bottom sheet composable in `app/src/main/java/dev/nutting/pocketllm/ui/chat/ConversationSettingsSheet.kt` — system prompt text field, temperature/maxTokens/topP/frequencyPenalty/presencePenalty sliders with current values shown, "Reset to defaults" button, visual indicator for overridden values (FR-014)
- [ ] T052 [US4] Update `ChatViewModel.kt` to apply per-conversation parameters when building `ChatCompletionRequest` — merge conversation overrides with global defaults from `SettingsRepository`, include system prompt as first message
- [ ] T053 [US4] Add global defaults section to `SettingsScreen.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/settings/` — default system prompt, default temperature/maxTokens/topP/frequencyPenalty/presencePenalty with sliders, persisted via `SettingsRepository`
- [ ] T054 [US4] Create `SettingsViewModel.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/settings/` and `SettingsUiState.kt` — expose all settings as `StateFlow`, update methods for each setting
- [ ] T055 [US4] Create `SettingsScreen.kt` shell in `app/src/main/java/dev/nutting/pocketllm/ui/settings/` — M3 settings screen with sections for General (defaults from T053) and navigation to Server Config. Wire into `AppNavGraph.kt`

**Checkpoint**: System prompts and generation parameters work globally and per-conversation.

---

## Phase 7: User Story 12 — Token Usage Display (Priority: P2)

**Goal**: User sees token counts per response and a running context usage indicator.

**Independent Test**: Send message → verify prompt/completion/total tokens displayed → verify context usage bar updates.

**Acceptance Scenarios**: US12.1 (per-message tokens), US12.2 (context usage indicator), US12.3 (threshold warning colors)

### Implementation

- [ ] T056 [P] [US12] Create `TokenCounter.kt` in `app/src/main/java/dev/nutting/pocketllm/util/` — estimate token count from message list (simple heuristic: ~4 chars per token). Method: `estimateTokens(messages): Int`. Used for pre-send context tracking when server doesn't report usage.
- [ ] T057 [US12] Update `MessageBubble.kt` to display token counts (prompt, completion, total) from `Message.promptTokens`/`completionTokens`/`totalTokens` — small expandable footer below assistant messages
- [ ] T058 [US12] Update `ChatScreen.kt` to add context usage indicator — horizontal progress bar in TopAppBar showing `tokensUsed / contextWindowSize`, color transitions: green (<50%), amber (50-75%), red (>75% compaction threshold). Reads `compaction_threshold_pct` from settings
- [ ] T059 [US12] Update `ChatManager.kt` to extract `usage` from streaming response final chunk and save to `Message` entity `promptTokens`/`completionTokens`/`totalTokens`

**Checkpoint**: Token usage visible on every response. Context usage indicator warns as threshold approaches.

---

## Phase 8: User Story 6 — Markdown and Code Rendering (Priority: P2)

**Goal**: Assistant responses render as rich markdown with syntax-highlighted code blocks and copy buttons.

**Independent Test**: Prompt LLM for response with headings, bold, code block → verify each renders correctly with syntax highlighting and copy button.

**Acceptance Scenarios**: US6.1 (markdown elements), US6.2 (syntax highlighting), US6.3 (copy button), US6.4 (inline code)

### Implementation

- [ ] T060 [US6] Update `MessageBubble.kt` to replace plain `Text` with mikepenz `Markdown` composable for assistant messages — use `multiplatform-markdown-renderer-m3` with `retainState = true` for streaming, M3 `markdownColor()` theming, `markdownTypography()` from theme
- [ ] T061 [US6] Integrate `multiplatform-markdown-renderer-code` for syntax highlighting in code blocks — configure `CodeHighlighting` with language detection, set up code block styling with M3 surface colors
- [ ] T062 [US6] Add copy button to code blocks — use mikepenz built-in copy button (v0.38.0+), wire to `ClipboardManager`, show Snackbar on copy confirmation. `contentDescription = "Copy code"` for accessibility

**Checkpoint**: Markdown rendering works for all common elements. Code blocks have syntax highlighting and copy buttons.

---

## Phase 9: User Story 5 — Context Compaction (Priority: P2)

**Goal**: App manages context window limits via automatic and manual compaction using LLM-generated summaries.

**Independent Test**: Have a long conversation approaching context limit → verify compaction triggers automatically → continue chatting coherently.

**Acceptance Scenarios**: US5.1 (auto compaction at 75%), US5.2 (visual indicator), US5.3 (manual trigger), US5.4 (coherent continuation)

### Implementation

- [ ] T063 [P] [US5] Create `CompactionSummaryEntity.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` and `CompactionSummaryDao.kt` in `dao/` per data-model.md. Add entity to `PocketLlmDatabase.kt` (migration to version 2)
- [ ] T064 [US5] Implement compaction logic in `ChatManager.kt` — before sending, check estimated tokens vs 75% of context window. If threshold exceeded: select oldest messages, send compaction prompt to LLM ("Summarize the following conversation..."), save `CompactionSummaryEntity`, rebuild message list with summary replacing compacted messages
- [ ] T065 [US5] Add manual compaction trigger — menu item in `ChatScreen.kt` TopAppBar overflow menu, calls `ChatManager.compactConversation(conversationId)`
- [ ] T066 [US5] Add compaction visual indicator in `ChatScreen.kt` — special `CompactionBubble` composable inserted at compaction points in the message list, showing "Earlier messages summarized" with expandable summary text (FR-033)

**Checkpoint**: Compaction works automatically and manually. Long conversations continue coherently after compaction.

---

## Phase 10: User Story 8 — Message Actions and Editing (Priority: P3)

**Goal**: User can copy, regenerate, edit (branching), and delete messages. Branch navigation at branch points.

**Independent Test**: Long-press message → verify copy, regenerate, edit, delete. Edit creates new branch with navigation arrows.

**Acceptance Scenarios**: US8.1 (regenerate → new branch), US8.2 (edit → new branch), US8.3 (branch navigation), US8.4 (copy), US8.5 (delete)

### Implementation

- [ ] T067 [US8] Add message action menu to `MessageBubble.kt` — long-press or tap menu icon reveals: Copy, Regenerate (assistant only), Edit (user only), Delete. Use M3 `DropdownMenu`
- [ ] T068 [US8] Implement copy action — copy message content to clipboard via `ClipboardManager`, show Snackbar confirmation (FR-053)
- [ ] T069 [US8] Implement regenerate action in `ChatViewModel.kt` — create new child message under the parent of the assistant message being regenerated, call `ChatManager.sendMessage()` to get new response, update `activeLeafMessageId` to the new branch, increment `childCount` on parent (FR-050)
- [ ] T070 [US8] Implement edit action in `ChatViewModel.kt` — create new user message as sibling of the edited message (same `parentMessageId`), then send to get new assistant response, update `activeLeafMessageId`, increment `childCount` on parent (FR-051)
- [ ] T071 [US8] Create `BranchNavigator.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — composable showing "2 of 3" with prev/next arrows at branch points where `childCount > 1`, tapping navigates to sibling branch by updating `activeLeafMessageId` (FR-054, FR-055)
- [ ] T072 [US8] Implement delete action in `ChatViewModel.kt` — delete message and cascade to subtree via Room FK CASCADE, update `childCount` on parent, navigate to remaining sibling if active branch deleted (FR-052)
- [ ] T073 [US8] Update `ChatScreen.kt` to insert `BranchNavigator` at message positions where `childCount > 1`

**Checkpoint**: All message actions work. Branching creates navigable tree structure.

---

## Phase 11: User Story 7 — Tool Calls / Function Calling (Priority: P3)

**Goal**: App detects tool calls, displays them, gets user approval, executes locally, sends results back to model.

**Independent Test**: Enable calculator tool → prompt that triggers tool call → approve → verify result sent to model and final response received.

**Acceptance Scenarios**: US7.1 (display tool call), US7.2 (approve + execute), US7.3 (tools in request), US7.4 (error handling), US7.5 (decline)

### Implementation

- [ ] T074 [P] [US7] Create `ToolDefinitionEntity.kt`, `ConversationToolEnabledEntity.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` and `ToolDefinitionDao.kt` in `dao/` per data-model.md. Add to `PocketLlmDatabase.kt` (migration to version 3). Seed built-in tools (calculator, web_fetch)
- [ ] T075 [P] [US7] Create tool call API models `ToolCallModels.kt` in `app/src/main/java/dev/nutting/pocketllm/data/remote/model/` per contracts section 4 — `ToolDefinition`, `ToolCall`, `FunctionCall`, `ToolCallChunk`, `FunctionCallChunk`
- [ ] T076 [P] [US7] Create `CalculatorTool.kt` in `app/src/main/java/dev/nutting/pocketllm/domain/tool/` — evaluates arithmetic expressions, returns result string
- [ ] T077 [P] [US7] Create `WebFetchTool.kt` in `app/src/main/java/dev/nutting/pocketllm/domain/tool/` — fetches URL content using Ktor client, returns trimmed text content
- [ ] T078 [US7] Create `ToolExecutor.kt` in `app/src/main/java/dev/nutting/pocketllm/domain/tool/` — routes tool calls to appropriate handler by name, returns result or error string
- [ ] T079 [US7] Update `ChatManager.kt` to handle tool call responses — detect `finish_reason: "tool_calls"`, parse tool calls from accumulated chunks, emit `StreamState.ToolCallsPending` with tool call details, wait for approval signal, execute via `ToolExecutor`, send tool results as `role: "tool"` messages, resume streaming. Handle parallel tool calls (FR-047)
- [ ] T080 [US7] Create `ToolCallCard.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — M3 Card displaying tool name, arguments (formatted JSON), Approve/Decline buttons, execution status (pending/running/complete/error), result preview. `contentDescription` for accessibility
- [ ] T081 [US7] Update `ChatScreen.kt` to render `ToolCallCard` for tool call messages, wire approve/decline buttons to `ChatViewModel`
- [ ] T082 [US7] Add tool configuration to `ConversationSettingsSheet.kt` — list of available tools with enable/disable toggles per conversation (FR-046)

**Checkpoint**: Tool calling round-trip works end-to-end with user approval.

---

## Phase 12: User Story 13 — Thinking / Reasoning Display (Priority: P3)

**Goal**: Reasoning content from models (DeepSeek R1, QwQ) displayed in collapsible section, collapsed by default.

**Independent Test**: Send prompt to reasoning model → verify thinking content appears in collapsible section separate from final answer.

**Acceptance Scenarios**: US13.1 (detect + display collapsed), US13.2 (expand), US13.3 (no thinking = no section)

### Implementation

- [ ] T083 [US13] Update `ChatManager.kt` streaming logic to detect thinking content — check for `reasoning_content` field in `Delta`, check for `<think>...</think>` tags in content, extract into separate `thinkingContent` accumulator, save to `Message.thinkingContent` per contracts section 5
- [ ] T084 [US13] Create `ThinkingSection.kt` in `app/src/main/java/dev/nutting/pocketllm/ui/chat/` — M3 collapsible section (AnimatedVisibility) showing "Thinking..." header with expand/collapse chevron, displays thinking content when expanded. Collapsed by default. Only rendered when `message.thinkingContent` is non-null (FR-018)
- [ ] T085 [US13] Update `MessageBubble.kt` to include `ThinkingSection` above the main content for assistant messages with thinking content

**Checkpoint**: Reasoning content displayed in collapsible sections without cluttering the main response.

---

## Phase 13: User Story 9 — Search Conversations (Priority: P3)

**Goal**: User searches across conversation titles and message content.

**Independent Test**: Create conversations with distinct content → search for specific term → verify results link to correct conversation and message.

**Acceptance Scenarios**: US9.1 (search matches), US9.2 (navigate to result)

### Implementation

- [ ] T086 [US9] Add FTS virtual table for message content in `PocketLlmDatabase.kt` — Room `@Fts4` entity or raw SQL for `message_fts` table with content sync triggers. Migration to next version.
- [ ] T087 [US9] Add search query methods to `MessageDao.kt` — `searchMessages(query: String): Flow<List<SearchResult>>` using FTS, join with Conversation for title matching
- [ ] T088 [US9] Add search UI to `ConversationListScreen.kt` — M3 `SearchBar` at top of drawer, search results displayed as list items with conversation title, matching message snippet, and timestamp. Tapping navigates to conversation at matching message position (FR-024)

**Checkpoint**: Search works across titles and message content with navigation to results.

---

## Phase 14: User Story 10 — Export and Share (Priority: P3)

**Goal**: User exports conversations as markdown/text and shares via Android share sheet.

**Independent Test**: Export conversation → verify file contains full message history in markdown format.

**Acceptance Scenarios**: US10.1 (export as file), US10.2 (share via sheet)

### Implementation

- [ ] T089 [US10] Add export function to `ConversationRepository.kt` — `exportAsMarkdown(conversationId): String` builds markdown from active branch messages (role headers, content, timestamps)
- [ ] T090 [US10] Add export/share actions to `ChatScreen.kt` TopAppBar overflow menu — "Export as Markdown" saves to Downloads via `MediaStore`, "Share Conversation" and "Share Message" open Android `ShareSheet` with `Intent.ACTION_SEND` (FR-060, FR-061)

**Checkpoint**: Export and sharing functional.

---

## Phase 15: User Story 11 — Appearance and Theme Settings (Priority: P3)

**Goal**: User customizes theme (light/dark/system) and message font size.

**Independent Test**: Toggle themes → verify UI updates. Adjust font size → verify message text size changes.

**Acceptance Scenarios**: US11.1 (dark mode), US11.2 (font size)

### Implementation

- [ ] T091 [US11] Add appearance section to `SettingsScreen.kt` — theme mode picker (Light/Dark/System) using M3 `SegmentedButton`, message font size slider (12-24sp), dynamic color toggle (FR-070, FR-071, FR-072)
- [ ] T092 [US11] Update `PocketLlmTheme` in `Theme.kt` to read theme mode from `SettingsRepository` and apply, wire dynamic color toggle
- [ ] T093 [US11] Update `MessageBubble.kt` to read `message_font_size_sp` from `SettingsRepository` and apply to message text `fontSize`

**Checkpoint**: Theme and font customization works.

---

## Phase 16: User Story 14 — Image / Multimodal Input (Priority: P3)

**Goal**: User attaches images to messages for vision-capable models.

**Independent Test**: Attach image → send to vision model → verify response describes the image.

**Acceptance Scenarios**: US14.1 (attach from gallery/camera/clipboard), US14.2 (send in vision format), US14.3 (warn if model unsupported), US14.4 (full-screen preview)

### Implementation

- [ ] T094 [P] [US14] Create `ImageCompressor.kt` in `app/src/main/java/dev/nutting/pocketllm/util/` — resize image to max 1024px on long side, compress to JPEG quality 85%, base64-encode. Read `image_max_dimension_px` and `image_jpeg_quality` from settings (FR-081)
- [ ] T095 [US14] Update `MessageInput.kt` to add attach button — image picker (gallery), camera capture, clipboard paste. Display image thumbnails in input area before sending
- [ ] T096 [US14] Update `ChatManager.kt` to build multimodal `ChatCompletionRequest` with `content` as array of `ContentPart` (text + image_url) per contracts section 6 when images attached
- [ ] T097 [US14] Update `MessageBubble.kt` to display attached images inline — load from local URI, tap opens full-screen preview dialog (FR-082, FR-083 vision warning via Snackbar)

**Checkpoint**: Image attachment and vision API integration works end-to-end.

---

## Phase 17: User Story 16 — Parameter Presets (Priority: P3)

**Goal**: Built-in and custom parameter presets for quick configuration.

**Independent Test**: Select "Creative" preset → verify parameters update → create custom preset → reapply.

**Acceptance Scenarios**: US16.1 (built-in presets), US16.2 (custom presets), US16.3 (view/adjust after applying)

### Implementation

- [ ] T098 [P] [US16] Create `ParameterPresetEntity.kt` in `app/src/main/java/dev/nutting/pocketllm/data/local/entity/` and `ParameterPresetDao.kt` in `dao/` per data-model.md. Add to `PocketLlmDatabase.kt` (migration). Seed built-in presets (Creative, Precise, Code, Balanced)
- [ ] T099 [US16] Add preset picker to `ConversationSettingsSheet.kt` — M3 dropdown of presets, selecting one fills parameter sliders, "Save as Preset" button to create custom preset from current values, delete custom presets (FR-100, FR-101, FR-102)
- [ ] T100 [US16] Add preset management section to `SettingsScreen.kt` — list of presets (built-in read-only, custom editable), create/edit/delete custom presets, set default preset

**Checkpoint**: Presets work for quick parameter configuration.

---

## Phase 18: Polish & Cross-Cutting Concerns

**Purpose**: Quality, performance, and edge case handling across all stories

- [ ] T101 Add error handling for all edge cases from spec: mid-stream disconnect (display partial + retry), empty response ("No response generated" + retry), context exceeded + compaction failure (suggest new conversation), message during streaming (queue or inform), network loss (detect + graceful failure), zero models (clear message), undefined tool (display raw + notify), large paste (token warning), 429 rate limiting (retry-after + button), request timeout (timeout error + retry)
- [ ] T102 [P] Add loading, empty, and error states to all screens — `ChatScreen` (empty conversation placeholder, loading spinner, error banner with retry), `ConversationListScreen` (empty state illustration, loading), `ServerConfigScreen` (connection testing spinner, error feedback)
- [ ] T103 [P] Add Material motion and transitions — container transforms between conversation list and chat, shared axis transitions for navigation, purposeful animations per constitution VII
- [ ] T104 [P] Verify accessibility on all screens — TalkBack navigation audit, logical reading order, all `contentDescription` values meaningful, 48dp touch targets, dynamic font scaling without breakage, high contrast support (FR-090 through FR-093, SC-011)
- [ ] T105 [P] Add retry with exponential backoff for 429/503 responses in `OpenAiApiClient.kt` — display retry countdown to user (FR-019)
- [ ] T106 Verify `./gradlew assembleDebug && ./gradlew test && ./gradlew lintDebug` all pass with zero errors
- [ ] T107 Run quickstart.md validation — verify all listed dependencies compile, package structure matches plan, build commands succeed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Phase 2 — MVP target
- **US2 (Phase 4)**: Depends on Phase 2; integrates with US1 chat screen (drawer)
- **US3 (Phase 5)**: Depends on Phase 2; extends US1 server config
- **US4 (Phase 6)**: Depends on Phase 2; adds settings UI
- **US12 (Phase 7)**: Depends on Phase 2; extends US1 chat display
- **US6 (Phase 8)**: Depends on Phase 2; upgrades US1 message rendering
- **US5 (Phase 9)**: Depends on US12 (token counting) for threshold detection
- **US8 (Phase 10)**: Depends on Phase 2; extends US1 message display
- **US7 (Phase 11)**: Depends on Phase 2; extends US1 chat flow
- **US13 (Phase 12)**: Depends on Phase 2; extends US1 message display
- **US9 (Phase 13)**: Depends on US2 (conversation list for search UI)
- **US10 (Phase 14)**: Depends on US2 (conversation export needs message tree)
- **US11 (Phase 15)**: Depends on Phase 2 (settings infrastructure)
- **US14 (Phase 16)**: Depends on US1 (extends message input)
- **US16 (Phase 17)**: Depends on US4 (parameter settings infrastructure)
- **Polish (Phase 18)**: Depends on all desired user stories being complete

### User Story Independence

Most user stories are independently testable after Phase 2 foundation:
- **Fully independent**: US1, US3, US4, US6, US7, US8, US11, US12, US13, US14
- **Depends on US1 integration**: US2 (drawer in chat), US5 (needs token tracking from US12)
- **Depends on US2 integration**: US9 (search in conversation list), US10 (export from conversations)
- **Depends on US4 integration**: US16 (extends parameter settings)

### Parallel Opportunities Per Phase

```
Phase 2 parallel groups:
  Group A: T005, T006, T007 (entities — different files)
  Group B: T008, T009, T010 (DAOs — different files)
  Group C: T012, T013 (API models — different files)
  Group D: T015, T016 (DataStore — different files)
  Group E: T017, T018, T019, T020 (repositories — different files)
  Group F: T028, T029 (tests — different files)

Phase 3 parallel:
  T030, T031, T032 (test + UI states — different files)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Configure a server, send a message, see streamed response
5. Deploy/demo if ready

### Recommended Build Order (Incremental)

1. Setup + Foundational → Foundation ready
2. US1 (Server + Chat) → **MVP: working chat app**
3. US2 (Conversations) → Multi-conversation management
4. US6 (Markdown) → Rich response rendering
5. US12 (Token Usage) → Context awareness
6. US3 (Multi-server) → Server management
7. US4 (System Prompts) → Customization
8. US5 (Compaction) → Long conversation support
9. US8 (Message Actions) → Branching + editing
10. US7 (Tools) → Advanced feature
11. US13 (Thinking) → Reasoning model support
12. US9 (Search) → Discovery
13. US10 (Export) → Sharing
14. US11 (Themes) → Appearance
15. US14 (Multimodal) → Image support
16. US16 (Presets) → Quick params
17. Polish → Edge cases, transitions, final accessibility audit

---

## Summary

| Metric | Count |
|--------|-------|
| **Total tasks** | 107 |
| **Setup tasks** | 4 |
| **Foundational tasks** | 25 |
| **US1 tasks** | 11 |
| **US2 tasks** | 7 |
| **US3 tasks** | 3 |
| **US4 tasks** | 5 |
| **US5 tasks** | 4 |
| **US6 tasks** | 3 |
| **US7 tasks** | 9 |
| **US8 tasks** | 7 |
| **US9 tasks** | 3 |
| **US10 tasks** | 2 |
| **US11 tasks** | 3 |
| **US12 tasks** | 4 |
| **US13 tasks** | 3 |
| **US14 tasks** | 4 |
| **US16 tasks** | 3 |
| **Polish tasks** | 7 |
| **Parallel opportunities** | 41 tasks marked [P] |
| **MVP scope** | Phases 1-3 (40 tasks) |

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks in same phase
- [Story] label maps task to specific user story for traceability
- Each user story is independently testable after its phase checkpoint
- Accessibility is built into every screen task (contentDescription, 48dp targets) per constitution V
- Tests follow BDD naming: `given X when Y then Z` per constitution VI
- Tests target critical logic only (DAO queries, streaming, ChatManager, ViewModels) per constitution III
- Commit after each task or logical group using conventional commits per constitution IV
