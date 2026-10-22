# Implementation Plan: Remote Image Generation

**Branch**: `003-remote-image-generation` (developed on the session branch, one PR per phase) | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)
**Inputs**: [research.md](research.md) · [data-model.md](data-model.md) · [contracts/image-servers.md](contracts/image-servers.md) · [quickstart.md](quickstart.md)

## Summary

*(Amended 2026-10-22: image servers are a separate server type, and generating is an explicit Image mode in the composer; see spec Amendment 1.)* The user picks an image model in the existing model picker. Each prompt becomes a **persisted image job**
(`image_jobs` row) run by a **WorkManager worker** with a foreground notification.

- **sd-server:** the worker uses the native async API: submit → poll with progress and preview → fetch the result. This survives process death by resuming from the stored remote job id.
- **Other OpenAI-images servers:** a synchronous fallback.

Images are stored as **files**, referenced by a `generated_images` table, and rendered as an image reply in
the chat, with full-screen view, save, share, edit, reuse seed and regenerate. Image conversations get
their own parameter sheet. Editing reuses the existing attachment flow and sends attachments as reference images.

## Technical Context

**Language/Version**: Kotlin 2.2.10, JDK 21
**Primary Dependencies**: Ktor 3.4.0 (OkHttp engine), Room 2.8.4, WorkManager 2.10.1, Coil 3.1.0, Compose BOM 2026.01.00. **No new dependencies.**
**Storage**: Room schema 6 → 7 (3 tables, 2 columns), app-private files under `filesDir/images/`
**Testing**: JUnit 4 + Robolectric; `ktor-client-mock` for contracts; `room-testing` for the migration
**Target Platform**: Android minSdk 28 / targetSdk 36
**Performance Goals**: progress visible ≤3 s after the server reports it; 20-image conversation opens without jank (SC-004)
**Constraints**: no image bytes in SQLite; no automatic resubmission of expensive work; sd-server has no auth

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I. MVVM-Compose single module | ✅ | All code in `app/`, new packages `domain/image`, `data/remote/image`. UI state stays in `ChatUiState` and the existing settings/server UI states. The worker holds no UI references |
| II. Simplicity / no premature abstraction | ⚠️ justified | No shared client interface: the two dialects have different shapes (submit/poll/cancel vs one call), so `ImageJobRunner` branches on the dialect *(changed in Phase 2; the first draft had an `ImageServerClient` interface)*. The one interface is `ImageJobScheduler` (WorkManager in the app, a fake in tests). `ImageJobRunner` is split from the Worker *only* so the job logic is testable without WorkManager. Three new tables (see Complexity Tracking). llama-swap metadata detection is deferred (YAGNI) |
| III. Test value over volume | ✅ | Tests target the contracts (parsing and request shape), the job state machine and recovery, file safety, the migration, and the chat-history placeholder. No tests for trivial UI |
| IV. Conventional Commits | ✅ | One `feat(image): …` PR per phase; each releases as a minor version |
| V. Accessibility-first | ✅ | Images described by their prompt; progress `liveRegion=Polite` announced only at phase changes; 48dp actions; zoom viewer has Close and action buttons, not gesture-only |
| VI. BDD spec-driven | ✅ | Each phase lists the spec scenarios it delivers; test names follow `given … when … then …` |
| VII. Polished Material UX | ✅ | Loading, queued, progress, error and empty states designed per state; a Material bottom sheet for parameters; snackbars for save |
| VIII. Debuggable errors | ✅ | Every failure path is logged at ERROR with its exception before the job row is updated (the job row is the "emit"). Server cancel failures log WARN with justification |

## Architecture

