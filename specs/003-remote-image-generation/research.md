# Research: Remote Image Generation

Verified on 2026-10-21 against source at:

- stable-diffusion.cpp `a77d362` (`examples/server/api.md`, `routes_openai.cpp`, `routes_sdcpp.cpp`, `async_jobs.*`)
- llama-swap `c31d416`
- openai-openapi `main`
- vllm-omni `6b35bca`
- LocalAI `7f7b10f`
- Ollama `f580b54`

Latency figures are extrapolated, not measured.

## R1. Which server protocol to target

**Decision:** two dialects, chosen per server by probing.

| Dialect | When | Endpoints |
|---|---|---|
| `SD_CPP` | `GET /sdcpp/v1/capabilities` returns 200 and `supported_modes` contains `img_gen` | Async: `POST /sdcpp/v1/img_gen` → `GET /sdcpp/v1/jobs/{id}` (poll) → `POST /sdcpp/v1/jobs/{id}/cancel` |
| `OPENAI_IMAGES` | Anything else | Synchronous: `POST /v1/images/generations` (JSON), `POST /v1/images/edits` (multipart) |

**Rationale:**

- sd-server's OpenAI endpoints ignore `seed`, `negative_prompt`, steps, CFG and sampler. Native controls only travel inside the prompt as `<sd_cpp_extra_args>{json}</sd_cpp_extra_args>`.
- Those endpoints have no progress reporting, no cancel, and no way to recover a result after the client process dies.
- The native async API provides:
  - full parameter control
  - `queue_position`
  - `preview.{pass,step,total_steps,b64_json}`
  - queued-job cancel
  - results that survive the client disconnecting (kept 600 s after completion)

That covers four of the five v1 stories with one protocol. The user runs a standalone sd-server, so the native path is the primary one.

**Alternatives rejected:**

- *OpenAI images only, with `sd_cpp_extra_args` embedded in the prompt*: no progress, no recovery after process death (a requirement), and no queue visibility.
- *A1111 `/sdapi` with `/sdapi/v1/progress`*: progress is global rather than per job, and its synchronous call still dies with the client.
- *Native only*: drops every other server (LocalAI, vLLM-Omni, llama-swap routing). The generic path is cheap: one request, one response.

## R2. sd-server facts that shape the client

**Submitting and polling jobs**

- **Submit:** returns 202 with `{id, kind, status:"queued", created, poll_url}`. Errors: 400 for invalid input, 429 when the queue is full (64 pending jobs), 500 for anything else.
- **Job object fields:**
  - `status`: `queued`, `generating`, `completed`, `failed` or `cancelled`
  - `queue_position`
  - `created`, `started`, `completed`: Unix time in **milliseconds**
  - `result`: `{output_format, images:[{index, b64_json}]}`
  - `error`: `{code, message}`
  - `preview`
- **Poll responses:** 200, 404, or 410 for a job purged about 600 s after it completed.
- **Cancel:** works only for **queued** jobs. A job that's generating returns 409. `features_by_mode.img_gen.cancel_generating` is currently `false`.

**Seeds**

- The default seed is **42** unless the server was started with `-s -1`, so the client must always send `seed`.
- In a batch, image *i* uses `seed + i` (`async_jobs.cpp:276`), so per-image seeds are derived, not reported.

**Images in and out**

- Input images (`init_image`, `ref_images[]`, `mask_image`) are accepted as raw base64 or a data URL.
- Qwen-Image 2.1 edits use `ref_images` and need the server started with the vision weights (`--llm_vision`).
- **Measured on 2026-10-21:** `features_by_mode.img_gen.ref_images` is a server-wide `true`, not a per-model flag. A model that can't edit (SDXS) silently ignores `ref_images` and returns an ordinary text-to-image result, with no error.
  - So the app can't know beforehand that an edit won't work. It refuses an edit only when the server explicitly reports `ref_images: false`.
  - When a server does reject an edit, the app adds a hint about edit support and `--llm_vision`.
- Output formats: `png`, `jpeg`, or `webp` only on WebP-enabled builds. `"jpg"` is rejected.

