# Implementation Plan: Code Review Fixes — On-Device LLM Inference

**Branch**: `002-local-llm-inference` | **Date**: 2026-04-03 | **Spec**: n/a (post-review fix pass)  
**Input**: Code review findings from PR #4 `feat: on-device LLM inference via llama.cpp`

## Summary

Address all findings from the PR #4 code review: one confirmed correctness bug (projector
download progress overflow), one subtle stream-error ordering issue, one UX regression in
scroll behavior, one main-thread performance concern, and several code quality improvements
(duplicate WorkRequest construction, `buildCompactionPrompt` style, `utf8_complete_length`
readability, log privacy). Two review findings were false positives and are documented as
such. No new features or abstractions are introduced — this is a pure fix pass.

## Technical Context

**Language/Version**: Kotlin 2.2.10, JDK 21, C++ (llama.cpp JNI)  
**Primary Dependencies**: Jetpack Compose BOM 2026.01.00, WorkManager, OkHttp 4.12.0, Room 2.8.4  
**Storage**: Room (conversations/messages), DataStore Preferences (settings/models)  
**Testing**: JUnit 4 + Robolectric (local JVM); instrumented tests only for hardware interaction  
**Target Platform**: Android, minSdk 28, targetSdk 36  
**Project Type**: Single-module Android app (`app/`)  
**Performance Goals**: No jank during streaming; token estimation must not block main thread on conversations >50 messages  
**Constraints**: All fixes must be minimal-scope; no new abstractions, no new dependencies

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. MVVM-Compose Single Module | ✅ PASS | All changes stay within `app/` package structure |
| II. Simplicity & No Premature Abstraction | ✅ PASS | Extract one private helper (`buildDownloadWorkRequest`) — justified by 2 call sites |
| III. Test Coverage — Value Over Volume | ✅ PASS | Tests added for projector byte accounting logic and scroll trigger logic |
| IV. Conventional Commits | ✅ PASS | All commits will follow `fix:` / `refactor:` / `perf:` convention |
| V. Accessibility-First | ✅ PASS | No UI changes that affect accessibility |
| VI. BDD Spec-Driven | ✅ PASS | Fixes address observable behavior described in PR #4 test plan |
| VII. Polished Material Design UX | ✅ PASS | Scroll fix improves UX, no Material regressions |
| VIII. Debuggable Error Handling | ⚠️ SEE NOTE | Stream error ordering fix must preserve correct log-before-emit order |

> **Constitution VIII note**: The stream error fix (Task 2) must ensure that any
> `RuntimeException` thrown from `LocalLlmClient.streamChatCompletion` is logged at ERROR
> level before propagating, consistent with constitution principle VIII.

## False Positive Findings (No Action Required)

The following review issues were verified against the source and found to be incorrect:

| Review Item | Finding | Verification |
|-------------|---------|--------------|
| 2.3 — Compaction summary not inserted into inference | FALSE POSITIVE | `sendMessage()` (ChatManager.kt ~line 180) creates a synthetic `MessageEntity(role="system", content="Previous conversation summary: $summary")` and prepends it to `recentMessages` before inference |
| 3.2 — Silent retry failures in ModelDownloadWorker | FALSE POSITIVE | Catch block at line 122 already calls `Log.e(TAG, "Download failed for $modelId", e)` with full stack trace before retry logic |
| OkHttpClient created per-download | FALSE POSITIVE | `client` is a private field of `ModelDownloader` (line 30), instantiated once per class instance, which is a singleton via `AppContainer` |

## Project Structure

### Documentation (this feature)

```text
specs/002-local-llm-inference/
├── plan.md              # This file
├── research.md          # N/A — no unknowns; all issues grounded in source
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Files Touched

```text
app/src/main/java/dev/nutting/pocketllm/
├── domain/
│   ├── ChatManager.kt                          # Task 1 (token estimation), Task 5 (buildCompactionPrompt)
│   └── LocalLlmClient.kt                       # Task 2 (stream error ordering)
├── ui/
│   ├── chat/
│   │   └── ChatScreen.kt                       # Task 3 (scroll logic)
│   └── modelmanagement/
│       └── ModelManagementViewModel.kt         # Task 4 (duplicate WorkRequest)
└── util/
    └── ModelDownloadWorker.kt                  # Task 0 (projector byte accounting)

