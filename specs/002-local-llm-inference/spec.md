# Feature Specification: Local LLM Inference

**Feature Branch**: `002-local-llm-inference`
**Created**: 2026-02-07
**Status**: Draft
**Input**: User description: "Port the local LLM features from snacktrack to this app — on-device model management, download, import, and inference via llama.cpp."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run a Model Locally on Device (Priority: P1)

A user wants to chat with an LLM entirely on their device without needing any server. They open model management, download a model from the built-in registry, wait for the download to complete, select the model, and start chatting. Responses are generated on-device token-by-token using the device's CPU and optionally GPU.

**Why this priority**: This is the core value proposition of local inference — private, offline, server-free LLM chat. Without this, no other local LLM feature matters.

**Independent Test**: Can be fully tested by downloading a registry model, selecting it, sending a message, and verifying a streamed response is generated on-device without any server connection.

**Acceptance Scenarios**:

1. **Given** no local models are downloaded, **When** the user opens model management, **Then** available models from the built-in registry are listed with their name, parameter count, quantization, and download size.
2. **Given** a registry model is listed, **When** the user taps "Download", **Then** the model files download in the background with a progress indicator showing percentage and speed.
3. **Given** a model has finished downloading, **When** the user selects it as the active model, **Then** subsequent messages in any conversation are processed on-device using that model.
4. **Given** a local model is selected and the user sends a message, **When** inference begins, **Then** the response streams token-by-token into the chat, and the user can see inference progress (tokens generated, speed in tokens/sec).
5. **Given** inference is in progress, **When** the user taps the stop button, **Then** inference is cancelled and the partial response is preserved.
6. **Given** no network connection is available, **When** the user sends a message with a local model selected, **Then** inference proceeds normally since no server is needed.

---

### User Story 2 - Manage Downloaded and Imported Models (Priority: P1)

A user wants to manage their local models — see which are downloaded, delete ones they no longer need to free storage, and import custom GGUF model files they've obtained elsewhere.

**Why this priority**: Model management is essential for local inference — users need control over which models consume device storage.

**Independent Test**: Can be tested by downloading a model, verifying it appears in the downloaded list, deleting it, and verifying storage is freed. Separately, importing a custom GGUF file and verifying it becomes available for inference.

**Acceptance Scenarios**:

1. **Given** one or more models are downloaded, **When** the user views model management, **Then** downloaded models are listed with their name, size on disk, and a delete option.
2. **Given** a downloaded model exists, **When** the user deletes it, **Then** the model files are removed from storage and the model moves back to the "available to download" state.
3. **Given** the user has a GGUF model file on their device, **When** they use the import function, **Then** they can select a model file and optionally a projector file, and the imported model appears in the downloaded models list.
4. **Given** a model import is attempted, **When** the selected file is not a valid GGUF file, **Then** the app displays an error and does not import the file.
5. **Given** the active model is deleted, **When** the user returns to chat, **Then** the app prompts them to select a different model or server.

---

### User Story 3 - Switch Between Local and Remote Inference (Priority: P1)

A user sometimes wants to use a local model for privacy or offline use, and other times connect to a remote server for access to larger models. They want to seamlessly switch between local and remote inference within the same app.

**Why this priority**: The app already supports remote servers (feature 001). Local inference must integrate cleanly with the existing server/model selection flow — not replace it.

**Independent Test**: Can be tested by selecting a local model, sending a message (on-device inference), then switching to a remote server, sending another message (server inference), and verifying both work within the same conversation.

**Acceptance Scenarios**:

1. **Given** both a remote server and a local model are configured, **When** the user opens the model selector, **Then** both local models and remote server models appear as selectable options.
2. **Given** a remote model is selected, **When** the user switches to a local model mid-conversation, **Then** subsequent messages are processed on-device and the conversation history is preserved.
3. **Given** a local model is selected, **When** the user switches to a remote server model, **Then** subsequent messages are sent to the remote server and the conversation history is preserved.
4. **Given** the user is in a conversation, **When** they view message details, **Then** each message indicates whether it was generated locally or by a remote server.

---

### User Story 4 - Resume Interrupted Downloads (Priority: P2)

A user starts downloading a large model (1-3 GB), but the download is interrupted (network loss, app closed, device sleep). They want to resume from where it left off instead of starting over.

**Why this priority**: Model files are large and re-downloading from scratch wastes time and data. Resume support is critical for a good download experience, but basic download works without it.

**Independent Test**: Can be tested by starting a download, killing the app mid-download, reopening, and verifying the download resumes from the interrupted point.

**Acceptance Scenarios**:

1. **Given** a download was interrupted, **When** the user returns to model management, **Then** the partially downloaded model shows a "Resume" option with progress so far.
2. **Given** a partial download exists, **When** the user taps "Resume", **Then** the download continues from the last downloaded byte.
3. **Given** a download is in progress, **When** the user taps "Cancel", **Then** the download stops and the partial file is retained for potential resume.
4. **Given** a download is started on cellular, **When** the download begins, **Then** the user is warned about cellular data usage before proceeding.

---

### User Story 5 - Configure GPU Acceleration (Priority: P2)

A user with a device that has Vulkan GPU support wants to offload model computation to the GPU for faster inference. They want a simple control to adjust how much of the model runs on GPU vs CPU.

**Why this priority**: GPU acceleration significantly improves inference speed but CPU-only inference is functional. This is an optimization, not a requirement.

**Independent Test**: Can be tested by adjusting the GPU offload slider, running inference, and verifying that performance metrics (tokens/sec) change accordingly.

**Acceptance Scenarios**:

1. **Given** the device supports Vulkan GPU acceleration, **When** the user opens local model settings, **Then** a GPU offload control is available (0-100% slider).
2. **Given** the GPU offload is set to a non-zero value, **When** inference runs, **Then** model layers are partially offloaded to the GPU and performance metrics reflect the acceleration.
3. **Given** the device does not support Vulkan, **When** the user opens local model settings, **Then** the GPU offload control is either hidden or disabled with an explanation.
4. **Given** the GPU offload setting is changed, **When** the next inference runs, **Then** the model is reloaded with the updated GPU layer allocation.

---

### Edge Cases

- What happens when the device runs out of storage during a model download? The app should detect insufficient storage before starting, and halt gracefully if storage fills mid-download with a clear error message.
- What happens when the device has insufficient RAM to load a model? The app should check available RAM against model requirements and warn the user before loading. If loading fails, the error is displayed and the model is unloaded.
- What happens when inference crashes due to a native (C/C++) error? The app should catch the crash via signal handlers, recover gracefully, notify the user, and allow them to retry or select a different model.
- What happens when the user switches models while inference is in progress? The in-progress inference should be cancelled before switching to the new model.
- What happens when the user sends a message before the model has finished loading? The app should show a loading indicator and queue or block the send until the model is ready.
- What happens when a downloaded model file becomes corrupted? The app should detect corruption (via GGUF magic number validation) and offer to re-download.
- What happens when the user imports a GGUF file that requires a projector but doesn't provide one? The app should inform the user that a projector file is needed for vision capabilities but allow text-only use.
- What happens when the app is backgrounded during inference? Inference should continue if the system allows, but the app should handle being killed gracefully (partial response preserved).

## Requirements *(mandatory)*

### Functional Requirements

#### Model Registry & Download
- **FR-200**: System MUST provide a built-in registry of downloadable models with metadata: name, parameter count, quantization level, file sizes, and minimum RAM requirement.
- **FR-201**: System MUST download model files (model + projector GGUF files) in the background with a visible progress indicator showing percentage and download speed.
- **FR-202**: System MUST support resuming interrupted downloads using HTTP Range headers.
- **FR-203**: System MUST validate downloaded files by checking the GGUF magic number (`0x46554747`).
- **FR-204**: System MUST check available device storage before starting a download and warn if insufficient (with a 100 MB buffer).
- **FR-205**: System MUST warn the user before downloading on a cellular connection.
- **FR-206**: System MUST retry failed downloads with exponential backoff (max 3 retries).
- **FR-207**: System MUST allow users to cancel an in-progress download while retaining the partial file for resume.
- **FR-208**: System MUST display a persistent notification during background model downloads with progress.

#### Model Import & Management
- **FR-210**: System MUST allow users to import custom GGUF model files from device storage via a file picker.
- **FR-211**: System MUST support a two-step import flow: select model file, then optionally select a projector file.
- **FR-212**: System MUST validate imported files by checking the GGUF magic number before accepting them.
- **FR-213**: System MUST allow users to delete downloaded or imported models, freeing the associated storage.
- **FR-214**: System MUST display device RAM and warn if it is below a model's minimum RAM requirement.
- **FR-215**: System MUST store model metadata persistently (download status, file paths, source URLs) so it survives app restarts.
- **FR-216**: System MUST store downloaded and imported model files in app-private external storage, automatically cleaned on uninstall and not visible to other apps.

