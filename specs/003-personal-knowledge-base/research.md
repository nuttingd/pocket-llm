# Research: Personal Knowledge Base Implementation

**Feature**: `003-personal-knowledge-base`  
**Date**: 2026-02-27  
**Branch**: `003-personal-knowledge-base`

This document consolidates research findings to resolve all technical unknowns identified during spec planning. All decisions are guided by the project's local-first architecture and single-module constraint.

---

## 1. Document Processing Architecture

### Decision: **Unified `DocumentProcessor` interface with format-specific implementations**

### Implementation Strategy

```kotlin
// Core interface in app/src/main/java/dev/nutting/pocketllm/data/local/processor/
interface DocumentProcessor {
    fun canHandle(mimeType: String, extension: String): Boolean
    suspend fun process(inputStream: InputStream, documentId: String): DocumentProcessingResult
}

data class DocumentProcessingResult(
    val content: String,
    val metadata: DocumentMetadata,
    val wordCount: Int,
    val charCount: Int
)
```

### Library Selection

| Format | Library | Version | Rationale |
|--------|---------|---------|-----------|
| PDF | **pdfbox-android** | 2.0.27.0 | Actively maintained fork of Apache PDFBox with Android support, handles text extraction well |
| DOCX | **docx4j-core** | 8.3.1 | Robust Word docx parsing, better than alternatives for Android |
| Plain Text | Standard Kotlin | N/A | Built-in `InputStream.readBytes()` + encoding detection |

### Why These Libraries

- All are lightweight (critical for mobile with limited storage)
- No complex native dependencies (unlike full office suites)
- Good performance on mobile devices
- Apache License 2.0 compatible

### Processing Pipeline

```
Upload → File Storage (app-specific dir) → Processor Selection 
    → Content Extraction → Store in DB (Entity + FTS) 
    → Update Statistics → Notify UI
```

### Key Design Decisions

1. **Extract text immediately upon upload** - not on-demand (better performance for search)
2. **Store processed content in database** - not separate files (simplifies backup/export)
3. **Use WorkManager for processing** - prevents ANR on large documents
4. **Track word/character counts** - enables usage analytics and quotas

### Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Store raw PDF/DOCX bytes in database | Blobs would make database huge (10MB+ per document), slow queries |
| On-demand parsing (lazy) | Search would be unusably slow, poor user experience |
| External service parsing | Violates local-first constraint, requires internet |

---

## 2. Search Implementation Strategy

### Decision: **Use SQLite FTS4 with Room (already implemented)**

### Rationale

1. **Existing infrastructure** - The app already has `MessageFts` working perfectly
2. **Zero additional dependencies** - Uses built-in Android SQLite FTS
3. **Automatic sync** - Triggers keep search index in sync with content changes
4. **Proven performance** - Handles millions of documents efficiently on mobile

### Schema Extension

```kotlin
// Add to PocketLlmDatabase entities list:
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceFileName: String,
    val mimeType: String,
    val wordCount: Int,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@Fts4(contentEntity = DocumentContentEntity::class)
@Entity(tableName = "document_fts")
data class DocumentFts(
    val content: String
)

@Entity(tableName = "document_content")
data class DocumentContentEntity(
    @PrimaryKey val documentId: String,
    val content: String
)
```

### Search Dao Implementation

```kotlin
@Dao
interface DocumentDao {
    // CRUD operations
    
    @Query("""
        SELECT d.id, d.title, d.sourceFileName, d.wordCount, d.createdAt
        FROM documents d
        WHERE d.id IN (
            SELECT documentId FROM document_fts 
            WHERE content MATCH :query 
            ORDER BY rank LIMIT 50
        )
        ORDER BY d.updatedAt DESC
    """)
    fun searchDocuments(query: String): Flow<List<DocumentSearchResult>>
}
```

### Incremental Updates Strategy

**Trigger-based synchronization (recommended for this architecture)**:
```sql
-- Automatic trigger when document content changes:
CREATE TRIGGER document_fts_sync_after_update 
AFTER UPDATE ON document_content BEGIN
    DELETE FROM document_fts WHERE docid = OLD.documentId;
    INSERT INTO document_fts(docid, content) VALUES (NEW.documentId, NEW.content);
END;

CREATE TRIGGER document_fts_sync_after_insert 
AFTER INSERT ON document_content BEGIN
    INSERT INTO document_fts(docid, content) VALUES (NEW.documentId, NEW.content);
END;
```

