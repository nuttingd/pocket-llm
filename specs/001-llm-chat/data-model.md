# Data Model: LLM Chat Client

**Branch**: `001-llm-chat` | **Date**: 2026-02-02

## Room Database Schema

### Entity: ServerProfile

Represents an LLM server connection (spec entity: Server).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `name` | String | NOT NULL | User-visible label |
| `baseUrl` | String | NOT NULL | e.g. `http://localhost:11434` |
| `hasApiKey` | Boolean | NOT NULL, default false | Whether an API key is stored (key itself in encrypted DataStore) |
| `requestTimeoutSeconds` | Int | NOT NULL, default 60 | Per-server timeout (FR-006) |
| `createdAt` | Long | NOT NULL | Epoch millis |
| `updatedAt` | Long | NOT NULL | Epoch millis |

**Indices**: none beyond PK (low cardinality).

**Notes**:
- API keys stored separately in encrypted DataStore (Tink), keyed by server `id`.
- Models are fetched dynamically from `/v1/models` and not persisted (spec says "fetched dynamically from the server").

---

### Entity: Conversation

A thread of messages between user and LLM (spec entity: Conversation).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `title` | String | NOT NULL | Auto-generated or user-renamed (FR-022) |
| `lastServerProfileId` | String | NULLABLE, FK → ServerProfile.id (SET NULL on delete) | Loose reference, not a binding (clarification: conversations loosely tied) |
| `lastModelId` | String | NULLABLE | Last-used model identifier |
| `systemPrompt` | String | NULLABLE | Per-conversation system prompt (FR-013) |
| `temperature` | Float | NULLABLE | Overrides global default |
| `maxTokens` | Int | NULLABLE | Overrides global default |
| `topP` | Float | NULLABLE | Overrides global default |
| `frequencyPenalty` | Float | NULLABLE | Overrides global default |
| `presencePenalty` | Float | NULLABLE | Overrides global default |
| `activeLeafMessageId` | String | NULLABLE | Currently viewed branch leaf (for tree navigation) |
| `createdAt` | Long | NOT NULL | Epoch millis |
| `updatedAt` | Long | NOT NULL | Epoch millis, updated on each new message |

**Indices**: `idx_conversation_updatedAt` on `updatedAt` DESC (conversation list sort order FR-023).

**FK behavior**: `lastServerProfileId` → ON DELETE SET NULL (orphan behavior per clarification).

---

### Entity: Message

A single message within a conversation (spec entity: Message). Messages form a tree where branch points have multiple children.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `conversationId` | String | NOT NULL, FK → Conversation.id (CASCADE) | Parent conversation |
| `parentMessageId` | String | NULLABLE, FK → Message.id (CASCADE) | NULL for root messages; self-referencing FK for tree |
| `role` | String | NOT NULL | "user", "assistant", "system", "tool" |
| `content` | String | NOT NULL | Message text content |
| `thinkingContent` | String | NULLABLE | Reasoning/thinking content (FR-018) |
| `serverProfileId` | String | NULLABLE | Server used for this specific message |
| `modelId` | String | NULLABLE | Model used for this specific message |
| `promptTokens` | Int | NULLABLE | Token count for prompt (FR-017) |
| `completionTokens` | Int | NULLABLE | Token count for completion (FR-017) |
| `totalTokens` | Int | NULLABLE | Total token count (FR-017) |
| `toolCallId` | String | NULLABLE | Tool call identifier (for tool role messages) |
| `toolCallsJson` | String | NULLABLE | JSON array of tool calls made by assistant (FR-040) |
| `imageUris` | String | NULLABLE | Comma-separated local URIs of attached images (FR-080) |
| `depth` | Int | NOT NULL, default 0 | Distance from root, for performance |
| `childCount` | Int | NOT NULL, default 0 | Number of direct children, for branch indicators |
| `createdAt` | Long | NOT NULL | Epoch millis |

**Indices**:
- `idx_message_conversationId` on `conversationId`
- `idx_message_parentMessageId` on `parentMessageId`
- `idx_message_content` (FTS virtual table for search, FR-024)

**FK behavior**:
- `conversationId` → ON DELETE CASCADE (deleting conversation removes all messages)
- `parentMessageId` → ON DELETE CASCADE (deleting a message removes its subtree)

**Tree queries** (recursive CTEs):
- Get active branch: walk from `activeLeafMessageId` up to root via `parentMessageId`
- Get children at branch point: `SELECT * FROM messages WHERE parentMessageId = :id`
- Get full subtree: recursive CTE expanding from a root message downward

---

### Entity: CompactionSummary