llm/src/main/cpp/
└── llm_jni.cpp                                 # Task 6 (utf8_complete_length comment)
```

---

## Phase 1: Fix Plan

Tasks are ordered by severity. Each is self-contained and can be committed individually.

---

### Task 0 — Fix projector download progress overflow

**Severity**: High (user-visible: progress bar exceeds 100%)  
**File**: `app/src/main/java/dev/nutting/pocketllm/util/ModelDownloadWorker.kt`  
**Lines**: ~95–106

**Problem**: `modelSizeBytes` is computed as `totalSize - KEY_PROJECTOR_SIZE`. If
`KEY_PROJECTOR_SIZE` is 0 (default when model has no projector, or input data key missing),
then `modelSizeBytes == totalSize`. During projector download: 
`combinedBytes = totalSize + projectorBytesDownloaded` which exceeds `totalSize`, breaking
the progress bar and foreground notification.

**Fix**: Compute `modelSizeBytes` correctly from the input data, accounting for the case
where projector size is 0. During projector download, `combinedBytes` should be clamped to
`totalSize` at most:

```kotlin
// Before projector download loop:
val projectorSizeBytes = inputData.getLong(KEY_PROJECTOR_SIZE, 0L)
val modelSizeBytes = totalSize - projectorSizeBytes  // correct: only when projector present

// Inside projector download collect:
val combinedBytes = (modelSizeBytes + progress.bytesDownloaded).coerceAtMost(totalSize)
```

Also add a guard: only enter the projector download block if `projectorSizeBytes > 0`.

**Commit**: `fix(download): clamp projector download progress to prevent overflow past 100%`

---

### Task 1 — Move token estimation off main thread

**Severity**: Medium (performance — jank on long conversations)  
**File**: `app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt`  
**Lines**: ~137–142

**Problem**: `TokenCounter.estimateTokens()` is called synchronously within `sendMessage()`,
which executes on `Dispatchers.Main` (called from `viewModelScope.launch` in ChatViewModel
with no explicit dispatcher override). For conversations with 100+ messages and a compaction
summary, this blocks the main thread briefly before inference starts.

**Fix**: Wrap the token estimation in `withContext(Dispatchers.Default)`:

```kotlin
val estimatedTokens = withContext(Dispatchers.Default) {
    if (latestCompaction != null) {
        TokenCounter.estimateTokens(latestCompaction.summary) + TokenCounter.estimateTokens(uncompactedMessages)
    } else {
        TokenCounter.estimateTokens(uncompactedMessages)
    }
}
```

`sendMessage()` is already a suspend function, so `withContext` is valid here.

**Commit**: `perf(chat): move token estimation off main thread`

---

### Task 2 — Fix stream error ordering in LocalLlmClient

**Severity**: Medium (correctness — misleading terminal state on native error)  
**File**: `app/src/main/java/dev/nutting/pocketllm/domain/LocalLlmClient.kt`  
**Lines**: ~127–179

**Problem**: `progressJob` emits a final chunk with `finishReason = "stop"` when the
native engine emits `phase == "complete"`. Then `inferChat` returns. If `inferChat` returns
an `"ERROR: ..."` string, we throw a `RuntimeException` — but the "stop" terminal chunk
has already been sent to the consumer. The consumer sees a clean finish followed by an
exception, which is contradictory. Additionally, if `inferChat` throws before the native
engine emits "complete" (e.g., JNI crash), `progressJob` is cancelled without any error
chunk being emitted, and the `channelFlow`'s exception handler propagates the cause — this
is actually safe because `channelFlow` closes with the exception, but no log is emitted at
catch time.

**Fix**: 
1. Do not emit a `finishReason = "stop"` chunk from inside `progressJob` on "complete".
   Instead, always emit the final chunk after `inferChat` returns cleanly (so the terminal
   chunk is only sent when we know the result is not an error):

```kotlin
val progressJob = launch {
    llmEngine.progress.collect { progress ->
        if (progress.tokenText.isNotEmpty() && progress.phase != "complete") {
            send(/* delta chunk */)
        }
        // Do NOT emit final "stop" chunk here
    }
}