### When to Rebuild FTS

- After initial document upload
- When user modifies document content in UI
- Database migration that affects content

### Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| In-memory inverted index (HashMap) | Limited by app memory, lost on process death, no persistence |
| SQLite LIKE queries | O(n) complexity, unusably slow for >100 documents |
| External search library (e.g., Elasticsearch) | Overkill for mobile, violates local-first principle |

---

## 3. Document Storage Location

### Decision: **Mixed approach - metadata in Room, content in Room FTS**

### Rationale

- **Metadata in Room**: Quick queries on titles, dates, sizes
- **Content in FTS**: Optimized full-text search with ranking
- **No separate file storage needed**: Content is just text (typically <1MB per document)

### Storage Design

```
Database Storage:
├── documents table          → documentEntity (title, filename, stats)
├── document_content table   → parsed text content
└── document_fts table       → FTS index for search

File System Storage:
└── {externalFilesDir}/documents/
    └── {documentId}/        → original uploaded file (for export/re-import)
        ├── source.pdf       → original PDF
        ├── metadata.json    → parsing metadata
        └── preview.txt      → first 500 chars for quick preview
```

### Benefits of This Approach

1. **Backup simplicity** - Single database file covers everything
2. **Export ease** - Can dump entire database or export selective documents
3. **Performance** - FTS4 is highly optimized for text search
4. **Synchronization** - No complex file sync logic needed

### File Storage Justification

Keep original files on disk because:

- **User access**: Users may want to re-upload, share, or process differently
- **Format preservation**: Keep original for future processing with better tools
- **Export requirements**: Need originals for complete backup/restore

### Database Size Estimate

| Document Type | Average Size | 100 docs | 1000 docs |
|---------------|--------------|----------|-----------|
| PDF (text only) | ~250KB | 25MB | 250MB |
| Markdown | ~10KB | 1MB | 10MB |
| TXT | ~5KB | 0.5MB | 5MB |
| **Total DB (with FTS overhead)** | - | **~30-50MB** | **~300-500MB** |

**Note:** Room's compression and SQLite's efficiency means actual sizes will be smaller.

---

## 4. Export/Import Format Details

### Decision: **JSON-based manifest with SHA-256 integrity verification**

### Export Structure

```
pocketllm_kb_export_YYYYMMDDTHHmmssZ/
├── manifest.json              → Metadata + content references
└── documents/                 → Original files (optional)
    ├── doc_abc123.pdf
    └── doc_def456.md
```

### Manifest Schema

```kotlin
@Serializable
data class KnowledgeBaseManifest(
    val version: String = "1.0",
    val exportedAt: Long,  // Unix timestamp
    val documentCount: Int,
    @SerialName("documents")
    val entries: List<DocumentEntry>
)

@Serializable
data class DocumentEntry(
    val id: String,
    val title: String,
    @SerialName("source_filename") val sourceFileName: String,
    val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("content_hash") val contentHash: String,  // SHA-256 hex
    @SerialName("word_count") val wordCount: Int,
    @SerialName("checksum_algorithm") val checksumAlgorithm: String = "SHA-256"
)
```

### Export Process

```kotlin
suspend fun exportKnowledgeBase(outputDirectory: File): ExportResult {
    val documents = documentDao.getAllSorted()
    
    // 1. Generate manifest with content hashes
    val manifest = KnowledgeBaseManifest(
        exportedAt = System.currentTimeMillis(),
        documentCount = documents.size,
        entries = documents.map { doc ->
            DocumentEntry(
                id = doc.id,
                title = doc.title,
                sourceFileName = doc.sourceFileName,
                mimeType = doc.mimeType,
                sizeBytes = doc.sizeBytes,
                createdAt = doc.createdAt,
                updatedAt = doc.updatedAt,
                contentHash = calculateSha256(doc.content),
                wordCount = doc.wordCount
            )
        }
    )
    
    // 2. Write manifest.json
    val manifestJson = json.encodeToString(manifest)
    File(outputDirectory, "manifest.json").writeText(manifestJson)
    
    return ExportResult(success = true, manifest = manifest)
}
```