Condensed representation of earlier messages (spec entity: Compaction Summary).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `conversationId` | String | NOT NULL, FK → Conversation.id (CASCADE) | |
| `summaryContent` | String | NOT NULL | LLM-generated summary text (FR-034) |
| `firstMessageId` | String | NOT NULL | First message in summarized range |
| `lastMessageId` | String | NOT NULL | Last message in summarized range |
| `messageCount` | Int | NOT NULL | Number of messages summarized |
| `createdAt` | Long | NOT NULL | Epoch millis |

**Indices**: `idx_compaction_conversationId` on `conversationId`.

---

### Entity: ParameterPreset

Named set of generation parameters (spec entity: Parameter Preset).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `name` | String | NOT NULL, UNIQUE | Preset display name |
| `temperature` | Float | NOT NULL | |
| `topP` | Float | NOT NULL | |
| `maxTokens` | Int | NOT NULL | |
| `frequencyPenalty` | Float | NOT NULL, default 0.0 | |
| `presencePenalty` | Float | NOT NULL, default 0.0 | |
| `isBuiltIn` | Boolean | NOT NULL, default false | Built-in presets are read-only (FR-100) |
| `createdAt` | Long | NOT NULL | Epoch millis |

**Indices**: none beyond PK and UNIQUE on `name`.

**Seed data** (built-in presets):
| Name | Temperature | Top-P | Max Tokens | Freq Penalty | Presence Penalty |
|------|-------------|-------|------------|--------------|------------------|
| Creative | 1.0 | 0.95 | 2048 | 0.3 | 0.3 |
| Precise | 0.2 | 0.5 | 2048 | 0.0 | 0.0 |
| Code | 0.1 | 0.3 | 4096 | 0.0 | 0.0 |
| Balanced | 0.7 | 0.8 | 2048 | 0.0 | 0.0 |

---

### Entity: ToolDefinition

Tools available for model function calling (spec entity: Tool Definition).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | String | PK | UUID |
| `name` | String | NOT NULL, UNIQUE | Tool function name |
| `description` | String | NOT NULL | Tool description sent to model |
| `parametersSchemaJson` | String | NOT NULL | JSON Schema for tool parameters |
| `isBuiltIn` | Boolean | NOT NULL, default false | Built-in vs user-defined |
| `isEnabledByDefault` | Boolean | NOT NULL, default true | Default enabled state for new conversations |

**Seed data** (built-in tools):
- `calculator`: Basic arithmetic evaluation
- `web_fetch`: Fetch URL content (with user approval)

---

### Junction: ConversationToolEnabled

Tracks which tools are enabled per conversation (FR-046).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `conversationId` | String | PK, FK → Conversation.id (CASCADE) | |
| `toolDefinitionId` | String | PK, FK → ToolDefinition.id (CASCADE) | |
| `isEnabled` | Boolean | NOT NULL, default true | |

---

## Encrypted DataStore (Tink)

Stored separately from Room. Keyed by server profile ID.

| Key Pattern | Type | Notes |
|-------------|------|-------|
| `api_key_{serverProfileId}` | String | Encrypted API key per server |

---

## Preferences DataStore (Settings)

App-level settings stored in unencrypted DataStore Preferences.

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `theme_mode` | String | "system" | "light", "dark", "system" (FR-070) |
| `message_font_size_sp` | Int | 16 | Message text size in SP (FR-071) |
| `dynamic_color_enabled` | Boolean | true | Material You dynamic color (FR-072) |
| `default_system_prompt` | String | "" | Global default system prompt (FR-013) |
| `default_temperature` | Float | 0.7 | Global default (FR-014) |
| `default_max_tokens` | Int | 2048 | Global default (FR-014) |
| `default_top_p` | Float | 1.0 | Global default (FR-014) |
| `default_frequency_penalty` | Float | 0.0 | Global default (FR-014) |
| `default_presence_penalty` | Float | 0.0 | Global default (FR-014) |
| `last_active_server_id` | String | "" | Last selected server |
| `last_active_conversation_id` | String | "" | Last open conversation |
| `compaction_threshold_pct` | Int | 75 | Context compaction trigger (FR-031) |
| `image_max_dimension_px` | Int | 1024 | Image resize long-side max (FR-081) |
| `image_jpeg_quality` | Int | 85 | JPEG compression quality (FR-081) |

---

## ER Diagram (text)

```
ServerProfile 1──────0..* Conversation
                          │
                          ├──1..* Message (tree via parentMessageId)
                          ├──0..* CompactionSummary
                          └──0..* ConversationToolEnabled ──* ToolDefinition

ParameterPreset (standalone, applied to Conversation or global defaults)
```

## FTS (Full-Text Search)

A virtual FTS table mirrors `Message.content` for full-text search (FR-024):

```sql
CREATE VIRTUAL TABLE message_fts USING fts4(content, content='messages', tokenize=unicode61);
```

Triggers keep the FTS table in sync with inserts/updates/deletes on `messages`.
