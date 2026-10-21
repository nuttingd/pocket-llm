# Data Model: Remote Image Generation

Room schema **6 → 7** in a single migration (`MIGRATION_6_7`). It adds no columns to `messages`: image replies are ordinary assistant messages, linked from the new tables.

## New tables

### `image_jobs`

One row per generation or edit request. It's the source of truth for the job's lifecycle and survives the process dying.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID. Also the WorkManager unique work name, `image-job-<id>` |
| `conversationId` | TEXT NOT NULL | FK → `conversations.id`, `ON DELETE CASCADE`, indexed |
| `messageId` | TEXT NOT NULL | The pending assistant message. FK → `messages.id`, `ON DELETE CASCADE`, unique index |
| `serverId` | TEXT NOT NULL | **Not** a foreign key, on purpose. Deleting a server must not erase the history of jobs that finished. A non-terminal job whose server is gone fails as `interrupted` |
| `modelId` | TEXT NOT NULL | |
| `dialect` | TEXT NOT NULL | `SD_CPP` or `OPENAI_IMAGES`, resolved when the job is submitted |
| `kind` | TEXT NOT NULL | `GENERATE` or `EDIT` |
| `prompt` | TEXT NOT NULL | Exactly what the user typed. Never contains `sd_cpp_extra_args` |
| `paramsJson` | TEXT NOT NULL | The resolved `ImageParameters` actually sent, including the concrete seed |
| `sourceMessageId` | TEXT NULL | For edits: the user message whose attachments are the input images |
| `remoteJobId` | TEXT NULL | sd-server job id. Set immediately after a 202 response, before the first poll |
| `status` | TEXT NOT NULL | See the state machine below |
| `queuePosition` | INTEGER NULL | |
| `pass` | INTEGER NULL | Progress, from `preview.pass` |
| `step` | INTEGER NULL | Progress, from `preview.step` |
| `totalSteps` | INTEGER NULL | Progress, from `preview.total_steps` |
| `previewPath` | TEXT NULL | Cache file holding the latest preview frame, overwritten in place |
| `errorCode` | TEXT NULL | Machine-readable, e.g. `server`, `interrupted`, `timeout`, `storage`, `cancelled_server_busy` |
| `errorMessage` | TEXT NULL | User-facing text |
| `createdAt` | INTEGER NOT NULL | Epoch ms |
| `startedAt` | INTEGER NULL | Epoch ms. Server-reported where available |
| `completedAt` | INTEGER NULL | Epoch ms |

### `generated_images`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | |
| `messageId` | TEXT NOT NULL | FK → `messages.id`, `ON DELETE CASCADE`, indexed |
| `idx` | INTEGER NOT NULL | Order within the reply. Unique together with `messageId` |
| `filePath` | TEXT NOT NULL | Relative to `filesDir` |
| `mimeType` | TEXT NOT NULL | Sniffed from the file's magic bytes |
| `width` | INTEGER NOT NULL | Read from the image header |
| `height` | INTEGER NOT NULL | Read from the image header |
| `seed` | INTEGER NULL | `baseSeed + idx` in the SD_CPP dialect. Null when unknown |
| `prompt` | TEXT NOT NULL | Denormalised so captions, accessibility labels and chat placeholders don't need a join |
| `paramsJson` | TEXT NOT NULL | The parameters used |
| `modelId` | TEXT NOT NULL | |
| `durationMs` | INTEGER NULL | `completed − started` |
| `createdAt` | INTEGER NOT NULL | |

### `model_classifications`

| Column | Type | Notes |
|---|---|---|
| `serverId` | TEXT NOT NULL | FK → `server_profiles.id`, `ON DELETE CASCADE`. Part of the composite PK |
| `modelId` | TEXT NOT NULL | Part of the composite PK |
| `isImageModel` | INTEGER NOT NULL | |

This table holds user overrides only. Automatic detection (`SD_CPP` dialect) is computed at runtime and never stored, so a later server change is picked up.

## New columns

- `server_profiles.imageTimeoutSeconds` INTEGER NOT NULL DEFAULT 1800
- `conversations.imageParamsJson` TEXT NULL: the per-conversation `ImageParameters`. Null means server defaults.

## `ImageParameters` (JSON, Kotlin `@Serializable`)

```kotlin
data class ImageParameters(
    val width: Int? = null, val height: Int? = null,  // null → app default 1024×1024; always snapped to a multiple of 32 and clamped to server limits
    val count: Int? = null,                            // 1..min(4, limits.max_batch_count)
    val steps: Int? = null, val cfg: Float? = null,     // SD_CPP only
    val sampler: String? = null, val scheduler: String? = null, // SD_CPP only; values from capabilities
    val seed: Long? = null,                            // null → random per job (client-generated)
    val negativePrompt: String? = null,                // SD_CPP only
)
```

Omitted fields are left out of the request (FR-015). The exception is `seed`, which SD_CPP requests always carry (research R2).

## Job state machine

```
QUEUED_LOCAL ──submit ok──▶ SUBMITTED ──poll: queued──▶ SUBMITTED (queuePosition)
     │                          │
     │                          └─poll: generating──▶ GENERATING (pass/step/total) ──poll: completed──▶ SAVING ──files+rows committed──▶ SUCCEEDED
     │                                                     │
     ├──sync dialect: request sent──▶ GENERATING ───────────┤
     │                                                     └─poll: failed / HTTP error / deadline──▶ FAILED
     └──user cancel (any non-terminal)──▶ CANCELLED  (server cancel attempted if remoteJobId != null; 409 ⇒ errorCode=cancelled_server_busy)
```

Terminal states are `SUCCEEDED`, `FAILED` and `CANCELLED`.

**Recovery rules** run when the worker starts:

- `QUEUED_LOCAL`: submit.
- `SUBMITTED` or `GENERATING` with a `remoteJobId`: resume polling. Never resubmit. A 404 or 410 response means `FAILED(interrupted)`.
- `GENERATING` without a `remoteJobId` (sync dialect, process died mid-request): `FAILED(interrupted)`.
- `SAVING`: re-poll for the result and redo the save. Saving is idempotent: files are written temp-then-rename, and rows are upserted by `(messageId, idx)`.
- Terminal: return immediately.

Retry (from a failed reply) resets the same row to `QUEUED_LOCAL` with a new seed, unless the conversation has a fixed seed. It clears `remoteJobId` and the progress fields, then enqueues new work.

## Relationships to existing tables

- **Image reply:** an assistant `MessageEntity`, created in the same transaction as the user message and the `image_jobs` row. Its `content` stays `""`, and its `modelId` and `serverProfileId` are set as for chat replies.
- **Pending reply creation:** `ImageJobLauncher` inserts the user message, the pending assistant message, the job row, and the active-leaf update (pointing at the assistant message) in **one Room transaction**. Today's `ChatManager` does these as separate writes. Message insertion goes through `MessageRepository.insertMessage`, so the existing parent/child bookkeeping applies.
- **Regenerate:** the existing sibling-branch mechanism. It creates a new assistant message plus a new job with the same prompt and source attachments.
- **Chat history (FR-013):** when `ChatManager` builds a chat-model request, an assistant message with `generated_images` rows becomes `"[Generated image: <prompt>]"`. One with a non-succeeded job becomes `"[Image generation failed]"`, or is skipped while it's still pending.

## File layout

```
filesDir/images/<conversationId>/<messageId>_<idx>.<png|jpg|webp>
filesDir/images/<conversationId>/.<messageId>_<idx>.tmp            (in-flight writes; swept)
cacheDir/image-previews/<jobId>.png                                (latest preview; deleted at terminal state)
```