val result = llmEngine.inferChat(...)
progressJob.cancel()

if (result.startsWith("ERROR: ")) {
    Log.e(TAG, "Local inference error: ${result.removePrefix("ERROR: ")}")
    throw RuntimeException(result.removePrefix("ERROR: "))
}

// Only emit terminal chunk when result is clean
send(/* final chunk with finishReason = "stop" */)
```

2. Add `Log.e` before throwing (required by Constitution VIII).

**Commit**: `fix(llm): emit stop chunk only after clean inference result, log errors`

---

### Task 3 — Fix scroll triggering when user is reading history

**Severity**: Medium (UX regression — interrupts reading)  
**File**: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatScreen.kt`  
**Lines**: ~102–112

**Problem**: 
```kotlin
LaunchedEffect(state.messages.size, state.isStreaming, state.isCompacting) {
    listState.animateScrollToItem(0)
}
```
This scrolls to the bottom on every message append, even if the user has scrolled up to
read history. It also conflicts with the streaming scroll effect below it.

**Fix**: Track whether the user has manually scrolled up. Only auto-scroll when the list
is already near the bottom (i.e., the user hasn't scrolled away), or when streaming starts:

```kotlin
// Derive whether user is "at bottom" (item 0 in reverseLayout = most recent)
val isAtBottom = remember {
    derivedStateOf { listState.firstVisibleItemIndex == 0 }
}

// Scroll to bottom when a new message arrives AND user is already at bottom
LaunchedEffect(state.messages.size) {
    if (isAtBottom.value) {
        listState.animateScrollToItem(0)
    }
}

// Always scroll when streaming begins (user initiated send)
LaunchedEffect(state.isStreaming) {
    if (state.isStreaming) {
        listState.scrollToItem(0)
    }
}

// Keep pinned during streaming content growth
val streamingContentLength = state.currentStreamingContent.length
LaunchedEffect(streamingContentLength) {
    if (state.isStreaming && streamingContentLength > 0) {
        listState.scrollToItem(0)
    }
}
```

Remove the `state.isCompacting` trigger from the discrete scroll effect (compaction should
not force-scroll the user to the bottom).

**Commit**: `fix(chat): only auto-scroll on new messages when already at bottom`

---

### Task 4 — Extract duplicate WorkRequest construction

**Severity**: Low (code quality — duplication)  
**File**: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementViewModel.kt`  
**Lines**: `startDownload()` ~165–175, `retryDownload()` ~219–228

**Problem**: Both `startDownload()` and `retryDownload()` construct an identical
`OneTimeWorkRequestBuilder<ModelDownloadWorker>` block with the same `.setInputData()`
arguments. The only difference is `ExistingWorkPolicy.KEEP` vs `ExistingWorkPolicy.REPLACE`.

**Fix**: Extract a private helper:

```kotlin
private fun buildDownloadWorkRequest(entry: ModelRegistryEntry): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<ModelDownloadWorker>()
        .setInputData(workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to entry.id,
            ModelDownloadWorker.KEY_MODEL_URL to entry.modelDownloadUrl,
            ModelDownloadWorker.KEY_MODEL_FILENAME to entry.modelFileName,
            ModelDownloadWorker.KEY_TOTAL_SIZE to entry.totalSizeBytes,
            ModelDownloadWorker.KEY_PROJECTOR_URL to entry.projectorDownloadUrl,
            ModelDownloadWorker.KEY_PROJECTOR_FILENAME to entry.projectorFileName,
            ModelDownloadWorker.KEY_PROJECTOR_SIZE to entry.projectorSizeBytes,
        ))
        .build()
