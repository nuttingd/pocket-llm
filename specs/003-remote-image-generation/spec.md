# Feature Specification: Remote Image Generation

**Feature Branch**: `003-remote-image-generation`
**Created**: 2026-10-21
**Status**: Draft
**Input**: User description: "Support remote image generation (e.g. Qwen-Image-2.1) through a self-hosted server. Image model selected in the existing model picker; separate stable-diffusion.cpp `sd-server` profile; v1 includes image editing, advanced parameters, surviving app close, and live progress."

## Amendment 1 (2026-10-22): image servers are their own kind of server

In use, treating image servers like chat servers (an image model chosen in the chat model picker, with a per-model "Generates images" override) was confusing and not discoverable. This replaces that interaction model:

- **Server types:** every server profile is either **Chat** or **Image generation**, chosen when it's added. While the URL is typed, the app probes it. Finding stable-diffusion.cpp pre-selects Image generation, unless the user already picked the type.
  - The server list shows **Chat servers** and **Image servers** as separate sections.
  - An image server that isn't stable-diffusion.cpp needs an **image model**, picked from the models it lists or typed in.
- **Composer mode:** when any image server exists, the message bar has a **Chat / Image** toggle.
  - The model picker at the top follows the mode. In Chat mode it lists chat servers and models; in Image mode it lists image servers and the selected server's image models: the one a stable-diffusion.cpp server serves, or the models an OpenAI images server lists.
  - Choosing an image model saves it on that server. The top bar's settings button opens the image settings in Image mode.
  - Image mode sends prompts (and attached photos, as edits) to the selected image server and model.
  - **Negative prompt:** it belongs to one prompt, like the prompt itself, so it isn't a saved setting. In Image mode on a stable-diffusion.cpp server, an **Exclude** chip in the message bar reveals a negative-prompt field. It's sent with that prompt, then cleared.
    - Regenerating or retrying an image keeps the negative prompt it was made with.
    - The image caption shows it ("excluding “blurry”").
  - One conversation can mix chat replies and image replies.
- **Migration:** a server that already ran image jobs, or had a model marked "Generates images", becomes an image server and keeps that model. The per-model overrides are removed.

FR-001, User Story 1 scenarios 1, 2 and 9, and SC-001 are revised accordingly. Where the text below says "image model in the picker", read "image mode with an image server".

## Context

The app talks to OpenAI-compatible servers over `/v1/chat/completions` and only renders text (plus images the user attached).
Image generation models such as Qwen-Image-2.1 can't be served by llama.cpp. They run on stable-diffusion.cpp's
`sd-server`, which exposes an OpenAI-style images API (`/v1/images/generations`, `/v1/images/edits`) and its own
async job API. A generation takes from tens of seconds to many minutes, returns megabytes of image data, and
doesn't stream.

Decisions already made:

- **Interaction model:** image models appear in the existing model picker. In an image conversation each user message is a prompt and the assistant reply is the image(s).
- **Primary server:** a standalone `sd-server` configured as its own server profile. Other OpenAI-images-compatible servers should work for the core text-to-image flow, but aren't the design target.
- **v1 scope:** text-to-image, image editing, advanced parameters, completion after the app is closed, and live step progress.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Generate an image from a prompt (Priority: P1)

The user adds their sd-server as a server profile, picks its image model in the chat model picker and types a
prompt. The assistant reply is the generated image, shown inline with its key settings. Tapping it opens it
full screen, and the user can save it to the gallery or share it. Regenerating produces a new variation as a
branch, the same way regenerating a chat reply does.

**Why this priority**: This is the whole value of the feature. Every other story builds on it.

**Independent Test**: Configure an sd-server profile, select its model, send a prompt, and verify an image
appears, persists after restarting the app, and can be saved and shared.

**Acceptance Scenarios**:

