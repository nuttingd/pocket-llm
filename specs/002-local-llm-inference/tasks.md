# Tasks: PR #4 Code Review Fixes — On-Device LLM Inference

**Input**: `specs/002-local-llm-inference/plan.md`  
**Branch**: `002-local-llm-inference`  
**Source**: Post-review fix pass (no spec.md — tasks derived directly from plan.md)

## Format: `[ID] [P?] [FIX-N] Description`

- **[P]**: Can run in parallel (touches different files, no shared dependencies)
- **[FIX-N]**: Maps to the fix number in plan.md (FIX-0 through FIX-7)

---

## Phase 1: Foundational (Verification Baseline)

**Purpose**: Confirm the build and tests are green before touching anything, so regressions are detectable.

- [x] T001 Verify `./gradlew assembleDebug` passes cleanly on `002-local-llm-inference`
- [x] T002 Verify `./gradlew test` passes cleanly on `002-local-llm-inference`
- [x] T003 Verify `./gradlew lintDebug` reports zero errors on `002-local-llm-inference`

**Checkpoint**: Green baseline confirmed — all fixes from here are independently committable

---

## Phase 2: High Severity Fix — Projector Download Progress Overflow

**Goal**: Progress bar stays ≤ 100% when downloading a model with a projector (e.g., SmolVLM, Qwen3-VL)

**Independent Test**: Download a vision model with a projector; observe the progress bar and foreground notification never exceeds 100% during the projector phase

- [x] T004 [FIX-0] Fix `modelSizeBytes` computation and clamp `combinedBytes` in `app/src/main/java/dev/nutting/pocketllm/util/ModelDownloadWorker.kt` (~lines 95–106): derive `projectorSizeBytes` from `inputData.getLong(KEY_PROJECTOR_SIZE, 0L)`, set `modelSizeBytes = totalSize - projectorSizeBytes`, and change `combinedBytes` to `(modelSizeBytes + progress.bytesDownloaded).coerceAtMost(totalSize)`
- [x] T005 [FIX-0] Guard the projector download block in `ModelDownloadWorker.kt` so it only executes when `projectorSizeBytes > 0`, preventing the overflow entirely when no projector is present

**Commit**: `fix(download): clamp projector download progress to prevent overflow past 100%`

**Checkpoint**: FIX-0 complete — projector progress is correct

---

## Phase 3: Medium Severity Fixes (all touch different files — run in parallel)

**Goal**: No main-thread jank before inference starts; stream errors are correctly ordered and logged; user reading history is not force-scrolled

**Independent Test for FIX-1**: Send a message in a conversation with 100+ messages; no visible frame drop in logcat/Perfetto before streaming begins  
**Independent Test for FIX-2**: Trigger a local inference error (e.g., load corrupt model); logcat shows `E/LocalLlmClient` before UI shows error state; no prior "stop" finishReason chunk was emitted  
**Independent Test for FIX-3**: Scroll up mid-conversation while messages arrive; position is held; only auto-scrolls when already at the bottom

- [x] T006 [P] [FIX-1] Wrap token estimation in `withContext(Dispatchers.Default)` in `app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt` (~lines 137–142): replace the inline `TokenCounter.estimateTokens` call with a `withContext(Dispatchers.Default) { ... }` block covering both the `latestCompaction != null` and else branches
- [x] T007 [P] [FIX-2] Fix stream error ordering in `app/src/main/java/dev/nutting/pocketllm/domain/LocalLlmClient.kt` (~lines 127–179): remove the `finishReason = "stop"` final chunk emission from inside `progressJob`'s "complete" branch; instead emit the terminal chunk after `inferChat` returns and the error check passes; add `Log.e(TAG, "Local inference error: ...")` before throwing `RuntimeException` to satisfy Constitution VIII
- [x] T008 [P] [FIX-3] Fix scroll logic in `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatScreen.kt` (~lines 102–112): add `val isAtBottom = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }`; change `LaunchedEffect(state.messages.size, state.isStreaming, state.isCompacting)` to separate effects: one on `state.messages.size` that only scrolls if `isAtBottom.value`, one on `state.isStreaming` that scrolls unconditionally when streaming starts; remove `state.isCompacting` from the discrete scroll trigger

**Commits** (one per fix):  
`perf(chat): move token estimation off main thread`  
`fix(llm): emit stop chunk only after clean inference result, log errors`  
`fix(chat): only auto-scroll on new messages when already at bottom`

**Checkpoint**: FIX-1, FIX-2, FIX-3 complete — medium issues resolved

---

## Phase 4: Low Severity Fixes (all touch different files — run in parallel)

**Goal**: No duplicated WorkRequest construction; `buildCompactionPrompt` has a single return path; `utf8_complete_length` is self-documenting; no conversation content in debug logs