```

Both call sites become a single line, passing their respective `ExistingWorkPolicy`.

**Commit**: `refactor(download): extract buildDownloadWorkRequest helper`

---

### Task 5 — Simplify buildCompactionPrompt

**Severity**: Low (code quality — near-identical branches)  
**File**: `app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt`  
**Lines**: ~509–551

**Problem**: `buildCompactionPrompt` has two branches that both return the same
2-element list structure (`[system, user]`), differing only in the system prompt string and
user message content. The branching duplicates the `ChatMessage(...)` construction.

**Fix**: Pull the varying strings into local vals:

```kotlin
private fun buildCompactionPrompt(
    newMessages: List<MessageEntity>,
    priorSummary: String?,
): List<ChatMessage> {
    val newText = newMessages.joinToString("\n") { "${it.role}: ${it.content}" }

    val systemPrompt = if (priorSummary != null) {
        "You have an existing summary of an earlier portion of a conversation. New messages " +
        "have occurred since that summary. Produce an updated summary that integrates BOTH " +
        "the existing summary AND the new messages. Cover every topic, key fact, decision, " +
        "and piece of content. Organize chronologically. The summary must preserve enough " +
        "context to continue the conversation coherently. Respond with only the updated " +
        "summary, no preamble."
    } else {
        "Summarize the ENTIRE following conversation from beginning to end. Cover every " +
        "topic, key fact, decision, and piece of content discussed — do not focus only on " +
        "recent messages. Organize chronologically. The summary must preserve enough context " +
        "to continue the conversation coherently. Respond with only the summary, no preamble."
    }

    val userContent = if (priorSummary != null) {
        "EXISTING SUMMARY:\n$priorSummary\n\nNEW MESSAGES:\n$newText"
    } else {
        newText
    }

    return listOf(
        ChatMessage(role = "system", content = ChatContent.Text(systemPrompt)),
        ChatMessage(role = "user", content = ChatContent.Text(userContent)),
    )
}
```

**Commit**: `refactor(chat): simplify buildCompactionPrompt to single return path`

---

### Task 6 — Add explanatory comment to utf8_complete_length

**Severity**: Low (readability)  
**File**: `llm/src/main/cpp/llm_jni.cpp`  
**Lines**: ~251–268

**Problem**: The function already has good comments on individual steps, but the final
expression `return actual >= expected ? len : i - 1` is non-obvious. The variable names
`actual` and `expected` explain the intent, but not the truncation point choice (`i - 1`).

**Fix**: Add an inline comment:

```cpp
int actual = static_cast<int>(len - (i - 1));
// If we have all bytes for this character, the buffer ends on a complete boundary.
// Otherwise, truncate before the incomplete lead byte (at i-1).
return actual >= expected ? len : i - 1;
```

**Commit**: `refactor(jni): clarify utf8_complete_length truncation logic`

---

### Task 7 — Remove conversation IDs from debug logs

**Severity**: Low (privacy hygiene)  
**File**: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt`  
**Lines**: ~1184–1187

**Problem**: Debug log lines include raw `conversationId`, `serverId`, and a 50-char
preview of the compaction summary. On Android 9 (minSdk 28) and above, logcat requires
`READ_LOGS` permission to read from other apps, so real privacy risk is low. However,
during development with adb attached the conversation content is exposed in the terminal.

**Fix**: Replace the first 50-character summary preview with just the character count.
Keep server and model IDs (not PII), but truncate/hash conversation IDs to a short prefix
in debug logs:

```kotlin
Log.d(TAG, "Starting compaction: conv=${conversationId.take(8)}, model=$modelId")
Log.d(TAG, "Compaction result: ${if (summary != null) "success (${summary.length} chars)" else "null"}")
```

**Commit**: `fix(privacy): redact conversation content from debug logs`

---

## Complexity Tracking

No constitution violations. The single extracted helper (`buildDownloadWorkRequest`) has two
call sites, satisfying Constitution II's requirement that abstractions serve more than one
consumer.

## Acceptance Criteria

- [ ] `./gradlew assembleDebug` passes with zero errors
- [ ] `./gradlew test` passes all unit tests
- [ ] `./gradlew lintDebug` reports zero errors
- [ ] Manual: download a model with a projector — progress bar stays ≤ 100% throughout
- [ ] Manual: scroll up mid-conversation, then receive a new remote message — position held
- [ ] Manual: send a message in a conversation with 100+ messages — no visible jank before streaming begins
- [ ] Manual: trigger a local inference error — logcat shows ERROR before UI shows error state
- [ ] All commits follow Conventional Commits format
