# Local image generation on device — design

**Date:** 2026-10-25
**Status:** Draft for review
**Scope:** v1 = text-to-image and image-to-image with SD 1.5 / SDXL(-Turbo) GGUF models, fully on device, no server required.

## Purpose

pocket-llm can already generate images through remote image servers (stable-diffusion.cpp `sd-server` and any
OpenAI-images-compatible server). This adds the same capability **without any server at all**: the phone runs
the diffusion model itself, so image generation works offline and on a fresh install with no homelab.

The motivating constraint is portability, not performance. A phone that has downloaded a model must be able to
generate images on a plane, with no server profile configured.

## Goals

- Text-to-image (M1) and image-to-image (M2) on device.
- SD 1.5 and SDXL-class models, including Turbo/Lightning low-step variants, as single-file all-in-one GGUFs.
- Works on an 8 GB flagship floor; tuned and validated on a Pixel 9 Pro (Tensor G4, Mali-G715, 16 GB).
- No server profile, URL, API key, or timeout involved.
- Reuse the existing image job pipeline: conversation replies, progress, live previews, cancel, persistence,
  gallery save/share.

## Non-goals (v1)

- Inpainting (mask painting + mask-guided generation) — **deferred by decision**, not rejected.
- Flux / Qwen-Image-class models — **deferred** because they are 6–12+ GB quantized plus a large text encoder,
  which is marginal-to-unusable on phones today. The model manifest and provider seam are designed so adding
  them later is a data/adapter change.
- Multi-file model bundles (separate VAE / CLIP-L / CLIP-G / T5) in the shipped download flow. The manifest
  supports components from day one; v1 downloads single-file all-in-one checkpoints only.
- LoRA, ControlNet, upscaling, SD3, model conversion.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Engine | stable-diffusion.cpp via JNI | Already the engine behind the app's remote `sd-server` path; supports SD1.5/SDXL and Flux/Qwen, GGUF quantized, Vulkan |
| Engine placement | **Separate `:sd` process** | Future-proof: image OOM cannot kill chat, image memory can be capped/reclaimed, llama.cpp and sd.cpp upgrade independently, no ggml symbol collisions |
| Contract | Transport-agnostic `ImageGenerator` seam | Remote `sd-server` and the local engine are both adapters; new families/features flow through capabilities |
| Model shape | Manifest with components | Single-file today, multi-file later without reworking storage/downloads/picker |
| Acquisition | Reuse HF browser + download manager, single-file all-in-one GGUFs; file import as escape hatch | Portable without a curated catalogue |
| Editing scope | M2 img2img only; inpainting deferred | Img2img is a small addition once the engine exists; masks are the largest UI work in the feature |

## Architecture

```
┌──────────────────────────── main process ────────────────────────────┐
│ Image mode UI ── ImageJobLauncher ── ImageJobRunner                  │
│                                          │                           │
│                              ImageGenerator (contract)               │
│                                 ├── RemoteSdCppAdapter (HTTP)        │
│                                 └── LocalImageGenerator (binder)     │
│                                                                      │
│ LocalImageJobScheduler ── starts foreground service, not WorkManager │
│ LocalImageModelStore (DataStore, manifest JSON)                      │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ AIDL: load / submit / cancel / callbacks
┌───────────────────────────────▼──────────────────────────────────────┐
│ :sd process — SdEngineService (foreground, single job at a time)     │
│   sd_ctx lifecycle, generate_image, progress + preview callbacks     │
│   results/previews written to cache handoff dirs; paths over binder  │
│   libpocketsd.so (JNI → external/stable-diffusion.cpp)               │
└──────────────────────────────────────────────────────────────────────┘
```

### New module `sd/`

- `external/stable-diffusion.cpp` pinned as a git submodule next to `external/llama.cpp`.
- Builds `libpocketsd.so` for `arm64-v8a` with `GGML_VULKAN=ON`, mirroring `llm/`'s NDK/CMake setup
  (`GGML_NATIVE=OFF`, KleidiAI off, Vulkan C++ headers + SPIRV-Headers discovery).
- JNI wraps sd.cpp's C API (`new_sd_ctx`, `free_sd_ctx`, `generate_image`, `free`, progress/preview callbacks,
  cancel flag) rather than its CLI or HTTP server. This is what makes init-image + strength (and later masks)
  available without a wire-format change.
- sd.cpp's ggml is built with hidden visibility and exports no `ggml_*` symbols. Process separation already
  removes the collision requirement; this keeps a future in-process option open and avoids accidental coupling.

### `:sd` process contract

AIDL surface, kept deliberately small and versioned:

- `load(manifestJson)`
- `unload()`
- `capabilities()` — engine-level capabilities (Vulkan availability, max size, sampler list). Per-model
  capabilities live in the model manifest and never come from the engine.
- `submit(jobId, requestJson)` — runs one job synchronously on the service's worker; emits
  `onProgress(step, totalSteps, elapsedMs)` and `onPreview(path)`
- `cancel(jobId)`

Rules:

- One job at a time, enforced in the service.
- Results and previews are written by the service into cache handoff directories; only paths cross the binder.
- The engine unloads after an idle timeout or on `onTrimMemory`.
- A model whose manifest `minRamMb` exceeds the device's available memory is refused with `out_of_memory`.
- `linkToDeath` from the main process makes the service abort the current job and clean up if the app dies.
- The service declares a foreground service type (`dataSync`) with the matching permission, and reuses the
  app's existing progress-notification channel and notification-permission handling.

### Main process integration

- `ImageGenerator` contract: `capabilities()`, `submit()`, a flow of progress/preview events, `cancel()`.
  Adapters: the existing remote `SdCppImageClient`, and `LocalImageGenerator` (binds the service).
- Local provider identity is a stable sentinel (`"__device__"`), mirroring `LocalLlmClient.LOCAL_SERVER_ID`;
  it is **not** a `ServerProfileEntity` row.
- `ImageJobEntity.dialect` gains `LOCAL_SD`; `serverId` holds the sentinel; `modelId` is the local image model id.
- `ImageJobLauncher` / `ImageJobRunner` resolve a provider from the job instead of assuming a server row.
- `ImageJobScheduler` gains a local implementation that starts the foreground service instead of enqueueing
  WorkManager work. This is the only structural divergence from the remote path.
- Local jobs are not resumable across process death. On startup, a `LOCAL_SD` job found mid-flight becomes
  `interrupted`, like the existing synchronous OpenAI-images path; Retry starts a fresh job.

## Data model

```text
LocalImageModel
  id, name
  family            SD15 | SDXL
  components[]      { role: DIFFUSION|VAE|CLIP_L|CLIP_G|T5XXL, fileName, sizeBytes, sourceUrl }
  quantization      e.g. Q4_K_M, Q8_0
  totalSizeBytes
  minRamMb
  capabilities      { maxWidth, maxHeight, samplers, supportsImg2Img,
                      supportsNegativePrompt, defaultSteps, defaultCfg }
  downloadStatus, downloadedBytes, errorMessage
  sourceUrl, repoId, revision
  isImported
```

- Stored as JSON in a dedicated `LocalImageModelStore` (DataStore), mirroring `LocalModelStore`.
- Deliberately separate from `LocalModel`: chat models are chat-shaped (`contextWindowSize`, projectors) and
  must never appear in the chat picker; SD checkpoints must never appear in the chat model scanner.
- Files live in `{externalFilesDir}/models/image/`, separate from chat GGUFs.
- v1 manifests contain exactly one `DIFFUSION` component. Deferred model families add `VAE`/`CLIP_*`/`T5XXL`
  components without changing storage, downloads, or the picker.
- Room impact: `ImageDialect` gains `LOCAL_SD` (stored by name — additive, no migration); `serverId` is already
  a non-FK string. No other schema change.

## Model acquisition

- The existing Hugging Face browser gains a **Chat / Image** scope toggle. In Image scope it lists `.gguf`
  files in a repo; the user confirms "add as image model". GGUF magic is validated as today.
- Search continues to use the Hub's `gguf` filter. Repo relevance is the user's choice (paste a known
  diffusion GGUF repo, e.g. an SDXL-Turbo all-in-one), with a short "known good" hint list in the empty state
  instead of a maintained catalogue.
- Import from device storage remains as the escape hatch, with the same confirmation.
- Before downloading, show size and a RAM verdict: "needs ~3.5 GB, this device has 16 GB — OK" or
  "may not fit on 8 GB devices".
- Downloads reuse `ModelDownloadManager` (resume, WorkManager, notifications), one component at a time.

## UI

- **Image mode model picker:** a "This device" section above image servers, listing local image models with
  family/quantization/size, plus a "Download model" entry point. Selecting one sets the job's local provider;
  no URL, key, or timeout fields.
- **Model management:** a new "Image models" section — download progress, cancel/retry, delete (with a warning
  when jobs reference the model), size and RAM details.
- **Image settings:** existing parameters (size, steps, CFG, sampler, seed, negative prompt) become
  capability-driven — the model manifest decides which controls appear and their ranges. M2 adds a **Strength**
  slider shown only for the local provider when an init image is attached.
