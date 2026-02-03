# Research: LLM Chat Client

**Branch**: `001-llm-chat` | **Date**: 2026-02-02

## 1. Markdown Rendering

**Decision**: `com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2` with `-code` module

**Rationale**:
- Pure Compose implementation (no AndroidView wrapper)
- Material 3 theming via `-m3` module
- Syntax highlighting via `-code` module (uses Highlights library)
- Built-in copy button on code blocks (since v0.38.0)
- Streaming-optimized: v0.39.2 fixes "parse thrashing during rapid content updates"
- Async parsing by default (since v0.33.0), `retainState` parameter for smooth streaming
- LazyColumn support for large documents (since v0.33.0)
- Table support (since v0.30.0)
- Compatible with Compose BOM 2026.01.00 (uses Compose 1.10.2)

**Alternatives Considered**:
- `dev.jeziellago:compose-markdown` (0.5.8): Uses Markwon via AndroidView wrapper. No streaming optimization. Basic syntax highlighting only.
- `com.halilibo.compose-richtext` (1.0.0-alpha03): Experimental/alpha status, no tests, no syntax highlighting, no streaming optimization.
- GetStream stream-chat-android-ai: Vendor lock-in, requires Stream Chat SDK subscription.

**Dependencies**:
```kotlin
implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")
implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.39.2")
implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.39.2") // if image rendering needed
```

---

## 2. Local Database (Room)

**Decision**: Room 2.8.4 with KSP, flat schema with self-referencing foreign keys for message tree

**Rationale**:
- Room 2.8.4 is current stable with full KSP support
- Self-referencing `parentMessageId` FK on messages enables tree branching without complex patterns
- Room disallows automatic parent object lookup in `@Relation` annotations for self-references — manual DAO queries with recursive CTEs are the correct approach
- `childCount` and `depth` fields improve query performance for tree traversal
- Indices on `conversationId` and `parentMessageId` for FK columns
- `@Transaction` annotation for multi-query DAO methods
- `Flow` return types for reactive UI updates

**Alternatives Considered**:
- Closure Table pattern: Separate ancestor-descendant table. More complex to maintain, better for frequent "get all descendants" queries. Overkill for chat branching (typically shallow trees).
- Nested Sets / Path Enumeration: Academic solutions that add complexity without benefit for chat apps.
- `@Relation` annotations: Not suitable for self-referencing tree structures in Room.

**Dependencies**:
```kotlin
implementation("androidx.room:room-runtime:2.8.4")
implementation("androidx.room:room-ktx:2.8.4")
ksp("androidx.room:room-compiler:2.8.4")
```

---

## 3. HTTP Client & SSE Streaming

**Decision**: Ktor Client 3.4.0 with native SSE plugin

**Rationale**:
- Ktor 3.4.0 (January 2026) includes unified SSE API across all engines
- Coroutine-native: built on Kotlin coroutines and Flow from the ground up
- Native SSE support via `serverSentEventsSession` — no extra library needed
- Structured concurrency: automatic cleanup when Flow is canceled
- Type-safe: SSE plugin supports deserialization into Kotlin data classes
- `HttpRequestLifecycle` plugin with `cancelCallOnClose = true` for automatic resource cleanup on disconnect
- Handles `data: [DONE]` termination by checking `event.data == "[DONE]"` and exiting the session
- Cancellation: cancel the coroutine scope to automatically close the HTTP connection

**Alternatives Considered**:
- OkHttp 5.3.0 + okhttp-eventsource 4.1.1: Mature and battle-tested but requires separate SSE library, callback-based (needs manual Flow conversion), more boilerplate, not designed for KMP.

**Dependencies**:
```kotlin
implementation("io.ktor:ktor-client-core:3.4.0")
implementation("io.ktor:ktor-client-okhttp:3.4.0")
implementation("io.ktor:ktor-client-sse:3.4.0")
implementation("io.ktor:ktor-client-content-negotiation:3.4.0")
implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")
```

---

## 4. Encrypted Storage for API Keys