```
ChatViewModel.send ─(image model?)─▶ ImageJobLauncher.start(conversation, prompt, attachments)
                                        │  tx: user msg + pending assistant msg + image_jobs(QUEUED_LOCAL)
                                        └─▶ WorkManager.enqueueUniqueWork("image-job-<id>", KEEP, ImageGenerationWorker)
ImageGenerationWorker (CoroutineWorker, setForeground dataSync)
   └─▶ ImageJobRunner.run(jobId)  ── state machine (data-model.md), recovery rules
          ├─ ImageServerClients.forServer(server) → SdCppImageClient | OpenAiImagesClient   (dialect via capability probe, cached)
          ├─ ImageStore (atomic file writes, delete, orphan sweep)
          ├─ ImageJobDao / GeneratedImageDao  (progress throttled to step changes)
          └─ ImageNotifications (ongoing progress + "Image ready" deep link)
ChatViewModel observes: messages + image_jobs + generated_images for the conversation → ChatUiState.imageReplies[messageId]
ChatManager (chat models): assistant messages with generated images → "[Generated image: <prompt>]"
```

**Model classification** (`ModelClassifier`, in `domain/image`):

- `isImageModel(server, modelId)` = user override ?: (dialect(server) == SD_CPP)
- The dialect is probed once when a server is saved or its models are refreshed, and cached in memory per server id with a 10 min TTL.
- A failed probe with no cached value counts as `OPENAI_IMAGES`, but the result isn't cached, so the next refresh tries again.

**Send routing** in `ChatViewModel.sendMessageInternal`:

- If the selected model is an image model, use `ImageJobLauncher`.
- Otherwise use the existing `GenerationManager` path, unchanged.
- Image conversations never request a title (FR-014), because they never go through `GenerationManager`.

## Project Structure

```
app/src/main/java/dev/nutting/pocketllm/
├── data/local/
│   ├── entity/ ImageJobEntity.kt, GeneratedImageEntity.kt, ModelClassificationEntity.kt   (new)
│   ├── dao/    ImageJobDao.kt, GeneratedImageDao.kt, ModelClassificationDao.kt            (new)
│   └── PocketLlmDatabase.kt          (v7, MIGRATION_6_7)
├── data/remote/image/                                                                     (new)
│   ├── ImageHttp.kt                  (ImageApiException, error-shape normalisation, shared HTTP client)
│   ├── SdCppImageClient.kt           (A1–A4)
│   ├── OpenAiImagesClient.kt         (B1–B3)
│   └── ImageApiModels.kt             (@Serializable request/response DTOs)
├── domain/image/                                                                          (new)
│   ├── ImageParameters.kt            (+ size snapping/clamping, seed resolution)
│   ├── ModelClassifier.kt
│   ├── ImageJobLauncher.kt           (create rows + enqueue; retry; cancel)
│   ├── ImageJobRunner.kt             (state machine; pure-ish, injected clock/client/dao/store)
│   ├── ImageGenerationWorker.kt
│   ├── ImageStore.kt
│   └── ImageNotifications.kt
├── ui/chat/
│   ├── ImageReply.kt                 (pending/progress/preview/error/images grid + caption + actions)
│   ├── ImageViewer.kt                (full screen, zoom, save/share)
│   ├── ImageParametersSheet.kt       (Story 4)
│   └── MessageBubble.kt, ChatScreen.kt, ChatViewModel.kt, ChatUiState.kt, MessageInput.kt (modified)
├── ui/server/ServerConfigScreen.kt   (image timeout field, "Image server" badge)
├── domain/ChatManager.kt             (placeholder text for image replies)
├── AppContainer.kt, MainActivity.kt (deep link), AndroidManifest.xml (WRITE_EXTERNAL_STORAGE maxSdk 28), res/xml/file_paths.xml
app/src/test/java/dev/nutting/pocketllm/  (mirrors the above)
```

## Delivery phases (one PR each, merged in order)

Each phase is releasable. Image models only behave differently once Phase 3 lands, and Phases 1 and 2 add no visible UI.

### Phase 1: Storage foundation · `feat(image): storage for image jobs and generated images`

- Migration 6 → 7, the entities and DAOs, and the `ImageStore`:
  - atomic `write(conversationId, messageId, idx, bytes)` (temp file, then rename)
  - `deleteConversation`
  - `sweepOrphans(referencedPaths, graceMs)`
- Conversation delete removes the image directory. The orphan sweep is scheduled once per app start on `Dispatchers.IO`.
- **Tests:**
  - `given v6 database with conversations when migrated to v7 then new tables exist and data is intact` (room-testing)
  - `given bytes when written then file appears only after complete write and no temp file remains`
  - `given write failure mid-stream then no partial file remains`
  - `given conversation deleted then its image directory is removed`
  - `given unreferenced file older than grace period when swept then deleted and newer temp file kept`

