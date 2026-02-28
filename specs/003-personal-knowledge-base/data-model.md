# Data Model: Personal Knowledge Base

**Feature**: `003-personal-knowledge-base`  
**Date**: 2026-02-27  
**Branch**: `003-personal-knowledge-base`

---

## Entity Overview

The knowledge base consists of four primary entities that work together to store, index, and manage user documents.

```
┌─────────────────┐         ┌──────────────────────┐
│  Document       │         │  DocumentContent     │
│  (documents)    │<───────│  (document_content)  │
└─────────────────┘   ID    └──────────────────────┘
        │                         │
        │                         │
        ▼                         ▼
┌─────────────────┐         ┌──────────────────────┐
│  DocumentFts    │         │  DocumentMetadata    │
│  (document_fts) │         │  (in-memory only)    │
└─────────────────┘         └──────────────────────┘
```

---

## Core Entities

### 1. DocumentEntity

**Purpose**: Main record for each uploaded document, storing metadata and statistics.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | String | PRIMARY KEY, NOT NULL | UUID v4 identifier |
| title | String | NOT NULL | Display title (from filename or extracted) |
| sourceFileName | String | NOT NULL | Original uploaded filename |
| mimeType | String | NOT NULL | MIME type (application/pdf, text/plain, etc.) |
| wordCount | Int | NOT NULL, DEFAULT 0 | Word count for statistics |
| sizeBytes | Long | NOT NULL, DEFAULT 0 | File size in bytes |
| createdAt | Long | NOT NULL | Unix timestamp of upload |
| updatedAt | Long | NOT NULL | Unix timestamp of last modification |

**Relationships**: One-to-many with `DocumentContent`, one-to-zero-or-one with `DocumentFts`

**Validation Rules**:
- `id` must be a valid UUID v4 string
- `title` cannot be empty or whitespace-only
- `sourceFileName` must have valid characters (no path separators)
- `mimeType` must be from supported list: `application/pdf`, `text/plain`, `text/markdown`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- `wordCount` and `sizeBytes` must be non-negative

**State Transitions**: None - DocumentEntity is immutable after creation except for content updates

---

### 2. DocumentContentEntity

**Purpose**: Stores the extracted text content of each document for FTS indexing.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| documentId | String | PRIMARY KEY, FOREIGN KEY → Document.id ON DELETE CASCADE | Reference to parent document |
| content | String | NOT NULL | Full-text content extracted from document |

**Relationships**: One-to-one with `DocumentEntity`

**Validation Rules**:
- `documentId` must reference an existing document
- `content` cannot be null (empty string allowed for empty documents)

**State Transitions**:
- When user edits content: Update `content` field and trigger FTS sync via trigger

---

### 3. DocumentFts

**Purpose**: SQLite FTS4 virtual table for full-text search indexing.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| content | String | Content from document_content table | Full-text index (automatically populated) |

**Relationships**: Virtual table linked to `DocumentContentEntity` via FTS4 `content=` option

**Validation Rules**: None - automatically maintained by SQLite triggers

**State Transitions**: Automatically synced by database triggers when `document_content` changes

---

### 4. DocumentMetadata

**Purpose**: In-memory structure for passing parsed metadata between layers.

| Field | Type | Description |
|-------|------|-------------|
| title | String? | Extracted document title (may be null if not found) |
| author | String? | Document author (PDF/DOCX only) |
| creationDate | Long? | Creation timestamp (if available in metadata) |
| modifiedDate | Long? | Last modification timestamp |
| keywords | List<String>? | Document keywords/tags |

**Relationships**: Used by `DocumentProcessor` implementations to return parsed metadata

**Validation Rules**:
- All fields are nullable
- String values cannot be empty or whitespace-only (if provided)

---

## Extension Entities

### 5. ConversationLinkEntity

**Purpose**: Links documents to conversations for context-aware AI responses.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PRIMARY KEY, AUTOINCREMENT | Unique identifier |
| conversationId | String | NOT NULL, FOREIGN KEY → conversations.id ON DELETE CASCADE | Reference to conversation |
| documentId | String | NOT NULL, FOREIGN KEY → documents.id ON DELETE CASCADE | Reference to document |
| linkedAt | Long | NOT NULL | Unix timestamp of link creation |

