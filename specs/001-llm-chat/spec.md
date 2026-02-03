# Feature Specification: LLM Chat Client

**Feature Branch**: `001-llm-chat`
**Created**: 2026-02-02
**Status**: Draft
**Input**: User description: "Build an LLM chat app with all expected features. Focus on self-hosted/local LLMs via OpenAI-compatible API. Must support: server configuration, model listing via /models endpoint, streaming chat, tool calls, compaction, and all features users expect in an LLM chat client."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect to LLM Server and Start Chatting (Priority: P1)

A user opens the app for the first time and wants to connect to their local LLM server (e.g., Ollama, LM Studio, vLLM, or any OpenAI-compatible endpoint). They enter the server URL, optionally an API key, and the app fetches available models. The user selects a model and begins a conversation by typing a message. The response streams in token-by-token in real time.

**Why this priority**: This is the core value proposition — without server connection, model selection, and streaming chat, nothing else matters.

**Independent Test**: Can be fully tested by configuring a server URL, selecting a model, sending a message, and verifying a streamed response appears. Delivers the fundamental chat experience.

**Acceptance Scenarios**:

1. **Given** the app is freshly installed with no servers configured, **When** the user launches the app, **Then** they are redirected to the server configuration screen with a welcome message guiding them to add their first server.
2. **Given** the user is on the server configuration screen, **When** they enter a valid server URL, **Then** the app connects and fetches the list of available models.
3. **Given** a server is configured and models are listed, **When** the user selects a model and types a message, **Then** the response streams in progressively (token-by-token) and displays in the chat.
4. **Given** a server is configured, **When** the user enters an invalid URL or the server is unreachable, **Then** the app displays a clear error message indicating the connection failure.
5. **Given** a server requires an API key, **When** the user provides the key in settings, **Then** the app authenticates successfully and lists models.
6. **Given** a conversation is in progress with one server/model, **When** the user switches to a different server or model, **Then** subsequent messages use the new server/model while the full conversation history is preserved.

---

### User Story 2 - Manage Conversations (Priority: P1)

A user wants to have multiple conversations, switch between them, rename them, and delete old ones. Each conversation maintains its own message history and model context. The user expects conversations to persist across app restarts.

**Why this priority**: Conversation management is essential for any chat app — users need to organize and revisit past chats.

**Independent Test**: Can be tested by creating multiple conversations, switching between them, renaming one, deleting another, closing the app, and verifying conversations persist on reopen.

**Acceptance Scenarios**:

1. **Given** the user is in a conversation, **When** they tap "New Chat", **Then** a new empty conversation is created and becomes active.
2. **Given** multiple conversations exist, **When** the user opens the conversation list, **Then** all conversations are displayed with their titles and last-message previews, ordered by most recent activity.
3. **Given** a conversation exists, **When** the user renames it, **Then** the new name persists and displays in the conversation list.
4. **Given** a conversation exists, **When** the user deletes it, **Then** it is removed from the list and its message history is cleared.
5. **Given** the user has active conversations, **When** the app is closed and reopened, **Then** all conversations and their messages are preserved.

---

### User Story 3 - Configure Multiple Servers (Priority: P2)

A user runs different LLM servers for different purposes (e.g., Ollama for local models, a remote OpenAI-compatible server for larger models). They want to configure multiple server profiles and switch between them.

**Why this priority**: Multi-server support expands the app's utility significantly but a single-server experience is viable on its own.

**Independent Test**: Can be tested by adding two server configurations, switching between them, and verifying each lists its own models.

**Acceptance Scenarios**:

1. **Given** the user is in server settings, **When** they add a new server profile with a name, URL, and optional API key, **Then** the profile is saved and appears in the server list.
2. **Given** multiple servers are configured, **When** the user switches the active server, **Then** the model list updates to show models from the newly selected server.
3. **Given** a server profile exists, **When** the user edits its URL or API key, **Then** the changes are saved and take effect on next connection.
4. **Given** a server profile exists, **When** the user deletes it, **Then** it is removed and cannot be selected.
5. **Given** a server profile is deleted, **When** conversations previously used that server, **Then** those conversations remain fully accessible and the user can select a different server to continue.