### Import Deduplication Strategy

**Three-tier deduplication approach:**

1. **Hash-based (primary)**: Check `contentHash` against existing documents
   - If hash exists → skip (already imported)
   
2. **Filename-based (secondary)**: For same content, different filenames
   - If filename matches + similar size → skip
   
3. **User decision (fallback)**: Present conflict dialog
   - Options: Skip, Overwrite, Rename to `{title}_{timestamp}`

```kotlin
suspend fun importKnowledgeBase(manifest: KnowledgeBaseManifest): ImportResult {
    val results = manifest.entries.map { entry ->
        // Check for existing document by hash
        val existing = documentDao.getByContentHash(entry.contentHash)
        
        return@map when {
            existing != null -> ImportOutcome.SKIPPED_DUPLICATE
            shouldImport(entry) -> {
                // Download/extract content, save to storage
                val documentId = saveDocument(entry)
                ImportOutcome.IMPORTED
            }
            else -> ImportOutcome.USER_SKIPPED
        }
    }
    
    return ImportResult(
        imported = results.count { it == ImportOutcome.IMPORTED },
        skipped = results.count { it == ImportOutcome.SKIPPED_DUPLICATE }
    )
}
```

### SHA-256 Implementation

```kotlin
import java.security.MessageDigest

fun calculateSha256(text: String): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
```

### Alternative Formats Considered

| Format | Why Rejected |
|--------|--------------|
| SQLite database dump only | No human-readable metadata, hard to inspect |
| Raw text/Markdown | Lost metadata (titles, dates, IDs), not self-contained |
| Custom binary format | No human inspection capability, harder debugging |

---

## 5. Migration Path

### Database Version Increment

**Current database version**: From existing `PocketLlmDatabase.kt`  
**New database version**: Increment by 1 for new entities

```kotlin
@Database(
    entities = [
        // ... existing entities ...
        DocumentEntity::class,
        DocumentContentEntity::class,
        DocumentFts::class,
    ],
    version = [CURRENT_VERSION + 1],  // Increment!
)
```

### Migration Script

Add to `PocketLlmDatabase.kt`:

```kotlin
private val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create documents table
        db.execSQL("""
            CREATE TABLE documents (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                source_filename TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                word_count INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        
        // Create document_content table
        db.execSQL("""
            CREATE TABLE document_content (
                document_id TEXT NOT NULL PRIMARY KEY,
                content TEXT NOT NULL,
                FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
        """.trimIndent())
        
        // Create FTS virtual table
        db.execSQL("""
            CREATE VIRTUAL TABLE document_fts USING FTS4(content=document_content)
        """.trimIndent())
        
        // Create triggers for FTS sync
        db.execSQL("""
            CREATE TRIGGER doc_fts_after_insert AFTER INSERT ON document_content
            BEGIN
                INSERT INTO document_fts(docid, content) VALUES (NEW.document_id, NEW.content);
            END
        """.trimIndent())
        
        db.execSQL("""
            CREATE TRIGGER doc_fts_after_update AFTER UPDATE ON document_content
            BEGIN
                DELETE FROM document_fts WHERE docid = OLD.document_id;
                INSERT INTO document_fts(docid, content) VALUES (NEW.document_id, NEW.content);
            END
        """.trimIndent())
        
        db.execSQL("INSERT INTO document_fts(document_fts) VALUES ('rebuild')")
    }
}
```

---

## 6. Performance Targets

| Operation | Target Time | Measurement Method |
|-----------|-------------|-------------------|
| Upload + Process (10 page PDF) | < 5 seconds | WorkManager background |
| Search query (< 1000 docs) | < 200ms | FTS4 indexed |
| Export manifest generation | < 1 second | Suspend function |
| Import single document | < 3 seconds | WorkManager background |

---

## Summary

All technical unknowns have been resolved. The implementation follows the project's existing architecture patterns and leverages proven components (Room, FTS4, WorkManager). No additional dependencies beyond those already listed are required.

**Status**: ✅ **Ready for Phase 1 design**