- Deleting a model keeps history: existing image replies keep their files; a queued job referencing a missing
  model fails with "the model was removed".

## Data flow (one local job)

1. Image mode, "This device" + local model selected. Send creates the user message, pending assistant reply,
   and job row in one transaction (existing behaviour): `dialect = LOCAL_SD`, `serverId = "__device__"`,
   `modelId = <local model id>`, parameters resolved from conversation settings ∩ model capabilities.
2. `LocalImageJobScheduler` starts the `:sd` foreground service (progress notification + Cancel action).
3. The service loads the model if not resident (load progress state), then generates. Step progress and
   preview paths arrive as oneway callbacks; the runner writes them with the existing job-row and
   `ImagePreviewStore` methods.
4. On completion the service writes results to the cache handoff dir; the runner moves them into `ImageStore`,
   inserts `GeneratedImageEntity` rows, and marks the job succeeded in one transaction — the existing `save()`
   path.
5. Idle timeout / `onTrimMemory` unloads the engine. Cancellation stops GPU work within ~1–2 s; a completion
   that races a cancel wins through the existing conditional DB writes.

## Failure mapping

Reuses `ImageJobRunner.codeFor`, with one new kind:

| Situation | Job failure code | User-facing message |
| --- | --- | --- |
| Model file missing or corrupt | `model_not_ready` | "The model is missing or corrupt; download it again." |
| Not enough RAM / engine init fails | `out_of_memory` (new) | "Not enough memory on this device for this model." |
| Model lacks a requested capability | `rejected` | Capability hint (e.g. "this model can't use an input image") |
| User cancels | n/a | Job `CANCELLED` |
| Service/app killed mid-job | `interrupted` | "The app was closed while the image was generating." Retry starts fresh |
| Binder died while idle | n/a | Reconnect silently on the next job |

## Acceptance criteria (v1)

1. With no server profile configured, the user downloads an all-in-one SD1.5 or SDXL-Turbo GGUF through the
   app and generates an image from a prompt in Image mode.
2. The pending reply shows elapsed time and step progress, then the finished image with its caption; the image
   is saved and survives an app restart.
3. Cancel stops within ~2 s, and the reply says it was cancelled.
4. On a model whose manifest enables img2img, attaching a photo and setting Strength produces an edited image.
5. Closing the app mid-generation and reopening it shows the job as interrupted (not silently stuck), and Retry
   starts a fresh generation.
6. On an 8 GB device, a model that does not fit is refused with a memory message before generation starts.
7. Remote image servers and remote chat continue to behave exactly as before.

## Testing

- **Unit:** provider resolution (remote vs local), manifest/capability resolution and control ranges, RAM
  verdict, scheduler dispatch, runner local path with a fake `ImageGenerator` (progress, preview, cancel race,
  each failure kind, interrupted-on-start), model store, and component-aware download handling
  (a single component in v1).
- **Instrumented (no real model):** bind `:sd` with a fake engine; exercise the AIDL contract, cancel,
  binder death, foreground notification, and result handoff paths.
- **Native smoke (opt-in, device):** load a tiny SD 1.5 GGUF and generate 64×64 to prove the JNI path.

## Milestones

- **M0 — spike (gate).** Confirm: sd.cpp builds/links for `arm64-v8a` with Vulkan; a 256×256 SD1.5 generation
  runs from the `:sd` service with live step progress; cancel stops within ~1–2 s; service kill/rebind leaves
  no stuck job or leaked GPU memory; record SD1.5 512² and SDXL-Turbo 1024² timings.
- **M1 — generate.** `sd/` module, service, provider seam, manifest model management, HF image scope, "This
  device" picker, text-to-image.
- **M2 — img2img.** Init image + strength (local-only), capability-gated UI.

## Risks and unknowns (settled in M0)

- Mali Vulkan throughput and thermal throttling on Tensor G4; user-visible generation times.
- Real peak memory during VAE decode (may exceed the model's file size).
- Which all-in-one GGUFs are reliably compatible; v1 documents the tested ones.
- APK size growth from the second native library and Vulkan shaders (~5–15 MB, models not bundled).

## Deferred, tracked

1. **Inpainting** — mask editor UI (brush/erase, feathering, masked preview) plus mask-guided generation.
   Current sd.cpp releases expose a mask input in their image-generation API (verify the exact version at
   implementation time); the work is mostly UI and model-support caveats (SD1.5-inpainting checkpoints vs
   SDXL differential diffusion).
2. **Flux / Qwen-Image** — larger models with multi-file components; the manifest, download flow, and
   capability-driven UI already accommodate them.
3. Multi-file bundles in the shipped download flow, LoRA, ControlNet, upscaling.