---

### User Story 4 - System Prompts and Chat Parameters (Priority: P2)

A user wants to customize how the LLM behaves by setting a system prompt (persona/instructions) and adjusting generation parameters like temperature, max tokens, and top-p. These settings can be configured globally as defaults or per-conversation.

**Why this priority**: Customizing model behavior is a core power-user feature that significantly improves the chat experience.

**Independent Test**: Can be tested by setting a system prompt and temperature, sending a message, and verifying the model response reflects the configured behavior.

**Acceptance Scenarios**:

1. **Given** the user is configuring a conversation, **When** they set a system prompt, **Then** subsequent messages to the LLM include that system prompt.
2. **Given** default parameters are set globally, **When** a new conversation is created, **Then** it inherits the global defaults.
3. **Given** a conversation has custom parameters, **When** the user adjusts temperature or max tokens, **Then** subsequent responses reflect the new parameters.
4. **Given** per-conversation parameters differ from global defaults, **When** the user views conversation settings, **Then** overridden values are clearly indicated.

---

### User Story 5 - Context Compaction (Priority: P2)

As a conversation grows long, the user wants the app to manage context window limits intelligently. The app should support compaction — summarizing earlier messages to free up context space while preserving the essential meaning of the conversation.

**Why this priority**: Without compaction, long conversations will hit token limits and break. This is essential for sustained use but not needed for initial short chats.

**Independent Test**: Can be tested by having a long conversation that approaches the context limit, triggering compaction, and verifying the conversation continues coherently with a summarized history.

**Acceptance Scenarios**:

1. **Given** a conversation approaches the model's context window limit, **When** the user continues chatting, **Then** the app automatically compacts earlier messages into a summary.
2. **Given** compaction has occurred, **When** the user reviews the conversation, **Then** a visual indicator shows where compaction happened.
3. **Given** the user prefers manual control, **When** they trigger compaction manually from the conversation menu, **Then** the conversation is compacted on demand.
4. **Given** compaction is performed, **When** the user continues chatting, **Then** the LLM's responses remain contextually coherent with the full conversation history.

---

### User Story 6 - Markdown and Code Rendering (Priority: P2)

A user expects LLM responses to render rich content properly: markdown formatting (bold, italic, lists, headings), code blocks with syntax highlighting, and inline code. Code blocks should have a copy button.

**Why this priority**: LLMs produce markdown-heavy responses, especially with code. Without proper rendering, the app feels broken.

**Independent Test**: Can be tested by prompting the LLM to produce a response with headings, bold text, a bullet list, and a code block, then verifying each renders correctly with syntax highlighting and a copy button on code blocks.

**Acceptance Scenarios**:

1. **Given** the LLM returns a response with markdown, **When** the response is displayed, **Then** headings, bold, italic, lists, and links render correctly.
2. **Given** the LLM returns a code block with a language tag, **When** displayed, **Then** the code block has syntax highlighting appropriate to the language.
3. **Given** a code block is displayed, **When** the user taps the copy button, **Then** the code content is copied to the clipboard.
4. **Given** the response contains inline code, **When** displayed, **Then** inline code is visually distinct from surrounding text.

---

### User Story 7 - Tool Calls / Function Calling (Priority: P3)

A user wants to leverage models that support tool calling (function calling). The app provides built-in tool handlers (e.g., calculator, web fetch) that execute locally on the device. When a model returns a tool call, the app displays it, requests user approval, executes the tool locally, and feeds the result back to the model.

**Why this priority**: Tool calling is an advanced feature that adds significant power but is not required for basic chat functionality.

**Independent Test**: Can be tested by enabling a built-in tool, sending a prompt that triggers a tool call, approving execution, and confirming the model incorporates the result.

**Acceptance Scenarios**:

1. **Given** a model supports tool calling, **When** the model response includes a tool call, **Then** the app displays the tool call name and arguments clearly in the chat.
2. **Given** a tool call is displayed, **When** the user approves execution, **Then** the built-in handler runs locally and the result is sent back to the model.
3. **Given** the user has enabled built-in tools for a conversation, **When** starting a conversation, **Then** the enabled tool definitions are included in the API request.
4. **Given** a tool call fails or times out, **When** the error occurs, **Then** the user is notified and can retry or skip the tool call.
5. **Given** a tool call is displayed, **When** the user declines execution, **Then** the tool call is skipped and the model is informed.

---

### User Story 8 - Message Actions and Editing (Priority: P3)

A user wants to interact with individual messages: copy text, regenerate a response, edit a previously sent message (creating a branch), and delete messages.

**Why this priority**: These quality-of-life features improve usability but the app is functional without them.

**Independent Test**: Can be tested by long-pressing a message and verifying copy, regenerate, edit, and delete options work correctly.

**Acceptance Scenarios**:

1. **Given** an assistant response is displayed, **When** the user taps "Regenerate", **Then** a new branch is created with a fresh response, and the user can navigate back to the original.
2. **Given** a user message exists in the conversation, **When** the user edits it, **Then** a new branch is created from that point with the edited message, preserving the original path.
3. **Given** a branch point exists in the conversation, **When** the user views it, **Then** navigation controls (e.g., prev/next arrows with "2 of 3" indicator) allow switching between branches.
4. **Given** any message in the chat, **When** the user copies it, **Then** the message text is placed on the clipboard.
5. **Given** a message exists, **When** the user deletes it, **Then** it is removed from the conversation display and history.

---

### User Story 9 - Search Conversations (Priority: P3)

A user wants to search across all conversations to find a specific message or topic. Search should match message content and conversation titles.

**Why this priority**: Search becomes important as the number of conversations grows but is not needed initially.

**Independent Test**: Can be tested by creating several conversations with distinct content, searching for a specific term, and verifying matching conversations and messages appear in results.

**Acceptance Scenarios**:

1. **Given** multiple conversations exist, **When** the user searches for a term, **Then** conversations containing that term in their title or messages are displayed.
2. **Given** search results are displayed, **When** the user taps a result, **Then** they are taken to the relevant conversation scrolled to the matching message.

---

### User Story 10 - Export and Share (Priority: P3)

A user wants to export a conversation as text or markdown, or share a specific message or the full conversation via the Android share sheet.

**Why this priority**: Export and sharing are convenience features that add value but are not core to the chat experience.

**Independent Test**: Can be tested by exporting a conversation and verifying the output file contains the full message history in the expected format.

**Acceptance Scenarios**:

1. **Given** a conversation exists, **When** the user selects "Export", **Then** the conversation is saved as a markdown or plain text file.
2. **Given** a message or conversation is selected, **When** the user taps "Share", **Then** the Android share sheet opens with the content ready to share.

---

### User Story 11 - Appearance and Theme Settings (Priority: P3)

A user wants to customize the app's appearance: light/dark/system theme, font size for messages, and optional AMOLED dark mode.

**Why this priority**: Visual customization improves comfort but the app's default Material 3 theme is already functional.

**Independent Test**: Can be tested by toggling between light, dark, and system themes and verifying the UI updates accordingly.

**Acceptance Scenarios**:

1. **Given** the user is in appearance settings, **When** they select dark mode, **Then** the entire app switches to a dark color scheme.
2. **Given** the user adjusts message font size, **When** they return to a conversation, **Then** messages display at the new size.

---

### User Story 12 - Token Usage Display (Priority: P2)

A user wants to see how many tokens each response consumed and how much of the model's context window is in use. This helps them understand costs (for paid APIs) and manage context limits proactively.

**Why this priority**: Token awareness is fundamental to using LLMs effectively — users need visibility into context consumption to avoid surprises.

**Independent Test**: Can be tested by sending a message and verifying that prompt tokens, completion tokens, and total tokens are displayed, along with a context usage indicator.

**Acceptance Scenarios**:

1. **Given** an assistant response has been received, **When** the user views the message, **Then** token counts (prompt, completion, total) are displayed for that exchange.
2. **Given** a conversation is active, **When** the user views the chat, **Then** a context usage indicator shows tokens used relative to the model's context window size.
3. **Given** context usage is approaching the compaction threshold, **When** the indicator updates, **Then** it visually warns the user (e.g., color change from green to amber to red).

---

### User Story 13 - Thinking / Reasoning Display (Priority: P3)

A user is chatting with a reasoning model (e.g., DeepSeek R1, QwQ) that produces thinking/reasoning content before its final answer. The app should detect and display this reasoning in a collapsible section so users can inspect the model's chain of thought without cluttering the main response.

**Why this priority**: Reasoning models are increasingly common. Without this, thinking blocks appear as raw text mixed into responses, degrading readability.

**Independent Test**: Can be tested by sending a prompt to a reasoning model and verifying that the thinking content appears in a collapsible section separate from the final answer.

**Acceptance Scenarios**:

1. **Given** a model returns a response with `<think>` blocks or a `reasoning_content` field, **When** the response is displayed, **Then** the reasoning content appears in a collapsible section (collapsed by default).
2. **Given** a thinking section is displayed, **When** the user expands it, **Then** the full reasoning chain is visible.
3. **Given** a model does not produce thinking content, **When** the response is displayed, **Then** no thinking section appears.

---

### User Story 14 - Image / Multimodal Input (Priority: P3)

A user wants to send images to vision-capable models. They can attach images from the gallery, camera, or clipboard, and the images are sent inline with their message using the OpenAI vision API format.

**Why this priority**: Vision models are widely available on local servers (LLaVA, etc.) and image input is an increasingly expected capability. However, the core text chat experience is viable without it.

**Independent Test**: Can be tested by attaching an image to a message, sending it to a vision model, and verifying the model responds with a description or analysis of the image.

**Acceptance Scenarios**:

1. **Given** the user is composing a message, **When** they tap the attach button, **Then** they can select an image from the gallery, take a photo, or paste from the clipboard.
2. **Given** an image is attached, **When** the message is sent, **Then** the image is included in the API request in the OpenAI vision format and displayed inline in the conversation.
3. **Given** a model does not support vision, **When** the user attempts to attach an image, **Then** the app warns that the selected model may not support image input.
4. **Given** an image is displayed in the conversation, **When** the user taps it, **Then** a full-screen preview is shown.

---

### User Story 15 - Accessibility (Priority: P1)

All users, including those using assistive technologies, must be able to use the app effectively. The app must support screen readers, meet minimum touch target sizes, and respect system accessibility settings.

**Why this priority**: Accessibility is a baseline requirement, not a feature. It must be considered from the start, not bolted on later.

**Independent Test**: Can be tested by enabling TalkBack, navigating every screen, and verifying all interactive elements are announced with meaningful descriptions and all actions are reachable.

**Acceptance Scenarios**:

1. **Given** TalkBack is enabled, **When** the user navigates the app, **Then** all interactive elements have meaningful content descriptions and logical reading order.
2. **Given** any interactive element in the app, **When** measured, **Then** it meets the minimum 48dp touch target size.
3. **Given** the system high-contrast setting is enabled, **When** the app is viewed, **Then** text and controls remain legible and usable.
4. **Given** the user has set a large system font size, **When** the app is viewed, **Then** text scales appropriately without layout breakage.

---

### User Story 16 - Parameter Presets (Priority: P3)

A user wants quick shortcuts for common parameter combinations rather than manually adjusting temperature, top-p, and max tokens each time. The app provides built-in presets (e.g., "Creative", "Precise", "Code") and allows users to create custom presets.

**Why this priority**: Presets improve UX for users who don't want to understand individual parameters but still want to influence model behavior.

**Independent Test**: Can be tested by selecting a preset, verifying the parameters update accordingly, and then creating a custom preset and reapplying it.

**Acceptance Scenarios**:

