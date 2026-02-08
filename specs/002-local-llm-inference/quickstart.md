# Quickstart: Local LLM Inference

**Branch**: `002-local-llm-inference` | **Date**: 2026-02-07

## Prerequisites

- Everything from 001-llm-chat (JDK 21, Android SDK 36, etc.)
- Android NDK (latest stable, for native llama.cpp compilation)
- CMake 3.22+ (via Android SDK Manager)
- Git submodule: `external/llama.cpp`

## New Dependencies

Add to `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"  // Only 64-bit ARM (llama.cpp target)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // Model downloads (OkHttp is already a transitive dep via Ktor-OkHttp engine)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Background download worker
    implementation("androidx.work:work-runtime-ktx:2.11.0")
}
```

## Git Submodule Setup

```sh
git submodule add https://github.com/ggml-org/llama.cpp.git external/llama.cpp
git submodule update --init --recursive
```

## New Package Structure

```
app/src/main/java/dev/nutting/pocketllm/
├── ... (existing packages from 001-llm-chat)
├── data/
│   ├── local/
│   │   └── model/
│   │       ├── LocalModel.kt                 # Data class + DownloadStatus enum
│   │       ├── ModelRegistryEntry.kt          # Registry entry data class
│   │       ├── ModelRegistry.kt               # Hardcoded model list
│   │       └── LocalModelStore.kt             # DataStore persistence for models
│   └── preferences/
│       └── SettingsDataStore.kt               # (modified) New keys: local_models, gpu_offload, etc.
├── domain/
│   ├── ChatManager.kt                         # (modified) Use InferenceProvider interface
│   ├── InferenceProvider.kt                   # Interface: streamChatCompletion()
│   ├── RemoteInferenceProvider.kt             # Wraps OpenAiApiClient
│   └── LocalInferenceProvider.kt              # Wraps LlmEngine for chat
├── llm/
│   └── LlmEngine.kt                          # JNI wrapper (ported from snacktrack)
├── ui/
│   ├── modelmanagement/
│   │   ├── ModelManagementScreen.kt           # Download/import/delete/select UI
│   │   ├── ModelManagementViewModel.kt        # Download orchestration
│   │   └── ModelManagementUiState.kt          # UI state
│   ├── settings/
│   │   └── SettingsScreen.kt                  # (modified) Add "Local Models" entry
│   └── chat/
│       └── ChatViewModel.kt                   # (modified) Select provider based on model type
├── util/
│   └── ModelDownloadWorker.kt                 # WorkManager download with progress
└── ... (existing packages)

app/src/main/cpp/
├── CMakeLists.txt                             # Native build config (ported from snacktrack)
└── llm_jni.cpp                                # JNI bridge (ported from snacktrack)

app/src/test/java/dev/nutting/pocketllm/
├── domain/
│   ├── LocalInferenceProviderTest.kt          # Token streaming format tests
│   └── InferenceProviderTest.kt               # Provider selection tests
├── data/local/model/
│   └── LocalModelStoreTest.kt                 # DataStore persistence tests
└── util/
    └── ModelDownloadWorkerTest.kt             # Download logic tests

external/
└── llama.cpp/                                 # Git submodule
```

## New Navigation Route

```kotlin
// Add to Routes.kt
@Serializable object ModelManagement

// Add to AppNavGraph.kt
composable<Routes.ModelManagement> {
    val viewModel: ModelManagementViewModel = viewModel(...)
    ModelManagementScreen(viewModel, onNavigateBack = { navController.popBackStack() })
}
```

## Room Migration (6 → 7)

Add `isLocalInference` column to `messages` table:

```sql
ALTER TABLE messages ADD COLUMN isLocalInference INTEGER NOT NULL DEFAULT 0;
```

## Build Order

1. **Git submodule**: Add llama.cpp submodule at `external/llama.cpp`
2. **Native build**: Port CMakeLists.txt and llm_jni.cpp to `app/src/main/cpp/`, rename package
3. **LlmEngine.kt**: Port to `dev.nutting.pocketllm.llm`, rename library loading
4. **Data layer**: LocalModel, ModelRegistry, LocalModelStore, new SettingsDataStore keys
5. **InferenceProvider**: Create interface, implement RemoteInferenceProvider (wrapping OpenAiApiClient), implement LocalInferenceProvider (wrapping LlmEngine)
6. **ChatManager refactor**: Replace direct OpenAiApiClient calls with InferenceProvider
7. **ModelDownloadWorker**: Port from snacktrack with OkHttp + WorkManager
8. **Room migration 6→7**: Add `isLocalInference` column to MessageEntity
9. **UI**: ModelManagementScreen, update SettingsScreen, update model selector in ChatViewModel
10. **Navigation**: Add ModelManagement route, wire from Settings

## Verify Setup

```sh
./gradlew assembleDebug   # Must compile with NDK/CMake native build
./gradlew test            # Must pass all tests
./gradlew lintDebug       # Must report zero errors
```

**Note**: First build with llama.cpp will take significantly longer (~5-10 min) as it compiles the full native library. Subsequent builds are incremental.