- [x] T009 [P] [FIX-4] Extract `buildDownloadWorkRequest(entry: ModelRegistryEntry): OneTimeWorkRequest` as a private helper in `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementViewModel.kt` (~lines 165–228): move the shared `OneTimeWorkRequestBuilder` block into the helper; update `startDownload()` to call it with `ExistingWorkPolicy.KEEP` and `retryDownload()` with `ExistingWorkPolicy.REPLACE`
- [x] T010 [P] [FIX-5] Simplify `buildCompactionPrompt` in `app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt` (~lines 509–551): extract `systemPrompt` and `userContent` as local `val`s selected by `if (priorSummary != null)`, then collapse the two list-building branches into a single `return listOf(ChatMessage(role="system", ...), ChatMessage(role="user", ...))`
- [x] T011 [P] [FIX-6] Add an inline comment to `utf8_complete_length` in `llm/src/main/cpp/llm_jni.cpp` (~line 268) explaining why the truncation point is `i - 1` when the character is incomplete: `// If all bytes for this character are present, return full length; otherwise truncate before the incomplete lead byte`
- [x] T012 [P] [FIX-7] Redact conversation content from debug logs in `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt` (~lines 1184–1187): replace full `conversationId` with `conversationId.take(8)` and replace the 50-char summary preview with `"${summary.length} chars"`

**Commits** (one per fix):  
`refactor(download): extract buildDownloadWorkRequest helper`  
`refactor(chat): simplify buildCompactionPrompt to single return path`  
`refactor(jni): clarify utf8_complete_length truncation logic`  
`fix(privacy): redact conversation content from debug logs`

**Checkpoint**: All low severity fixes complete

---

## Phase 5: Validation

**Purpose**: Confirm no regressions introduced by the fix pass

- [x] T013 Run `./gradlew assembleDebug` — must pass with zero errors
- [x] T014 Run `./gradlew test` — must pass all unit tests
- [x] T015 Run `./gradlew lintDebug` — must report zero errors
- [ ] T016 [P] Manual: download a vision model (SmolVLM or Qwen3-VL) — confirm progress bar stays ≤ 100% throughout model and projector download phases
- [ ] T017 [P] Manual: scroll up mid-conversation, receive a new message — confirm position is held; scroll down to bottom, receive a new message — confirm auto-scroll fires
- [ ] T018 [P] Manual: send a message in a conversation with 50+ messages — confirm no frame drop in systrace/Perfetto before streaming begins
- [ ] T019 [P] Manual: trigger a local inference error — confirm `E/LocalLlmClient` appears in logcat before the UI error state renders; confirm no prior "stop" chunk caused the UI to flash a completed state

**Checkpoint**: All acceptance criteria from plan.md satisfied — PR ready to merge

---

## Dependencies & Execution Order

```
Phase 1 (Baseline)
    └── Phase 2 (FIX-0: projector bytes)
    └── Phase 3 (FIX-1, FIX-2, FIX-3 — all parallel, no shared files)
    └── Phase 4 (FIX-4, FIX-5, FIX-6, FIX-7 — all parallel, no shared files)
            └── Phase 5 (Validation — after all phases complete)
```

Phases 2, 3, and 4 are all independent of each other and can run concurrently.

### File Conflict Map (no conflicts within parallel groups)

| Task | File |
|------|------|
| T004–T005 | `ModelDownloadWorker.kt` |
| T006 | `ChatManager.kt` |
| T007 | `LocalLlmClient.kt` |
| T008 | `ChatScreen.kt` |
| T009 | `ModelManagementViewModel.kt` |
| T010 | `ChatManager.kt` |
| T011 | `llm_jni.cpp` |
| T012 | `ChatViewModel.kt` |

> **Note**: T006 (`ChatManager.kt`) and T010 (`ChatManager.kt`) touch the same file.
> If running in parallel, coordinate or sequence them (T006 first, then T010, or branch + merge).

---

## Parallel Execution Example

```bash
# After Phase 1 baseline is confirmed, launch all three groups concurrently:

# Group A — FIX-0 (projector bytes)
Task: "Fix modelSizeBytes and clamp combinedBytes in ModelDownloadWorker.kt"
Task: "Guard projector block when projectorSizeBytes == 0"

# Group B — Medium fixes (different files, fully parallel)
Task: "Wrap token estimation in withContext(Dispatchers.Default) in ChatManager.kt"
Task: "Fix stream error ordering in LocalLlmClient.kt"
Task: "Fix scroll LaunchedEffect logic in ChatScreen.kt"

# Group C — Low fixes (different files, fully parallel — sequence ChatManager tasks)
Task: "Extract buildDownloadWorkRequest in ModelManagementViewModel.kt"
Task: "Simplify buildCompactionPrompt in ChatManager.kt"
Task: "Add utf8_complete_length comment in llm_jni.cpp"
Task: "Redact conversation IDs in ChatViewModel.kt"
```

---

## Summary

| Metric | Value |
|--------|-------|
| Total tasks | 19 |
| High severity (Phase 2) | 2 tasks |
| Medium severity (Phase 3) | 3 tasks (all parallelizable) |
| Low severity (Phase 4) | 4 tasks (all parallelizable, one file overlap) |
| Validation (Phase 5) | 7 tasks (4 parallelizable) |
| Parallel opportunities | 7 tasks marked [P] across Phases 3 & 4 |
| Files touched | 8 |
| Estimated commits | 8 (one per fix, as listed in plan.md) |