1. **Given** the user is adjusting generation parameters, **When** they select a built-in preset (e.g., "Creative"), **Then** the temperature, top-p, and other parameters update to the preset values.
2. **Given** the user has configured custom parameters, **When** they save them as a named preset, **Then** the preset appears in the preset list for future use.
3. **Given** a preset is selected, **When** the user views the parameter details, **Then** the individual parameter values are visible and can be further adjusted.

---

### Edge Cases

- What happens when the server disconnects mid-stream? The app should display the partial response received so far, show an error indicator, and allow the user to retry.
- What happens when the model returns an empty response? The app should display a "No response generated" message and allow retry.
- What happens when the context window is exceeded and compaction fails? The app should notify the user and suggest starting a new conversation or manually trimming messages.
- What happens when the user sends a message while a response is still streaming? The message should be queued or the user should be informed to wait.
- What happens when the device loses network connectivity? The app should detect this, pause/fail gracefully, and resume or retry when connectivity returns.
- What happens when the model list endpoint returns zero models? The app should display a clear message indicating no models are available on the server.
- What happens when a tool call references a tool that is not defined? The app should display the raw tool call and notify the user.
- What happens when the user pastes extremely long text into the input? The app should handle large inputs gracefully, potentially warning about token usage.
- What happens when the server returns a 429 (rate limited) response? The app should display the retry-after period and offer a retry button.
- What happens when a request times out due to slow local model inference? The app should show a timeout error and allow the user to retry with the same or adjusted timeout.
- What happens when a model returns both thinking content and tool calls in the same response? Both should be displayed in their respective UI elements.
- What happens when the user attaches an image but the model doesn't support vision? The app should warn the user before sending.

## Requirements *(mandatory)*

### Functional Requirements

#### Server & Connection
- **FR-001**: System MUST allow users to configure one or more LLM server connections with a name, base URL, and optional API key.
- **FR-002**: System MUST fetch and display available models from the server's `/v1/models` endpoint.
- **FR-003**: System MUST validate server connectivity when a connection is saved or selected.
- **FR-004**: System MUST support any OpenAI-compatible chat completions API (`/v1/chat/completions`).
- **FR-005**: System MUST store server configurations securely, with API keys encrypted at rest.
- **FR-006**: System MUST support a configurable request timeout per server (default 60 seconds) to accommodate slow local model inference.
- **FR-007**: System MUST redirect to the server configuration screen with a welcome message on first launch when no servers are configured.

#### Chat & Messaging
- **FR-010**: System MUST send user messages to the selected model and display responses via streaming (Server-Sent Events / chunked transfer).
- **FR-011**: System MUST display a typing/streaming indicator while a response is being generated.
- **FR-012**: System MUST allow users to stop a response mid-stream.
- **FR-013**: System MUST support system prompts configurable per-conversation and as global defaults.
- **FR-014**: System MUST support configurable generation parameters: temperature, max tokens, top-p, frequency penalty, presence penalty.
- **FR-014a**: System MUST allow users to switch server and model mid-conversation; subsequent messages use the newly selected server/model while preserving the full conversation history.
- **FR-015**: System MUST render assistant responses as rich markdown with syntax-highlighted code blocks.
- **FR-016**: System MUST provide a copy button on code blocks and support copying any message text.
- **FR-017**: System MUST display token usage per response (prompt tokens, completion tokens, total) and a running context usage indicator relative to the model's context window.
- **FR-018**: System MUST detect and display model thinking/reasoning content (e.g., `<think>` blocks, `reasoning_content` fields) in a collapsible section, collapsed by default.
- **FR-019**: System MUST support retrying failed requests with exponential backoff for 429 and 503 responses, displaying retry status to the user.

#### Conversation Management
- **FR-020**: System MUST support creating, renaming, and deleting conversations.
- **FR-021**: System MUST persist all conversations and messages locally across app restarts.
- **FR-022**: System MUST auto-generate conversation titles by initially truncating the first user message to 50 characters, then replacing with an LLM-generated title (max 6 words) once the first assistant response completes.
- **FR-023**: System MUST display conversations in a navigable list sorted by most recent activity.
- **FR-024**: System MUST support searching across conversation titles and message content.