**Limits and defaults**

- `capabilities.limits` reports min/max width and height, `max_batch_count` (8) and `max_queue_size`.
- Qwen-Image needs dimensions divisible by 32.
- Recipe defaults can be 2048², so the client always sends a size.

**Model state, concurrency, auth**

- **No model loaded:** `/v1/models` returns `{"data":[]}`. Generation returns 503 `model_not_loaded`, or `model_loading` while one loads.
- **Concurrency:** one model context, so jobs run one at a time and the rest queue.
- **Authentication:** none, so the app must warn in the setup guide.

**Errors**

- sd-server: `{"error":"<string>"}`, plus `"message"` and `"code"` on some paths.
- Malformed JSON returns 500, not 400.
- Other servers: vLLM-Omni returns `{"detail":…}`. llama-swap returns plain text.

## R3. Generic OpenAI images dialect

**Request:** `{model, prompt, n, size:"WxH", response_format:"b64_json", output_format:"png"}`.

- `response_format` is required for LocalAI, which defaults to `url`. vLLM-Omni rejects `url` but accepts `b64_json`.
- `size` is always sent; `auto` is not understood by sd-server.

**Response:** `data[].b64_json`. Fall back to downloading `data[].url` relative to the server URL (LocalAI). `output_format` may be missing, so sniff the magic bytes.

**Edits:** multipart with repeated `image[]`. Only the first plain `image` is read by sd-server. Also send `prompt`, `n`, `size`, and `model` (which llama-swap routes on). No mask in v1.

**Parameters:** steps, CFG, seed and negative prompt are **not sent** in this dialect. There is no standard, and each server spells them differently (vLLM-Omni: `num_inference_steps`, `guidance_scale`). The UI shows only size and count for these servers.

**Timeout:** the request timeout is the job deadline. Never retry automatically after the request has been sent.

## R4. Telling image models from chat models

No standard exists. OpenAI's `Model` has only `id`, `object`, `created` and `owned_by`.

**Decision**, in priority order:

1. The server's dialect is `SD_CPP`, so all of its models are image models. Its `/v1/models` lists only `sd-cpp-local`.
2. The user marks the model as an image model or a chat model; this override is persisted per (server, model).
3. Otherwise the model is treated as chat.

**Deferred:** llama-swap's `capabilities.image_generation` and `architecture.output_modalities`. It's only present when configured, and the user isn't running llama-swap (YAGNI). It's cheap to add later as a third rule.

## R5. Executing jobs that outlive the app

**Decision:**

- Persist an `image_jobs` row as the source of truth.
- Run it with a WorkManager `CoroutineWorker`: one unique work per job, `setForeground` with a `dataSync` foreground notification.
- Factor the job logic into a plain `ImageJobRunner` class so it can be unit tested.

**Rationale:**

- A multi-minute job must survive the process being killed (Story 2). WorkManager re-runs the worker after the process dies.
- With `SD_CPP`, the persisted `remoteJobId` lets the worker **resume polling instead of resubmitting**. No GPU work is duplicated, and progress isn't lost.
- With `OPENAI_IMAGES`, a worker found in the RUNNING state on restart can't recover the HTTP response. It fails as "Interrupted" with Retry rather than silently resubmitting expensive work.
- The app already uses WorkManager, a `dataSync` foreground service and a notification channel for model downloads (`ModelDownloadWorker`), so there are no new dependencies or permissions.

**Alternatives rejected:**

- *Extending `GenerationManager`*: it lives in process memory and dies with the process.
- *A hand-rolled foreground service*: duplicates WorkManager's restart and constraint handling.

**Risk:** when WorkManager restarts a worker in the background, `setForeground` can throw `ForegroundServiceStartNotAllowedException` on Android 12+. Catch it, log WARN, and keep polling without a foreground service.

## R6. Where image bytes live

**Decision:** files under `filesDir/images/<conversationId>/<messageId>_<index>.<ext>`, written to a temp file and then renamed. The `generated_images` table holds only path and metadata.

**Rationale:**