#### On-Device Inference
- **FR-220**: System MUST perform inference entirely on-device using the llama.cpp engine via native (JNI) bindings.
- **FR-221**: System MUST support text-to-text inference (standard chat) and image+text-to-text inference (vision models with projector files).
- **FR-222**: System MUST stream inference output token-by-token into the existing chat UI, consistent with the remote streaming experience.
- **FR-223**: System MUST allow the user to cancel in-progress inference.
- **FR-224**: System MUST report inference performance metrics: tokens generated, generation speed (tokens/sec), and current phase (model loading, prompt evaluation, generating).
- **FR-225**: System MUST read model-recommended sampling parameters (temperature, top-k, top-p, min-p, repeat penalty) from GGUF metadata and use them as defaults.
- **FR-226**: System MUST apply the model's embedded chat template when formatting prompts.
- **FR-227**: System MUST support configurable GPU layer offloading (0-100%) for devices with Vulkan support, falling back to CPU-only on unsupported devices.
- **FR-228**: System MUST automatically reload the model when GPU offload settings change.
- **FR-229**: System MUST handle native crashes (SIGSEGV/SIGBUS) gracefully via signal handlers, recovering the app to a usable state and notifying the user.
- **FR-230**: System MUST auto-detect and load available compute backends (CPU variants, Vulkan) from the application package.
- **FR-231**: System MUST allow the user to configure the context window size per local model, with a default of 2048 tokens.

#### Integration with Existing Chat
- **FR-240**: System MUST integrate local models into the existing model selector alongside remote server models.
- **FR-244**: System MUST provide access to local model management (download, delete, import) via the settings screen (Settings > Local Models). The model selector in chat handles quick model switching only.
- **FR-241**: System MUST allow switching between local and remote models mid-conversation, with the conversation history preserved.
- **FR-242**: System MUST record on each message whether it was generated locally or by a remote server.
- **FR-243**: System MUST apply existing chat features (system prompts, generation parameter overrides, markdown rendering, message actions, tool calling) to locally-generated responses identically to remote responses. The inference provider is a transport layer; all chat features are provider-agnostic.

### Key Entities

- **Local Model**: A model available for on-device inference. Attributes: ID, display name, parameter count, quantization, model file name, projector file name, model file size, projector file size, download status (not downloaded / downloading / complete / failed), downloaded bytes, source URL, projector source URL, is-imported flag, minimum RAM requirement.
- **Model Registry Entry**: A predefined model available for download. Attributes: model ID, name, download URLs (model + projector), file sizes, parameter count, quantization, minimum RAM. The registry is bundled with the app.
- **Compute Backend**: A hardware acceleration library available on the device. Attributes: backend type (CPU, Vulkan), library path. Discovered at app startup from the application package.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-100**: Users can download a model and get their first locally-generated response within 5 minutes (excluding download time on slow connections).
- **SC-101**: On-device inference produces the first token within 10 seconds of the user sending a message (for models within the device's RAM capacity).
- **SC-102**: Token generation speed reaches at least 3 tokens/sec on mid-range devices (8 GB RAM, Snapdragon 7-series or equivalent) with a 2-4B parameter Q4 model.
- **SC-103**: The app recovers to a usable state within 3 seconds after a native inference crash, without losing conversation history.
- **SC-104**: Model downloads can be resumed after interruption with no more than 1 MB of re-downloaded data.
- **SC-105**: Users can switch between local and remote models within the same conversation with no loss of message history.
- **SC-106**: Imported custom GGUF models work identically to registry-downloaded models for inference.
- **SC-107**: The local model management UI is navigable and operable via TalkBack with no unlabeled elements.

## Clarifications

### Session 2026-02-07

- Q: Should local models support tool calling (calculator, web fetch) the same way remote models do? → A: Yes, enable tool calling identically to remote models. The inference provider is a transport layer; all chat features are provider-agnostic.
- Q: How should the user navigate to local model management? → A: Entry in the existing settings screen (Settings > Local Models). The model selector in chat handles quick switching; management (download, delete, import) belongs in settings.
- Q: Should downloaded model files be stored in app-private or shared external storage? → A: App-private external storage (auto-deleted on uninstall, not visible to other apps). Users who want to preserve models across installs can use the import flow.
- Q: Should the 2048-token context window for local models be fixed or user-configurable? → A: User-configurable per model with 2048 default. The snacktrack value was tuned for single-shot extraction; multi-turn chat benefits from larger context.

## Assumptions

- The llama.cpp engine and JNI bridge from the snacktrack project are portable to pocket-llm with minimal adaptation (primarily rewiring package names and removing snacktrack-specific extraction logic).
- GGUF is the only supported model format. Other formats (ONNX, TFLite, etc.) are out of scope.
- The built-in model registry is hardcoded in the app; a dynamic registry service is out of scope.
- Vision model support (image+text inference) is limited to models that include a projector file in GGUF format compatible with llama.cpp's mtmd library.
- Grammar-constrained output (JSON schema enforcement) from snacktrack is out of scope for this feature — pocket-llm uses free-form text generation for chat.
- The existing chat infrastructure (Room database, message tree, conversation management, markdown rendering, tool calling) from feature 001 is reused as-is; this feature only adds a new inference provider.
- Context window size for local models defaults to 2048 tokens but is user-configurable per model.
- Thread count for inference is auto-calculated based on device CPU cores (min 2, max 6, reserving 2 cores for OS headroom).