#### Context & Compaction
- **FR-030**: System MUST track token usage relative to the selected model's context window size. Token counts are estimated using a character-based heuristic (chars / 4) when a tokenizer is unavailable.
- **FR-031**: System MUST trigger automatic context compaction when token usage reaches the configurable threshold (default 75%) of the model's context window size. Compaction summarizes all messages except the most recent exchange into a system-level summary, preserving conversation continuity.
- **FR-032**: System MUST support manual compaction triggered by the user.
- **FR-033**: System MUST visually indicate where compaction has occurred in a conversation.
- **FR-034**: System MUST use the LLM itself to generate compaction summaries.

#### Tool Calling
- **FR-040**: System MUST detect and display tool call requests from the model in the chat.
- **FR-041**: System MUST support the OpenAI-compatible tool calling format (function calling).
- **FR-042**: System MUST provide built-in tool handlers (e.g., calculator, web fetch) that execute locally on the device.
- **FR-043**: System MUST request user approval before executing any tool call.
- **FR-044**: System MUST send tool execution results back to the model to continue the response cycle.
- **FR-045**: System MUST handle tool call errors gracefully, allowing retry or skip.
- **FR-046**: System MUST allow users to configure which built-in tools are available per conversation.
- **FR-047**: System MUST handle multiple parallel tool calls within a single assistant response, presenting all calls for batch approval (single approve/decline for the group), executing each with a 30-second timeout, and returning all results before the model continues.

#### Message Actions
- **FR-050**: System MUST allow users to regenerate any assistant response.
- **FR-051**: System MUST allow users to edit a previously sent user message, creating a new branch while preserving the original conversation path.
- **FR-054**: System MUST allow users to navigate between conversation branches (e.g., via prev/next arrows at the branch point).
- **FR-055**: System MUST visually indicate branch points in the conversation.
- **FR-052**: System MUST allow users to delete individual messages from a conversation.
- **FR-053**: System MUST allow users to copy message content to the clipboard.

#### Export & Sharing
- **FR-060**: System MUST support exporting conversations as markdown or plain text files.
- **FR-061**: System MUST support sharing messages or conversations via the Android share sheet.

#### Image / Multimodal
- **FR-080**: System MUST allow users to attach images to messages from the device gallery, camera, or clipboard.
- **FR-081**: System MUST send attached images in the OpenAI vision API format (base64-encoded in the message content array), auto-resized to a configurable max dimension (default 1024px on the long side, images smaller than the threshold are preserved at original size) and compressed to JPEG quality 85% before encoding.
- **FR-082**: System MUST display attached and received images inline within the conversation.
- **FR-083**: System MUST warn the user if the selected model may not support image input.

#### Accessibility
- **FR-090**: System MUST provide meaningful content descriptions for all interactive elements for screen reader compatibility.
- **FR-091**: System MUST ensure all interactive elements meet the minimum 48dp touch target size.
- **FR-092**: System MUST respect system accessibility settings including high contrast and large font size.
- **FR-093**: System MUST maintain logical focus and reading order for assistive technology navigation.

#### Generation Parameter Presets
- **FR-100**: System MUST provide built-in parameter presets (e.g., "Creative", "Precise", "Code") that configure temperature, top-p, and other parameters as a group.
- **FR-101**: System MUST allow users to create, edit, and delete custom parameter presets.
- **FR-102**: System MUST allow presets to be applied per-conversation or as global defaults.

#### Settings & Appearance
- **FR-070**: System MUST support light, dark, and system-default theme modes.
- **FR-071**: System MUST support adjustable message font size.
- **FR-072**: System MUST support Material 3 dynamic color theming on compatible devices.

### Key Entities

