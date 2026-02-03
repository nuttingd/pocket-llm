# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Template Android app using Jetpack Compose and Material 3. Clone and build to get a working app immediately.

## Build & Test Commands

```sh
./gradlew assembleDebug          # debug build
./gradlew assembleRelease        # signed release (needs keystore.properties)
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
./gradlew lintDebug              # lint checks
```

Requires JDK 21 and Android SDK 36. Min SDK is 28.

## Architecture

Single-module Android app (`app/`) using MVVM with Jetpack Compose.

**Package:** `dev.nutting.template`

## Key Patterns

- State flows through a single `UiState` data class in the ViewModel
- No DI framework; manual/singleton pattern
- Material 3 with dynamic color support (light/dark)

## Release Pipeline

Uses semantic-release on the `main` branch with Conventional Commits. CI (GitHub Actions) builds a signed APK and creates a GitHub release. Version codes follow `MAJOR*10000 + MINOR*100 + PATCH` (managed by `scripts/set-version.sh`).