### Phase 2: Server clients and detection · `feat(image): sd-server and OpenAI images clients`

- `SdCppImageClient` and `OpenAiImagesClient` per the contracts; `ModelClassifier`; the dialect probe.
- **Tests** (MockEngine):
  - `given capabilities with img_gen when probed then SD_CPP and limits parsed`
  - `given 404 capabilities then OPENAI_IMAGES`
  - `given parameters when submitting then seed always sent, size snapped to 32 and clamped, unset fields omitted`
  - `given edit when submitting then ref_images are data URLs`
  - job-status parsing for each state
  - `given 409 cancel then reported as server-busy`
  - `given url-only response then image downloaded`
  - error-shape normalisation (string `error`, object `error`, `detail` string and list, plain text)
  - `given 503 model_loading then ModelNotReady`
  - `given generic edit then multipart has repeated image[] parts and model field`

### Phase 3: Text-to-image end to end (Story 1) · `feat(image): generate images with image models`

- **Job execution and send routing:** `ImageJobLauncher`, `ImageJobRunner` (all states, including recovery), `ImageGenerationWorker` with a minimal foreground notification, and send routing in the view model.
- **Chat UI:**
  - the `ImageReply` composable with pending/elapsed, images and captions, and error + Retry
  - `ImageViewer` with zoom, save to gallery (API 28 permission path) and share (FileProvider)
  - the image badge and "Generates images" toggle in the model picker
  - the "Image server" badge in server settings, and the image timeout field
- **Chat-model integration:** regenerate as a branch; the `ChatManager` placeholder; no title for image conversations.
- **Other surfaces:**
  - Markdown export writes `[Generated image: <prompt>]` for image replies.
  - The conversation-list preview shows "Image: <prompt>" instead of an empty line.
  - Message deletion relies on the FK cascade plus the orphan sweep for files.
- **Delivers** Story 1 scenarios 1–9.
- **Tests:**
  - `given queued job when runner runs then submits, persists remote id before polling, saves images, and succeeds`
  - `given job with remote id when worker restarts then resumes polling without resubmitting`
  - `given sync job found generating on restart then fails as interrupted`
  - `given poll 410 then fails as interrupted`
  - `given deadline exceeded then fails with timeout`
  - `given storage write fails then job fails and no image rows exist`
  - `given SAVING state on restart then save is redone idempotently`
  - `given image reply in history when sending to chat model then placeholder text includes prompt`
  - `given image model selected when user sends then pending assistant message and job created in one transaction`
  - `given image conversation then no title request is made`
  - `given override marks model as image then classifier returns true; given sd-cpp server then auto true`

### Phase 4: Survive the app closing (Story 2) · `feat(image): background image jobs with notifications`

- **Notifications:** an ongoing notification (elapsed time, step, Cancel action), and a completion or failure notification that deep-links to the conversation.
- **Permissions:** request `POST_NOTIFICATIONS` at the first generation.
- **Recovery and cancel:**
  - recovery when the worker is restarted in the background (catch the foreground-service restriction)
  - cancel in the chat and from the notification, with server cancel and the `cancelled_server_busy` message
  - "Queued (position N)"
- **Tests:**
  - `given queued remote job when user cancels then server cancel called and job cancelled`
  - `given generating job when cancel returns 409 then cancelled locally with server-busy note`
  - `given foreground start not allowed then job continues and logs warning`
  - `given two jobs then each tracks its own queue position`
- **Manual:** `adb shell am kill` during a job, reboot during a job, and Do-not-disturb or denied notifications.

### Phase 5: Live progress and preview (Story 3) · `feat(image): live generation progress and previews`

- **Progress:** phase text, a determinate bar from `step/total_steps`, and per-pass labelling.
- **Preview:** a preview thumbnail (`proj` mode) written only when `step` changes; a "Live preview" setting.
- **Poll rate:** measure the preview payload size and tune the poll interval (research R7).
- **Tests:**
  - `given step unchanged then no DB write or preview write`
  - `given server without proj preview mode then preview omitted and progress indeterminate` (sd-server reports steps only alongside a preview; see research R7)
  - `given live preview off then progress recorded but no frame kept`
  - describe-status formatting