- **Server**: Represents an LLM server connection. Attributes: name, base URL, API key (encrypted), connection status, request timeout. A user can have many servers.
- **Model**: Represents an LLM model available on a server. Attributes: model ID, display name, context window size. Fetched dynamically from the server.
- **Conversation**: A thread of messages between the user and an LLM. Attributes: title, creation date, last activity date, last-used server (loose reference), last-used model, system prompt, generation parameters. Conversations are not bound to a specific server — users can switch server or model mid-conversation. A user can have many conversations.
- **Message**: A single message within a conversation. Attributes: role (user/assistant/system/tool), content, timestamp, token count, tool call data (if applicable), parent message reference (for tree structure), server and model used for this message. Messages form a tree where branch points have multiple children. Each message records which server/model generated it, since these can change mid-conversation.
- **Tool Definition**: A tool the model can call. Attributes: name, description, parameter JSON schema. Tools are defined by the user and sent with API requests.
- **Compaction Summary**: A condensed representation of earlier messages in a conversation. Attributes: summary text, original message range, creation timestamp.
- **Parameter Preset**: A named set of generation parameters. Attributes: name, temperature, top-p, max tokens, frequency penalty, presence penalty, is-built-in flag. Built-in presets are read-only; user presets are editable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can go from app install to first streamed LLM response in under 2 minutes (assuming server is already running).
- **SC-002**: Streaming responses display the first token within 1 second of the server beginning to respond (no artificial buffering delay).
- **SC-003**: Users can manage 100+ conversations without noticeable performance degradation in the conversation list.
- **SC-004**: Conversations persist reliably across app restarts with zero data loss under normal operation.
- **SC-005**: Compaction allows conversations to continue indefinitely without hitting context window errors.
- **SC-006**: Markdown rendering correctly displays all common markdown elements including fenced code blocks with syntax highlighting.
- **SC-007**: The app functions correctly with at least 3 popular OpenAI-compatible servers (e.g., Ollama, LM Studio, vLLM/text-generation-inference).
- **SC-008**: Tool call round-trips (model request, tool execution, result, model continuation) complete without manual intervention beyond initial tool result input.
- **SC-009**: 90% of users can complete core tasks (configure server, start chat, manage conversations) without consulting documentation.
- **SC-010**: The app launches to a usable state within 2 seconds on mid-range Android devices.
- **SC-011**: All screens are fully navigable and operable using TalkBack with no unlabeled or unreachable interactive elements.
- **SC-012**: Token usage information is displayed for every assistant response without requiring user action.
- **SC-013**: Reasoning/thinking content from supported models is displayed separately from the main response and does not clutter the default view.

## Clarifications

### Session 2026-02-02

- Q: Who executes tool calls — the user manually, built-in handlers, an external service, or a hybrid? → A: App executes tools locally via built-in handlers (e.g., calculator, web fetch) with user approval before execution.
- Q: When editing a previous message, should the conversation replace the tail, tree-branch, or replace with undo? → A: Tree branching — the original path is preserved and the user can navigate between branches.
- Q: At what percentage of context window usage should automatic compaction trigger? → A: 75% of the model's context window size.
- Q: How should conversation titles be auto-generated? → A: Truncate first user message initially, then replace with an LLM-generated title once the first response completes.
- Q: What happens to conversations when a server is deleted? → A: Conversations are only loosely tied to a server (remembering the last used one). Changing server or model mid-conversation is fully supported. Deleting a server orphans conversations, which remain fully accessible.
- Q: What does the user see on first launch before any server is configured? → A: Redirect to server configuration screen with a welcome message on first launch.
- Q: Should images be resized/compressed before sending to the server? → A: Auto-resize to a configurable max dimension (default 1024px) and compress to JPEG quality 85%.

## Assumptions

- The target LLM servers expose an OpenAI-compatible API (specifically `/v1/models` and `/v1/chat/completions` endpoints).
- The servers support streaming via Server-Sent Events or chunked transfer encoding.
- Tool calling follows the OpenAI function calling format; servers that don't support tools will simply not trigger tool call flows.
- Context window sizes are either reported by the `/v1/models` endpoint or configured manually by the user per model.
- The app stores all data locally on the device; no cloud sync or remote backup is in scope.
- API keys, where required, follow the Bearer token authentication pattern (`Authorization: Bearer <key>`).
- Users are technically comfortable enough to know their server URL and optionally an API key.
- Compaction summaries are generated by the same LLM being used in the conversation.