**Decision**: DataStore + Tink (NOT EncryptedSharedPreferences)

**Rationale**:
- EncryptedSharedPreferences is officially deprecated as of `androidx.security:security-crypto:1.1.0-alpha07` — Google will not ship further versions
- DataStore provides async I/O off main thread (critical for Android 2026), atomic updates, Flow-based reactive reads
- Tink provides the encryption layer via `StreamingAead` with Android Keystore-backed master keys
- Architecture: DataStore handles storage, Tink handles encryption, Android Keystore manages keys — clean separation of concerns
- Custom `Serializer<Preferences>` wraps Tink encryption/decryption around DataStore reads/writes

**Alternatives Considered**:
- EncryptedSharedPreferences: Deprecated. Synchronous main thread access, brittle across manufacturers.
- Android Keystore + SharedPreferences (manual): Synchronous I/O, deprecated by Android team.
- SQLCipher + Room: Overkill for key-value storage; better for encrypting entire databases.

**Dependencies**:
```kotlin
implementation("com.google.crypto.tink:tink-android:1.13.0")
```

---

## 5. Navigation

**Decision**: Navigation Compose 2.9.7 with type-safe `@Serializable` routes

**Rationale**:
- Latest stable release (January 30, 2026)
- Type-safe navigation APIs with `@Serializable` data classes/objects (stable since Navigation 2.8.0)
- Compile-time safety for navigation arguments via `NavBackStackEntry.toRoute<T>()`
- Required by Navigation 3.0 (future-proofing)
- `NavigationSuiteScaffold` for adaptive UI: automatically switches between navigation rail (tablets), bottom nav (phones), and drawer based on window size

**Alternatives Considered**:
- String-based routes: Legacy, error-prone, not recommended for new projects.
- Compose Destinations: Third-party, adds code generation overhead.

**Dependencies**:
```kotlin
implementation("androidx.navigation:navigation-compose:2.9.7")
```

---

## 6. DataStore Preferences (Settings)

**Decision**: DataStore Preferences 1.1.7 for app settings (theme, defaults, last-used server/model)

**Rationale**:
- Thread-safe, built on Kotlin Coroutines and Flow
- Atomic updates prevent data corruption
- Asynchronous by default — all I/O off main thread
- Flow-based observation for automatic UI updates
- Type-safe keys (`stringPreferencesKey`, `intPreferencesKey`, etc.)
- Single DataStore instance as `Context` extension property for singleton behavior

**Alternatives Considered**:
- SharedPreferences: Synchronous, deprecated pattern for new code.
- Proto DataStore: Overkill for simple key-value settings.

**Dependencies**:
```kotlin
implementation("androidx.datastore:datastore-preferences:1.1.7")
```

---

## 7. JSON Serialization

**Decision**: kotlinx-serialization-json 1.8.0

**Rationale**:
- Official Kotlin serialization library, multiplatform-ready
- `@SerialName` for mapping snake_case API fields to camelCase Kotlin properties
- `ignoreUnknownKeys = true` handles API evolution gracefully
- `explicitNulls = false` reduces payload size
- Line-by-line SSE parsing: each `data:` line is independently `decodeFromString`-ed
- Compatible with Kotlin 2.2.x and Ktor content negotiation plugin

**Alternatives Considered**:
- Gson: Legacy, not Kotlin-friendly, no coroutines support.
- Moshi: Good but kotlinx.serialization is official and multiplatform.
- Jackson: Heavy, Java-centric, overkill for mobile.

**Dependencies**:
```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
```

---

## Summary

| Area | Decision | Version |
|------|----------|---------|
| Markdown | mikepenz multiplatform-markdown-renderer | 0.39.2 |
| Database | Room + KSP | 2.8.4 |
| HTTP/SSE | Ktor Client + SSE plugin | 3.4.0 |
| Encryption | DataStore + Tink | Tink 1.13.0 |
| Navigation | Navigation Compose (type-safe) | 2.9.7 |
| Settings | DataStore Preferences | 1.1.7 |
| JSON | kotlinx-serialization-json | 1.8.0 |