1. **Given** an sd-server profile is configured, **When** the user opens the model picker, **Then** the server's model is listed and marked as an image model.
2. **Given** a model the app didn't recognise as an image model, **When** the user marks it "Generates images" in the model picker, **Then** it's treated as an image model from then on, for that server.
3. **Given** an image model is selected, **When** the user sends "a red fox in snow, watercolor", **Then** a pending reply appears immediately with an elapsed timer and a Stop action, and when the server finishes, the reply shows the image and a caption with size, steps (when known), seed (when known) and duration.
4. **Given** a generated image, **When** the user taps it, **Then** it opens full screen with pinch-to-zoom, and Save and Share actions.
5. **Given** a generated image, **When** the user taps Save, **Then** it's written to the device gallery (Pictures/Pocket LLM) and a snackbar confirms it.
6. **Given** a generated image, **When** the user taps Regenerate, **Then** a new sibling reply is generated from the same prompt with a new seed, and the branch navigator moves between the variations.
7. **Given** a conversation with generated images, **When** the app is restarted, **Then** all images still display. **When** the conversation is deleted, **Then** its image files are removed from storage.
8. **Given** an image model is selected, **When** the server returns an error (unreachable, 4xx/5xx, malformed body), **Then** the reply shows a specific error with a Retry action, and the error is logged.
9. **Given** an image conversation, **When** the user switches the picker to a chat model and asks about the conversation, **Then** earlier image replies are sent to the chat model as text placeholders that include the prompt, not as raw image data.

---

### User Story 2 - Generation survives leaving or closing the app (Priority: P2)

Image generation can take minutes. The user starts one, switches apps or locks the phone, and the image
still arrives. A notification shows it's in progress and announces the result.

**Why this priority**: Without this, a multi-minute generation is lost whenever Android reclaims the app, and
the user has to babysit the screen.

**Independent Test**: Start a generation, swipe the app away from recents, and verify the notification stays,
the image is saved, and it's shown when the conversation is reopened.

**Acceptance Scenarios**:

1. **Given** a generation in progress, **When** the user leaves the app, **Then** an ongoing notification "Generating image…" shows elapsed time and (when available) step progress, with a Cancel action.
2. **Given** a generation in progress, **When** the app's process is killed, **Then** the generation still completes, the image is stored in the conversation, and a "Image ready" notification opens that conversation when tapped.
3. **Given** a generation in progress, **When** the user taps Cancel (in the notification or in the chat), **Then** the app stops waiting and the pending reply shows "Cancelled". If the job was still queued on the server, it's cancelled there. If it had already started, the reply notes that the server will finish the current image in the background, because sd-server can't interrupt a running job.
4. **Given** generations requested in two conversations, **When** the server processes one at a time, **Then** the second shows "Queued" until it starts, and neither fails due to the other.
5. **Given** the device restarts during a generation, **When** the app is next opened, **Then** the pending reply either resumes (if the server still has the job) or shows a clear "Interrupted" error with Retry. It never stays "pending" forever.

---

### User Story 3 - See live generation progress (Priority: P3)

While an image is generating, the user sees what the server is doing ("Loading model", "Step 5 of 8") and a
progress bar, not just a spinner.

**Why this priority**: Long waits are tolerable when progress is visible. It also separates "the server is
slow" from "the server is stuck".

**Independent Test**: Against sd-server, start an 8-step generation and verify the reply and the notification
advance through the steps.

**Acceptance Scenarios**:

1. **Given** a server that reports job progress, **When** a generation runs, **Then** the pending reply shows the current phase ("Queued (position 2)", "Generating") and "Step N of M" with a determinate progress bar, updated at least every few seconds.
4. **Given** a server that provides preview frames, **When** a generation runs, **Then** the pending reply shows the latest low-cost preview image under the progress bar, and the user can turn live previews off in settings.
2. **Given** a server that reports no progress (plain OpenAI images endpoint), **When** a generation runs, **Then** the pending reply shows an indeterminate indicator with elapsed time.
3. **Given** progress is shown, **When** the user leaves the chat and returns, **Then** the current progress is shown immediately, without restarting from zero.

---

### User Story 4 - Tune generation parameters (Priority: P4)

The user can set image size or aspect ratio, number of images, steps, guidance (CFG), seed, sampler and a
negative prompt for an image conversation, and can reuse a previous image's seed.

**Why this priority**: The defaults are often fine (e.g. a Turbo model at 8 steps), but serious use needs
control over size, count and reproducibility.

**Independent Test**: Set 768×1344, 2 images, 8 steps and seed 42, generate, and verify the request carries
those values and the captions report them. Generating again with the same seed and prompt gives the same image.

**Acceptance Scenarios**:

1. **Given** an image conversation, **When** the user opens conversation settings, **Then** image parameters appear instead of chat parameters (temperature etc.): size presets plus custom W×H, count (1–4), steps, CFG, seed (random by default), sampler, negative prompt.
2. **Given** parameters are set, **When** a prompt is sent, **Then** the request includes them, and fields left empty are omitted so server defaults apply.
3. **Given** a generated image, **When** the user chooses "Reuse seed" from its menu, **Then** the conversation's seed is set to that image's seed.
4. **Given** count = 2, **When** generation completes, **Then** the reply shows both images in a grid, each individually viewable, savable and shareable.
5. **Given** a parameter the server rejects (e.g. an unsupported size), **When** generation fails, **Then** the server's error message is shown verbatim alongside the app's explanation.

---

### User Story 5 - Edit an image with a prompt (Priority: P5)

The user attaches a photo (or taps Edit on a generated image) and describes a change ("make it night time"). The
reply is the edited image.

**Why this priority**: Qwen-Image-2.1 is strong at editing, and iterating on a result is the natural next step
after generating one. It depends on Stories 1 and 4.

**Independent Test**: Attach a photo with an image model selected, send "turn the sky purple", and verify the
reply is an edited version of the photo.

**Acceptance Scenarios**:

1. **Given** an image model is selected, **When** the user attaches one or more images and sends a prompt, **Then** the app calls the edit endpoint with those images and the reply is the edited image.
2. **Given** a generated image, **When** the user taps Edit, **Then** that image is attached to the input with the cursor ready for an instruction.
3. **Given** the server can't edit (e.g. it was started without the vision weights and returns an error), **When** an edit is sent, **Then** the error explains that the server needs edit support, and it's logged.
4. **Given** an edit request, **When** the source image is larger than the server's maximum, **Then** the app downsizes it before upload (keeping the aspect ratio), and the request still succeeds.

### Edge Cases