### Phase 6: Parameters (Story 4) · `feat(image): image generation parameters`

- An `ImageParametersSheet` replaces the chat parameter sheet for image conversations:
  - size presets: 1:1 1024², 3:4 896×1152, 4:3 1152×896, 9:16 768×1344, 16:9 1344×768, plus custom
  - count 1–4, steps, CFG, sampler and scheduler (from capabilities), seed, negative prompt
  - server defaults shown as placeholders
- SD_CPP-only fields are hidden for the generic dialect.
- The "Reuse seed" action on an image (in the full-screen viewer, next to Save and Share).
- A new chat keeps its image settings in memory until its first prompt creates the conversation, which then stores them.
- **Tests:**
  - `given custom 1000x1000 then snapped to 992x992`
  - `given count above server max then clamped`
  - `given fixed seed then sent unchanged and batch seeds are seed+i`
  - `given generic dialect then only n and size sent`
  - (the four above were written with `ImageParameters.resolve` in Phase 3) `ImageSizePresetTest` for preset matching

### Phase 7: Editing (Story 5) · `feat(image): edit images with a prompt`

- **Editing:**
  - attachments with an image model become an EDIT job: SD_CPP `ref_images`, or multipart `image[]` for the generic dialect
  - an "Edit" action on generated images attaches the file (read through FileProvider, downscaled by the existing compressor setting)
  - the error message when the server lacks edit support
- **Tests:**
  - `given attachments with image model then EDIT job with source message`
  - `given generated image edit action then attached to input`
  - `given server error about vision then edit-support message shown`
  - Also: an edit with no size set follows the photo's aspect ratio at ~1 MP; regenerate and retry of an edit reuse the same photo; an edit whose photo is gone fails as `source_missing`
- **Not detectable:** a model that can't edit makes sd-server ignore `ref_images` silently (research R2), so the quickstart explains it.

## Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Preview payloads are large (a full-resolution PNG per poll) | Medium | Measure in Phase 5. Throttle polling; make the preview optional |
| WorkManager restarts the worker in the background and the foreground-service start is denied (Android 12+) | Medium | Catch and continue without a foreground service. Polling still finishes while the process lives |
| sd-server API changes (it moves fast) | Medium | The contracts are pinned to `a77d362`. Parsing is lenient (`ignoreUnknownKeys`, nullable fields). The capability probe gates behaviour |
| A cancelled running job keeps the GPU busy | Certain (server limit) | Explain it in the UI. Later jobs simply queue |
| Users expose an unauthenticated sd-server | Medium | Warning in the quickstart and the server settings badge tooltip |
| `messages` recursive CTE / branch logic interacts badly with pending assistant messages | Low | The pending message is an ordinary assistant message with empty content (already supported). Covered by Phase 3 tests |

## Complexity Tracking

| Addition | Why needed | Simpler alternative rejected because |
|---|---|---|
| `image_jobs` table | Job state must survive the process dying and drive the UI after re-entry (FR-006) | In-memory state (`GenerationManager`) dies with the process |
| `generated_images` table | Metadata (seed, size, params) for captions, reuse seed and placeholders; cascade deletes | Paths in `messages.imageUris`: no metadata, and it overloads a column meant for user attachments |
| `model_classifications` table | User overrides per (server, model) | DataStore keyed strings: no cascade when a server is deleted, and awkward to query |
| `ImageJobScheduler` interface | Lets `ImageJobLauncher` be tested without WorkManager | Testing through WorkManager's test utilities is heavier and slower |
| `ImageJobRunner` split from the Worker | Unit-test the state machine without WorkManager | Testing through `TestListenableWorkerBuilder` is heavier and still needs the same fakes |

## Out of scope

As listed in spec.md. Follow-ups worth tracking:

1. An LLM tool `generate_image` that reuses `ImageJobLauncher`.
2. llama-swap capability metadata in detection.
3. Moving user attachments out of `messages.imageUris` into files.