**Relationships**: Many-to-one with `ConversationEntity`, many-to-one with `DocumentEntity`

**Validation Rules**:
- `(conversationId, documentId)` must be unique (no duplicate links)
- Both foreign keys must reference existing records

**State Transitions**: None - links are created and deleted atomically

---

## Indexes

```sql
-- Document search by title or filename
CREATE INDEX idx_documents_title ON documents(title);
CREATE INDEX idx_documents_source_filename ON documents(sourceFileName);

-- Search by upload date (for sorting)
CREATE INDEX idx_documents_created_at ON documents(createdAt);
CREATE INDEX idx_documents_updated_at ON documents(updatedAt);

-- Conversation document links lookup
CREATE INDEX idx_conversation_links_conversation ON conversation_links(conversationId);
CREATE INDEX idx_conversation_links_document ON conversation_links(documentId);
```

---

## Database Schema Summary

```sql
-- Documents table (metadata)
CREATE TABLE documents (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    source_filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    word_count INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Document content storage
CREATE TABLE document_content (
    document_id TEXT NOT NULL PRIMARY KEY,
    content TEXT NOT NULL,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- FTS4 search index (automatically maintained)
CREATE VIRTUAL TABLE document_fts USING FTS4(content=document_content);

-- Triggers for FTS synchronization
CREATE TRIGGER doc_fts_after_insert AFTER INSERT ON document_content
BEGIN
    INSERT INTO document_fts(docid, content) VALUES (NEW.document_id, NEW.content);
END;

CREATE TRIGGER doc_fts_after_update AFTER UPDATE ON document_content
BEGIN
    DELETE FROM document_fts WHERE docid = OLD.document_id;
    INSERT INTO document_fts(docid, content) VALUES (NEW.document_id, NEW.content);
END;

-- Conversation document links
CREATE TABLE conversation_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL,
    document_id TEXT NOT NULL,
    linked_at INTEGER NOT NULL,
    FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE,
    UNIQUE(conversation_id, document_id)
);
```

---

## Migration Strategy

**Version Change**: Increment database version by 1 in `PocketLlmDatabase.kt`

**Migration Script**: Add to existing migrations array in `PocketLlmDatabase.kt`:

```kotlin
private val MIGRATION_KB_1 = object : Migration(CURRENT_VERSION, CURRENT_VERSION + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create documents table
        db.execSQL("CREATE TABLE documents (...)")
        
        // Create document_content table
        db.execSQL("CREATE TABLE document_content (...)")
        
        // Create FTS virtual table
        db.execSQL("CREATE VIRTUAL TABLE document_fts USING FTS4(content=document_content)")
        
        // Create triggers for FTS sync
        db.execSQL("CREATE TRIGGER doc_fts_after_insert ...")
        db.execSQL("CREATE TRIGGER doc_fts_after_update ...")
        
        // Rebuild FTS index with existing data (if any)
        db.execSQL("INSERT INTO document_fts(document_fts) VALUES ('rebuild')")
    }
}
```

---

## Repository Interfaces

```kotlin
@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long
    
    @Update
    suspend fun update(document: DocumentEntity)
    
    @Delete
    suspend fun delete(document: DocumentEntity)
    
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?
    
    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    fun getAllSorted(): Flow<List<DocumentEntity>>
    
    @Query("""
        SELECT d.id, d.title, d.source_filename, d.word_count, d.created_at
        FROM documents d
        WHERE d.id IN (
            SELECT document_id FROM document_fts 
            WHERE content MATCH :query 
            ORDER BY rank LIMIT 50
        )
        ORDER BY d.updated_at DESC
    """)
    fun searchDocuments(query: String): Flow<List<DocumentSearchResult>>
    
    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getCount(): Int
    
    @Query("SELECT SUM(size_bytes) FROM documents")
    suspend fun getTotalSizeBytes(): Long?
}
```

---

## Performance Considerations

- **FTS4** provides O(log n) search complexity instead of O(n) with LIKE
- **WorkManager** processes large document uploads in background (prevents ANR)
- **Flow-based queries** enable reactive UI updates when documents change
- **Foreign key constraints** ensure referential integrity for linked documents
