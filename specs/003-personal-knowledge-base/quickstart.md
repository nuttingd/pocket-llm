# Quick Start Guide: Personal Knowledge Base

**Feature**: `003-personal-knowledge-base`  
**Branch**: `003-personal-knowledge-base`  
**Last Updated**: 2026-02-27

This guide provides a minimal implementation checklist for developers who want to get started with the Personal Knowledge Base feature.

---

## Prerequisites

Before implementing this feature, ensure you understand:

1. The **[spec](./spec.md)** - What the feature does and user requirements
2. The **[research](./research.md)** - Technical decisions and library choices
3. The **[data model](./data-model.md)** - Database schema and entity relationships

---

## Minimal Implementation Checklist

### Phase 1: Core Infrastructure (2-3 days)

#### 1. Database Schema Migration

**File**: `app/src/main/java/dev/nutting/pocketllm/data/PocketLlmDatabase.kt`

```kotlin
// Increment version
@Database(entities = [
    // ... existing entities ...
    DocumentEntity::class,
    DocumentContentEntity::class,
    DocumentFts::class,
], version = 7)  // was 6
```

**Migration script**: Add `MIGRATION_KB_1` to the migrations array (see data-model.md for full script)

#### 2. DAO Interface

**File**: `app/src/main/java/dev/nutting/pocketllm/data/local/dao/DocumentDao.kt`

Implement basic CRUD operations for `DocumentEntity` and search functionality using FTS4.

#### 3. Repository Layer

**File**: `app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`

Create repository that:
- Wraps `DocumentDao` with coroutine support
- Provides use case methods: `uploadDocument()`, `searchDocuments()`
- Handles WorkManager scheduling for background processing

#### 4. Document Processor Interface

**File**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocumentProcessor.kt`

Define the interface and implement basic text processor for `.txt` and `.md` files.

---

### Phase 2: Upload Flow (1-2 days)

#### 5. WorkManager Worker

**File**: `app/src/main/java/dev/nutting/pocketllm/data/local/UploadDocumentWorker.kt`

Create worker that:
- Receives document ID from upload request
- Selects appropriate processor based on MIME type
- Extracts content and stores in database
- Updates statistics (word count, file size)
- Notifies UI via `UiState` updates

#### 6. File Picker Integration

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/FilePickerDialog.kt`

Use Android's Storage Access Framework to let users select documents from any provider (device storage, Google Drive, etc.).

#### 7. Upload UI Screen

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentListScreen.kt`

Display:
- List of uploaded documents with metadata
- "Add Document" FAB button
- Progress indicators during upload processing
- Error states for failed uploads

---

### Phase 3: Search (1 day)

#### 8. Search Implementation

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/SearchBar.kt` and related code

Implement:
- Text input field with auto-suggest
- Real-time search results as user types
- Highlighting of matching terms in titles and content previews

#### 9. Search Results UI

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/SearchResultsScreen.kt`

Display:
- Ranked list of matching documents
- Document preview snippets with highlighted terms
- Empty state when no results found

---

### Phase 4: Integration with Chat (1 day)

#### 10. Conversation Linking UI

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/LinkedDocumentsSection.kt`

Add section to conversation screen that:
- Shows list of linked documents
- Provides button to add new links
- Displays warning badge for deleted linked documents

#### 11. Context Injection

Modify existing chat flow to include document content when linked documents exist.

---

### Phase 5: Export/Import (2 days)

#### 12. Manifest Generation

**File**: `app/src/main/java/dev/nutting/pocketllm/domain/model/KnowledgeBaseManifest.kt`

Implement serialization of document metadata for export.

#### 13. Export UI

**File**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/ExportScreen.kt`

Create screen that:
- Shows total knowledge base size
- Allows user to select export location
- Shows progress during export

#### 14. Import Logic

Implement deduplication strategy (hash-based, filename-based, user decision).

---

## Testing Checklist

### Unit Tests

- [ ] DocumentProcessor implementations (mocked InputStream)
- [ ] Search query formatting and FTS results
- [ ] SHA-256 hash calculation for exports
- [ ] Manifest serialization/deserialization
- [ ] Export/Import deduplication logic

### Integration Tests

- [ ] Full upload → search workflow
- [ ] Document linking to conversations
- [ ] Export → delete → import roundtrip
- [ ] Large document handling (50+ pages)
- [ ] Failed upload recovery

### UI Tests

- [ ] Document list renders with 100+ items
- [ ] Search as you type performance
- [ ] Import dialog error states
- [ ] Accessibility navigation (TalkBack)

---

## Common Pitfalls

1. **FTS4 Trigger Errors**: Remember to rebuild FTS index after migration: `INSERT INTO document_fts(document_fts) VALUES ('rebuild')`

2. **Memory on Large PDFs**: Process documents in WorkManager, not main thread - prevents ANR

3. **MIME Type Detection**: Use `MimeTypeMap.getSingleton().getMimeTypeFromExtension()` for reliable detection

4. **Storage Permissions**: Use Storage Access Framework (SAF) instead of requesting storage permission

5. **Database Size Growth**: Implement user quota warnings when approaching 500MB limit

---

## Next Steps

After completing this feature:

1. Run `./gradlew assembleDebug` to verify no build errors
2. Execute unit tests: `./gradlew test`
3. Verify with TalkBack enabled for accessibility compliance
4. Document any learnings in `.specify/memory/lessons.md`