- Android's `CursorWindow` limit is 2 MB per window. A 1024² PNG is 1–3 MB, or more at 2048².
- `MessageDao`'s recursive CTE reads every column on every branch load, so blobs would slow every chat.
- Coil decodes files at display size.

**Cleanup** has three layers:

1. Deleting a conversation removes its directory.
2. Room foreign keys cascade-delete `generated_images` rows.
3. A start-up orphan sweep deletes files not referenced by any row and older than one hour. The grace period avoids racing a job that is still writing.

**Existing debt (not fixed here):** user attachments are still stored as base64 data URLs in `messages.imageUris`. They're small JPEGs (≤1024 px), so it's tolerable for now.

## R7. Live preview cost

`preview` modes come from `capabilities.preview_modes`. Choose `proj` (latent projection, cheapest), with `preview_interval` 1.

Each poll returns the latest frame as a base64 PNG, possibly at full resolution, so polling every second could move about 1 MB/s on a LAN.

**Mitigation:**

- Poll every 1 s while generating and every 3 s while queued.
- Write the preview to a single cache file only when `step` changes.
- Provide a "Live preview" toggle (default on).
- Measure in the progress PR, and lower the poll rate if payloads are large.

**Measured on 2026-10-21** against sd-server `a77d362` with SDXS-512 Q8, at 512², 8 steps, on a 4-core CPU:

| Preview mode | Payload per poll (base64) | Generation time |
|---|---|---|
| `proj` | about 16 KB | 30.6 s |
| `tae` | about 600 KB, full resolution | 51.6 s, roughly 70% slower |

**Decision:** request `proj` only, never `tae` or `vae`. Polling every second costs about 16 KB/s, which is negligible.

**Finding (measured the same day):** sd-server reports `preview.{pass,step,total_steps}` only when a preview is requested. Without one, every poll of a generating job has `preview: null`, so there's no step progress at all.

**Consequences:**
- Request `proj` whenever the server lists it, because it's the only source of step progress.
- The "Live preview" setting controls only whether frames are kept and shown, not the request. Turning it off keeps the step bar and costs the same ~16 KB per poll.
- A server without `proj` shows an indeterminate bar plus elapsed time.
- Each frame is a new file under `cacheDir/image-previews/` (`<jobId>_<pass>-<step>.png`), written only when the step changes; older frames are deleted. All of a job's frames are removed when its run ends.

## R8. Latency and timeouts

**Expected latency (extrapolated):** Qwen-Image-2.1 on sd.cpp with a 24 GB GPU at 1024²:

- Turbo, 8 steps: about 10–45 s.
- Base, 20 steps: about 30–120 s.
- CPU offload, a smaller GPU, or a cold model load can add minutes.

**Timeouts:**

| Setting | Value |
|---|---|
| Connect | 15 s |
| Per poll | 30 s |
| Overall job deadline | Per-server `imageTimeoutSeconds`, default 1800 s |
| Synchronous dialect request timeout | Equal to the job deadline |

## R9. Android platform details

- **Save to gallery:** MediaStore `RELATIVE_PATH = Pictures/Pocket LLM` on API 29+. API 28 (our minSdk) needs `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` and a runtime request at save time.
- **Share:** the existing `FileProvider`. Add `<files-path name="images" path="images/"/>` to `file_paths.xml`.
- **Notifications:** `POST_NOTIFICATIONS` is already declared. Request it at the first generation on API 33+. Work continues if it's denied.
  - The prompt is shown only while Android would show it for the first time (no rationale due yet), so a user who said no isn't asked on every send.
  - Each job has its own ongoing notification (low-importance `image_generation` channel), with a Cancel action handled by `ImageJobActionReceiver`.
  - Results go to a separate default-importance `image_results` channel, so users can silence progress without missing results. They're posted only when the app isn't in the foreground, because otherwise the reply already shows the result. A user's own cancel isn't announced.
- **Full-screen zoom:** Compose `transformable` modifier; no new library.
- **Deep link from the completion notification:** a `MainActivity` intent extra with the conversation id, handled by `AppNavGraph`.
