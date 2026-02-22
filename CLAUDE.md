# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Private LLM chat client for Android. Connects to any OpenAI-compatible API (Ollama, LM Studio, OpenAI, etc.) with streaming, conversation management, tool calling, and per-conversation model selection. Also supports on-device inference via llama.cpp for fully offline chat.

## Build & Test Commands

```sh
./gradlew assembleDebug          # debug build
./gradlew assembleRelease        # signed release (needs keystore.properties)
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
./gradlew lintDebug              # lint checks
```

Requires JDK 21, Android SDK 36, and NDK (for native llama.cpp build). Min SDK is 28.

## Architecture

Multi-module Android app using MVVM with Jetpack Compose.

- `app/` — Main application module (UI, ViewModels, data layer, domain logic)
- `llm/` — Native LLM inference library (JNI bridge to llama.cpp)
- `external/llama.cpp` — Git submodule: llama.cpp inference engine

**Package:** `dev.nutting.pocketllm`

## Key Patterns

- State flows through a single `UiState` data class in the ViewModel
- No DI framework; manual/singleton pattern via `AppContainer`
- Material 3 with dynamic color support (light/dark)
- `ChatManager` supports both remote API and local LLM inference via a single streaming interface
- `LocalLlmClient` wraps `LlmEngine` to produce the same `ChatCompletionChunk` streaming events as the OpenAI API client
- GGUF model files stored in `{externalFilesDir}/models/`, metadata in DataStore Preferences
- WorkManager handles background model downloads with resume support

## Release Pipeline

Uses semantic-release on the `main` branch with Conventional Commits. CI (GitHub Actions) builds a signed APK and creates a GitHub release. Version codes follow `MAJOR*10000 + MINOR*100 + PATCH` (managed by `scripts/set-version.sh`).

## Active Technologies
- Kotlin (managed by Kotlin Gradle Plugin 2.2.10), JDK 21 + Jetpack Compose (BOM 2026.01.00), Material 3, Room 2.8.4, Ktor Client 3.4.0, Navigation Compose 2.9.7, kotlinx-serialization-json 1.8.0, mikepenz markdown-renderer 0.39.2, Tink 1.13.0, DataStore 1.1.7 (001-llm-chat)
- Room (SQLite) for conversations/messages, DataStore Preferences for settings, encrypted DataStore (Tink) for API keys (001-llm-chat)
- llama.cpp (git submodule at external/llama.cpp) with JNI bridge for on-device inference, GGML backends (CPU + Vulkan GPU), WorkManager for model downloads, OkHttp 4.12.0 for HTTP downloads (002-local-llm)

## Recent Changes
- 002-local-llm: Added on-device LLM inference via llama.cpp (JNI), model download/management UI, local model selection in chat, GPU offload control, memory pressure handling
- 001-llm-chat: Added Kotlin (managed by Kotlin Gradle Plugin 2.2.10), JDK 21 + Jetpack Compose (BOM 2026.01.00), Material 3, Room 2.8.4, Ktor Client 3.4.0, Navigation Compose 2.9.7, kotlinx-serialization-json 1.8.0, mikepenz markdown-renderer 0.39.2, Tink 1.13.0, DataStore 1.1.7