- **Huge payloads**: a 2048² PNG is about 4–8 MB and n=4 multiplies that. Images must never be stored in database rows (Android's cursor window is 2 MB) and must be decoded off the main thread.
- **Timeouts**: the server's chat timeout (default 120 s) is far too short. Image jobs need their own long timeout, and the async API must be used where available, so that reverse proxies with ~100 s limits don't break generation.
- **No automatic retries**: generation is expensive and not idempotent. The existing retry-on-502/503/504 behaviour must not be applied to image requests.
- **Non-image models**: chat models selected by mistake on an image server, or image models on a chat server, produce an understandable error, not a raw stack trace or JSON blob.
- **Title generation**: image conversations must not call chat completions on the image model to generate a title. The title comes from the first prompt.
- **Deleting**: deleting a message, branch or conversation removes its image files. Image files that no message references are cleaned up.
- **Storage full**: when saving the result fails, the job fails with a clear error and no partial file is left behind.
- **Server restart mid-job**: polling gets a 404 or connection errors. Retry with backoff up to a limit, then fail with "Interrupted" and offer Retry.
- **Result expiry**: sd-server purges finished jobs about 10 minutes after they complete (410 Gone). A job recovered after a longer outage fails with "Interrupted", not as a server error.
- **Fixed default seed**: sd-server defaults to seed 42 unless started with `-s -1`, so identical prompts would give identical images. The app MUST send an explicit seed on every request (random unless the user fixes one) and record it.
- **No model loaded**: sd-server returns an empty model list or 503 `model_not_loaded`/`model_loading`. Show "The server has no model loaded" or "The server is loading a model", not a generic error.
- **Prompt-embedded arguments**: on sd-server's OpenAI endpoints, native parameters travel inside the prompt (`<sd_cpp_extra_args>`). That markup must never be shown to the user or stored as the prompt.
- **Server queue**: sd-server runs one job at a time. A second request may queue or be rejected. Either way the user sees "Queued" or a clear message.
- **Rotation and process death** while the full-screen viewer is open: no crash and no lost image.
- **TalkBack**: generated images are announced with their prompt. Progress is announced at phase changes, not every step.
- **Android 9 (minSdk 28)**: saving to the gallery needs storage permission below API 29. Request it at save time.

## Requirements *(mandatory)*

### Functional Requirements

**Model and server**

- **FR-001** *(amended)*: Each server profile MUST have a type, Chat or Image generation, which decides what it's used for. Adding a stable-diffusion.cpp server MUST pre-select Image generation by probing it. An image server that isn't stable-diffusion.cpp MUST be given the image model to use. Generating images MUST be an explicit composer mode, separate from the chat model picker.
- **FR-002**: Image server requests MUST use a separate, user-configurable image timeout (default 30 minutes per job) that doesn't change chat timeouts.
- **FR-003**: The system MUST send the server profile's API key (Bearer) on image requests when one is configured, the same as chat requests. sd-server itself has no authentication, so the setup guide MUST warn against exposing it beyond the LAN or VPN without an authenticating proxy.

**Generation**

- **FR-004**: With an image model selected, sending a message MUST create a pending assistant reply and start a generation job. With images attached, it MUST start an edit job instead.
- **FR-005**: Jobs MUST run in a background work system with a foreground notification, so they survive the app being backgrounded or its process being killed.
- **FR-006**: Job state (queued, running with progress, succeeded, failed with reason, cancelled) MUST be persisted, so it survives process death and the UI renders it on re-entry.
- **FR-007**: When the server supports async jobs with progress, the system MUST use them and report progress. Otherwise it MUST fall back to the synchronous OpenAI-style endpoint.
- **FR-008**: The user MUST be able to cancel a job from the chat or the notification. Cancellation MUST cancel the server job when the server supports it.
- **FR-009**: Image requests MUST NOT be retried automatically after the server accepts them. Transient polling errors MAY be retried with bounded backoff.

**Storage and display**

- **FR-010**: Generated images MUST be stored as files in app-private storage and referenced from the database by path, with metadata: width, height, seed, steps, CFG, sampler, model, prompt, negative prompt, duration.
- **FR-011**: Image files MUST be deleted when their message or conversation is deleted. An orphan sweep MUST remove unreferenced files.
- **FR-012**: The system MUST render generated images inline, with a caption, full-screen viewing (zoom), Save to gallery, Share, Edit, Reuse seed, and Regenerate (as a branch).
- **FR-013**: When the conversation is sent to a chat model, image replies MUST appear as text placeholders containing the prompt.
- **FR-014**: Image conversations MUST NOT trigger LLM title generation.

**Parameters**

- **FR-015**: Image parameters (size, count, steps, CFG, seed, sampler, negative prompt) MUST be configurable per conversation. Unset values MUST be omitted from requests.

**Errors**

- **FR-016**: Every failure path MUST log at ERROR with a stack trace and show a specific user-facing message with a Retry action where retrying makes sense (Constitution VIII).

### Key Entities

- **ImageModelClassification**: (serverId, modelId) → isImageModel, and whether it was auto-detected or set by the user.
- **ImageJob**: one generation or edit request.
  - Links to its conversation and its pending assistant message.
  - Records the server, model and kind (generate or edit).
  - Holds the request parameters, plus the source image paths for edits.
  - Tracks the remote job id (when async), status, phase, step and total steps, error, and timestamps.
- **GeneratedImage**: a stored image file.
  - Belongs to a message, with an index for its order in the reply.
  - Records the file path, width, height, seed and the actual parameters used.
- **ImageParameters**: per-conversation settings (size, count, steps, CFG, seed, sampler, negative prompt).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With an sd-server running Qwen-Image-2.1-Turbo, a user can go from selecting the model to seeing a generated image with no setup beyond adding the server profile (and at most one "Generates images" toggle on non-sd servers).
- **SC-002**: 100% of generations started with the app in the foreground complete and display, even when the app is backgrounded or its process killed mid-job (verified by a manual kill test).
- **SC-003**: Progress updates appear within 3 s of the server reporting a new step (async-capable servers).
- **SC-004**: Opening an image conversation with 20 generated 1024² images shows the first visible image within 500 ms on a mid-range device, with no jank (images are decoded asynchronously at display size).
- **SC-005**: Deleting a conversation frees 100% of its image storage (verified by the files test).
- **SC-006**: No image bytes are stored in SQLite (verified by schema and tests).

## Out of Scope (v1)

- On-device image generation.
- A chat LLM calling an image tool (a planned follow-up that reuses this feature's job and storage layers).
- Inpainting masks drawn in the app.
- LoRA selection.
- Video generation.
- Image servers that only speak non-OpenAI protocols (ComfyUI workflows, A1111 `/sdapi`), except sd-server's own async API.
- Migrating existing user-attached images out of the database. That's tracked separately: they're small JPEGs today.

## Assumptions

- sd-server is reachable on the LAN or over VPN, without a proxy that strips long-polling responses.
- One image server runs one model at a time. Model switching (if any) is server-side configuration.
- Users accept the Qwen Research License terms themselves. The app doesn't bundle or recommend model weights.
