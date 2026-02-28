# ADR 006: Conversation Tree Architecture

**Date**: 2026-02-27  
**Status**: Approved  
**Author**: Pocket LLM Team  
**Context**: See ../context/chat-flow.md

---

## Decision

Conversations are stored as a tree structure where:
- Messages form parent-child relationships via `parentMessageId`
- Branch points have multiple children
- Active branch tracked via `activeLeafMessageId`

This enables:
- **Regeneration**: New child message from same parent
- **Editing**: Create new branch from edited message
- **Branch navigation**: prev/next arrows between siblings

---

## Status

**Approved** - This is the canonical conversation structure.

---

## Rationale

### Why Tree Instead of Linear?

| Linear | Tree |
|--------|------|
| No regeneration without loss | Regenerate creates branch, preserves original |
| Edit replaces history | Edit creates branch, preserves original path |
| Single conversation thread | Multiple exploration paths |

**Verdict**: Tree structure enables full message history preservation.

### Why Active Leaf Tracking?

Tracking `activeLeafMessageId` allows:
- Quick navigation to current position
- Efficient branch switching
- Clear UI indication of active path

---

## Database Schema

```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentMessageId: String?,  // NULL for root messages
    
    // Navigation fields (denormalized for performance)
    val depth: Int,           // Distance from root
    val childCount: Int,      // Number of direct children
    
    // Content fields
    val role: String,         // "user", "assistant", "system", "tool"
    val content: String,
    val thinkingContent: String?,
    
    // Metadata
    val serverProfileId: String?,
    val modelId: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val createdAt: Long
)
```

### Indices

```kotlin
@Index("idx_message_conversationId")
@Index("idx_message_parentMessageId")
```

---

## Tree Operations

### Get Active Branch (from leaf to root)

```kotlin
// Recursive CTE in MessageDao.kt
WITH RECURSIVE active_branch AS (
    SELECT * FROM messages WHERE id = :activeLeafId
    UNION ALL
    SELECT m.* FROM messages m
    INNER JOIN active_branch ab ON m.id = ab.parentMessageId
)
SELECT * FROM active_branch ORDER BY createdAt DESC;
```

### Get Children at Branch Point

```kotlin
@Query("SELECT * FROM messages WHERE parentMessageId = :parentId ORDER BY createdAt ASC")
fun getChildren(parentId: String): Flow<List<MessageEntity>>
```

### Count Messages Since Last Compaction

```sql
-- Get latest compaction point
SELECT insertedBeforeMessageId FROM compaction_summaries 
WHERE conversationId = :conversationId 
ORDER BY createdAt DESC LIMIT 1;

-- Count uncompacted messages
SELECT COUNT(*) FROM messages 
WHERE conversationId = :conversationId 
AND id > :lastCompactedId;
```

---

## Branch Navigation

### UI Indicators

Each branch point shows:
- "2 of 3" indicator
- Prev/Next navigation arrows
- Visual marker (e.g., circle with number)

### State Management

```kotlin
data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val activeBranchId: String? = null,  // Current leaf in branch
    val currentMessageId: String? = null, // Currently focused message
)
```

---

## Trade-offs

### Pros
- Full history preservation (no data loss on regenerate/edit)
- Multiple exploration paths supported natively
- Efficient navigation with denormalized fields

### Cons
- More complex queries (recursive CTEs needed)
- Denormalized fields must be kept in sync
- UI complexity for branch visualization

**Mitigation**: Use Room's query features, keep UI simple with clear indicators.

---

## Implementation Requirements

### New Message Creation
```kotlin
// When creating new message:
val parentMessage = messageDao.getById(parentId)
messageDao.insert(MessageEntity(
    id = UUID.randomUUID().toString(),
    parentMessageId = parentId,
    depth = (parentMessage?.depth ?: -1) + 1,
    childCount = 0,  // Will be incremented by trigger or manual update
))
```

### Branch Point Indicators
- Show "2 of 3" when `childCount > 1`
- Disable prev/next at branch boundaries
- Visual marker for active path

---

## Compliance

Conversation tree MUST:

- [ ] Use parent-child relationship via `parentMessageId`
- [ ] Track `activeLeafMessageId` for current position
- [ ] Support recursive CTEs for branch queries
- [ ] Display branch indicators clearly in UI

**Enforcement**: Code review + tests that verify tree behavior.

---

## Future Enhancements

Potential additions:
1. **Message collapsing** - Hide subtrees by default
2. **Branch diffing** - Show differences between branches
3. **Branch merging** - Consolidate branches when no longer needed

These are M3 features (Agent Network, Workflow management).
