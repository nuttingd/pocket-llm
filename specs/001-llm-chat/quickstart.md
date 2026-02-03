# Quickstart: LLM Chat Client

**Branch**: `001-llm-chat` | **Date**: 2026-02-02

## Prerequisites

- Android Studio Ladybug+ (2024.3+)
- JDK 21
- Android SDK 36 (compileSdk), minSdk 28
- An OpenAI-compatible LLM server running (e.g., Ollama, LM Studio, vLLM)

## Dependencies to Add

Add to `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)

    // Existing Compose dependencies...

    // Navigation (type-safe)
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Room (database)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Ktor (HTTP + SSE)
    implementation("io.ktor:ktor-client-core:3.4.0")
    implementation("io.ktor:ktor-client-okhttp:3.4.0")
    implementation("io.ktor:ktor-client-sse:3.4.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DataStore (settings + encrypted storage)
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Tink (encryption for API keys)
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.39.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.ktor:ktor-client-mock:3.4.0")
    testImplementation("androidx.room:room-testing:2.8.4")
}
```

Add to root `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.2.10-1.0.32" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
```

## Package Structure

```
app/src/main/java/dev/nutting/pocketllm/
├── PocketLlmApplication.kt          # Application class (Tink init, singletons)
├── AppContainer.kt                   # Manual DI container
├── MainActivity.kt                   # Single activity, hosts NavHost
├── data/
│   ├── local/
│   │   ├── PocketLlmDatabase.kt      # Room database definition
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
│   │   ├── OpenAiApiClient.kt         # Ktor HTTP client wrapper
│   │   └── model/
│   │       ├── ChatCompletionRequest.kt
│   │       ├── ChatCompletionResponse.kt
│   │       ├── ChatCompletionChunk.kt
│   │       ├── ModelsResponse.kt
│   │       └── ToolCallModels.kt
│   ├── preferences/
│   │   ├── SettingsDataStore.kt       # Preferences DataStore
│   │   └── EncryptedDataStore.kt      # Tink-encrypted DataStore for API keys
│   └── repository/
│       ├── ServerRepository.kt
│       ├── ConversationRepository.kt
│       ├── MessageRepository.kt
│       └── SettingsRepository.kt
├── domain/
│   ├── ChatManager.kt                # Orchestrates send/stream/compaction
│   └── tool/
│       ├── ToolExecutor.kt            # Built-in tool execution
│       ├── CalculatorTool.kt
│       └── WebFetchTool.kt
├── ui/
│   ├── navigation/
│   │   ├── Routes.kt                  # @Serializable route definitions
│   │   └── AppNavGraph.kt             # NavHost setup
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
│       └── Theme.kt                   # Material 3 + dynamic color
└── util/
    ├── TokenCounter.kt                # Token estimation
    └── ImageCompressor.kt             # Image resize/compress (FR-081)

app/src/test/java/dev/nutting/pocketllm/
├── data/
│   ├── local/dao/                     # Room DAO tests (Robolectric)
│   └── remote/                        # Ktor mock client tests
├── domain/
│   ├── ChatManagerTest.kt
│   └── tool/
└── ui/
    ├── chat/ChatViewModelTest.kt
    └── conversations/ConversationListViewModelTest.kt
```

## First Steps (Build Order)

1. **Rename package**: `dev.nutting.template` → `dev.nutting.pocketllm`
2. **Add plugins**: KSP, kotlinx-serialization to Gradle
3. **Room setup**: Database, entities, DAOs
4. **Ktor setup**: HTTP client, SSE streaming, API models
5. **Encrypted storage**: Tink init, encrypted DataStore for API keys
6. **Navigation**: Type-safe routes, NavHost
7. **Server config screen**: Add/edit/delete servers, model listing
8. **Chat screen**: Send messages, streaming display, markdown rendering
9. **Conversation management**: List, create, rename, delete, persist
10. **Compaction, tools, branching**: P2/P3 features in later stories

## Verify Setup

```sh
./gradlew assembleDebug   # Must compile with all new dependencies
./gradlew test            # Must pass (initially no new tests)
./gradlew lintDebug       # Must report zero errors
```
