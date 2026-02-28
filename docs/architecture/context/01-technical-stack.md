# Technical Stack

**Last Updated**: 2026-02-27  
**Reference**: ADR 001 - MVVM with Compose, Single Module

---

## Overview

Pocket LLM is built on a modern Android stack focused on:
- **Jetpack Compose** for UI
- **Kotlin Coroutines/Flow** for async operations
- **Room** for local persistence
- **DataStore** for settings storage
- **Ktor** for API client
- **llama.cpp via JNI** for local inference

---

## Core Technologies

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.2.10 | Language |
| JDK | 21 | Runtime |
| Android SDK | compileSdk 36, minSdk 28, targetSdk 36 | Platform |
| Compose BOM | 2026.01.00 | UI framework |
| Material 3 | Latest | Design system |
| Room | 2.8.4 | Local database |
| Ktor Client | 3.4.0 | HTTP client for remote APIs |
| kotlinx-serialization-json | 1.8.0 | JSON serialization |
| DataStore Preferences | 1.1.7 | Settings storage |
| Tink | 1.13.0 | Encryption (API keys) |
| Navigation Compose | 2.9.7 | Screen navigation |
| WorkManager | Latest | Background tasks |

---

## Native Dependencies

| Library | Purpose |
|---------|---------|
| llama.cpp | Local LLM inference engine |
| ggml | Neural network computation backend |
| Vulkan/CUDA (optional) | GPU acceleration for local models |

---

## Build Tools

| Tool | Purpose |
|------|---------|
| Gradle (Kotlin DSL) | Build configuration |
| Kotlin Gradle Plugin | Kotlin compilation |
| semantic-release | Automated versioning/releases |

---

## Testing Stack

| Technology | Purpose |
|------------|---------|
| JUnit 4 | Unit tests |
| Robolectric | Android environment for unit tests |
| Espresso (optional) | Instrumented UI tests |

---

## External Services

| Service | Purpose |
|---------|---------|
| GitHub Actions | CI/CD pipeline |
| GitHub Releases | Distribution |
| Google Play Store | App distribution (when ready) |

---

## Technology Selection Rationale

### Why Room over DataStore for Conversations?

**DataStore**: Simple key-value, good for settings  
**Room**: Relational database with complex queries, perfect for conversations/messages

Conversations need:
- Hierarchical message tree
- Full-text search
- Efficient retrieval by conversation + parent
- Relationships between entities

---

### Why Ktor over OkHttp?

**Ktor**: Kotlin-first, first-class Flow support, type-safe serialization  
**OkHttp**: Java library, more verbose for async

Pocket LLM uses Flow extensively - Ktor integrates natively.

---

### Why llama.cpp via JNI?

**llama.cpp advantages**:
- Pure C/C++, minimal dependencies
- Excellent mobile performance (ARM NEON optimizations)
- Supports GGUF format (community standard)
- No Java bindings needed - clean JNI layer

**Trade-off**: Native build complexity, but worth it for local inference quality.

---

### Why Single Module?

See ADR 001 for full rationale. Summary:
- Faster builds
- Simpler navigation
- Flat dependency graph

---

## Technology Evolution Roadmap

| Version | Current | Future |
|---------|---------|--------|
| Kotlin | 2.2.10 | Update to latest stable quarterly |
| Compose BOM | 2026.01.00 | Update monthly with new versions |
| Room | 2.8.4 | Monitor for 3.x release |

**Policy**: Update major dependencies on quarterly basis unless security-critical.

---

## Compatibility Requirements

| Requirement | Target | Why |
|-------------|--------|-----|
| Min SDK | 28 (Android 9) | Balance of device coverage and modern APIs |
| Compile/Target SDK | 36 | Latest stable at time of writing |

---

*This stack represents the right balance of modern features, stability, and performance for an on-device AI app.*
