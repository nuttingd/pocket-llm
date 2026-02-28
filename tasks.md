# Tasks: Personal Knowledge Base Feature

**Feature Branch**: `003-personal-knowledge-base`  
**Last Updated**: 2026-02-27

This document contains all implementation tasks for the Personal Knowledge Base feature, organized by user story.

---

## Phase 1: Setup (No User Story)

- [ ] T001 Create documentation structure under `specs/003-personal-knowledge-base/` with all required files
  - File: `specs/003-personal-knowledge-base/spec.md`
  - File: `specs/003-personal-knowledge-base/research.md`
  - File: `specs/003-personal-knowledge-base/data-model.md`
  - File: `specs/003-personal-knowledge-base/quickstart.md`
  - Directory: `specs/003-personal-knowledge-base/contracts/`

- [ ] T002 Add WorkManager dependency to app/build.gradle.kts if not already present
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/build.gradle.kts`
  - Note: Already has `implementation("androidx.work:work-runtime-ktx:2.10.1")`

---

## Phase 2: Foundational (No User Story)

### Database Schema

- [ ] T010 Update PocketLlmDatabase.kt to add new entities and increment version
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`
  - Action: Add `DocumentEntity::class`, `DocumentContentEntity::class`, `DocumentFts::class` to entities array
  - Action: Update version from 6 to 7

- [ ] T011 Create DocumentDao.kt with CRUD operations and FTS search
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/dao/DocumentDao.kt`
  - Includes:
    - `insert(document: DocumentEntity): Long`
    - `update(document: DocumentEntity)`
    - `delete(document: DocumentEntity)`
    - `getById(id: String): DocumentEntity?`
    - `getAllSorted(): Flow<List<DocumentEntity>>`
    - `searchDocuments(query: String): Flow<List<DocumentSearchResult>>`

- [ ] T012 Create DocumentContentDao.kt for content storage operations
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/dao/DocumentContentDao.kt`
  - Includes:
    - `insert(content: DocumentContentEntity)`
    - `update(content: DocumentContentEntity)`
    - `getByDocumentId(documentId: String): DocumentContentEntity?`

- [ ] T013 Add migration script to PocketLlmDatabase.kt MIGRATION_6_7
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`
  - Action: Create documents table, document_content table, FTS virtual table
  - Action: Create triggers for FTS sync
  - Action: Rebuild FTS index after migration

### Data Models

- [ ] T020 Create DocumentEntity.kt with all required fields
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentEntity.kt`
  - Fields: id, title, sourceFileName, mimeType, wordCount, sizeBytes, createdAt, updatedAt

- [ ] T021 Create DocumentContentEntity.kt for text content storage
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentContentEntity.kt`
  - Fields: documentId (PK, FK), content

- [ ] T022 Create DocumentFts.kt FTS4 virtual table entity
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentFts.kt`
  - Annotation: `@Fts4(contentEntity = DocumentContentEntity::class)`

- [ ] T023 Create ConversationLinkEntity.kt for document-conversation linking
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/entity/ConversationLinkEntity.kt`
  - Fields: id, conversationId, documentId, linkedAt

### Repository Layer

- [ ] T030 Create DocumentRepository.kt with use case methods
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`
  - Methods:
    - `uploadDocument()`
    - `searchDocuments()`
    - `getById()`
    - `getAllSorted()`

- [ ] T031 Create ConversationLinkDao.kt for document-conversation linking operations
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/dao/ConversationLinkDao.kt`
  - Includes:
    - `insert(link: ConversationLinkEntity)`
    - `deleteByDocumentId(documentId: String)`
    - `getLinksForConversation(conversationId: String): List<ConversationLinkEntity>`
    - `getDocumentsForConversation(conversationId: String): Flow<List<DocumentEntity>>`

- [ ] T032 Create DocumentSearchResult.kt data class
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentSearchResult.kt`
  - Fields: id, title, sourceFileName, wordCount, createdAt

### Document Processing

- [ ] T040 Create DocumentProcessor.kt interface
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocumentProcessor.kt`
  - Methods:
    - `canHandle(mimeType: String, extension: String): Boolean`
    - `process(inputStream: InputStream, documentId: String): DocumentProcessingResult`

- [ ] T041 Create DocumentMetadata.kt for parsed metadata structure
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocumentMetadata.kt`
  - Fields: title, author, creationDate, modifiedDate, keywords

- [ ] T042 Create TextDocumentProcessor.kt for .txt and .md files
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/processor/TextDocumentProcessor.kt`

### Export/Import Models

- [ ] T050 Create KnowledgeBaseManifest.kt for export format
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/domain/model/KnowledgeBaseManifest.kt`
  - Includes:
    - `KnowledgeBaseManifest` class with version, exportedAt, documentCount, entries
    - `DocumentEntry` class with all metadata fields and SHA-256 contentHash

---

## Phase 3: User Story 1 - Upload and Store Documents [US1]

### Document Upload UI

- [ ] T100 Create FilePickerDialog.kt for selecting documents from device storage
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/upload/FilePickerDialog.kt`
  - Uses Storage Access Framework (SAF) to select files

- [ ] T101 Create UploadViewModel.kt for managing upload state
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadViewModel.kt`
  - State: `UiState` with uploading files list, progress, errors
  - Methods:
    - `selectFiles()`
    - `startUpload()`
    - `cancelUpload()`

- [ ] T102 Create DocumentListScreen.kt for displaying uploaded documents
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/DocumentListScreen.kt`
  - Displays:
    - List of documents with title, size, date added, processing state
    - "Add Document" FAB button
    - Progress indicators during upload processing

### WorkManager Integration

- [ ] T110 Create UploadDocumentWorker.kt for background document processing
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/UploadDocumentWorker.kt`
  - Processing:
    - Retrieves document ID from work request
    - Loads original file from app-specific storage
    - Selects appropriate processor based on MIME type
    - Extracts content and stores in database
    - Updates statistics (word count, file size)
    - Updates UI state via Room observer

- [ ] T111 Create UploadDocumentWorkManager.kt utility class for scheduling
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/UploadDocumentWorkManager.kt`
  - Methods:
    - `scheduleUpload(documentId: String)`
    - `getActiveUploads(): List<WorkInfo>`

### File Storage

- [ ] T120 Update UploadViewModel.kt to save original files to app-specific storage
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadViewModel.kt`
  - Action: Save uploaded file to `context.getExternalFilesDir("documents")/${documentId}/source.{ext}`

### Processing Logic

- [ ] T130 Create PdfDocumentProcessor.kt using pdfbox-android
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/processor/PdfDocumentProcessor.kt`
  - Uses: `pdfbox-android 2.0.27.0`

- [ ] T131 Create DocxDocumentProcessor.kt using docx4j-core
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocxDocumentProcessor.kt`
  - Uses: `docx4j-core 8.3.1`

### Error Handling

- [ ] T140 Add failed state display in DocumentListScreen.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/DocumentListScreen.kt`
  - Shows clear visual indication for documents with `processing_state: failed`

---

## Phase 4: User Story 2 - Search Documents by Content [US2]

### Search Implementation

- [ ] T200 Create SearchBar.kt composable in DocumentListScreen
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/SearchBar.kt`
  - Includes:
    - Text input field with search icon
    - Clear button
    - Auto-suggest based on document titles

- [ ] T201 Create SearchResultsScreen.kt for displaying search results
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/ui/documents/SearchResultsScreen.kt`
  - Displays:
    - Ranked list of matching documents
    - Document preview snippets with highlighted terms
    - Empty state when no results found

### UI Integration

- [ ] T210 Add search functionality to UploadViewModel.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadViewModel.kt`
  - Action: Call DocumentRepository.searchDocuments() and update UI state

- [ ] T211 Add search functionality to DocumentListScreen.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/DocumentListScreen.kt`
  - Action: Observe search results Flow and display in list

### Highlighting

- [ ] T220 Create TextHighlighter.kt utility for matching term highlighting
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/util/TextHighlighter.kt`
  - Methods:
    - `highlightMatches(text: String, query: String): AnnotatedString`

---

## Phase 5: User Story 3 - Link Documents to Conversations [US3]

### DAO and Repository

- [ ] T300 Update ConversationRepository.kt with linking methods
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/repository/ConversationRepository.kt`
  - Methods:
    - `linkDocument(conversationId: String, documentId: String)`
    - `unlinkDocument(conversationId: String, documentId: String)`
    - `getLinkedDocuments(conversationId: String): Flow<List<DocumentEntity>>`

### UI Components

- [ ] T310 Create LinkedDocumentsSection.kt for chat screen integration
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/chat/LinkedDocumentsSection.kt`
  - Shows:
    - List of linked documents with delete button
    - "Add Document" button to attach from knowledge base
    - Warning badge for deleted linked documents

- [ ] T311 Update ChatViewModel.kt to include document links in conversation state
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt`
  - Add:
    - `_uiState.update { it.copy(linkedDocumentIds = ... }` field

### Context Injection

- [ ] T320 Update ChatManager.kt to include linked document content in AI context
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt`
  - Action: Append document text to conversation system prompt or user message

---

## Phase 6: User Story 4 - Export Knowledge Base [US4]

### Export Implementation

- [ ] T400 Create ExportKnowledgeBaseUseCase.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/domain/usecase/ExportKnowledgeBaseUseCase.kt`
  - Methods:
    - `export(outputDirectory: File): ExportResult`

- [ ] T401 Create KnowledgeBaseManifestSerializer.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/domain/serialization/KnowledgeBaseManifestSerializer.kt`
  - Uses: `kotlinx-serialization-json 1.8.0`

### Export UI

- [ ] T410 Create ExportScreen.kt for export flow
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/ExportScreen.kt`
  - Shows:
    - Preview with total document count and combined size
    - Export location picker (SAF)
    - Progress indicator during export

### Import Implementation

- [ ] T420 Create ImportKnowledgeBaseUseCase.kt
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/domain/usecase/ImportKnowledgeBaseUseCase.kt`
  - Deduplication strategy:
    - Hash-based (SHA-256)
    - Filename-based (secondary)
    - User decision (fallback)

### Import UI

- [ ] T430 Create ImportScreen.kt for import flow
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/ImportScreen.kt`
  - Shows:
    - Manifest preview (document count, total size)
    - Conflict resolution dialog for duplicates

---

## Final Phase: Polish & Cross-Cutting Concerns

### Accessibility

- [ ] T500 Verify DocumentListScreen.kt is accessible with TalkBack
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/DocumentListScreen.kt`
  - Touch targets meet 48dp minimum
  - All interactive elements have contentDescription

- [ ] T501 Verify SearchBar.kt accessibility
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/SearchBar.kt`
  - Search button has contentDescription

### Performance

- [ ] T510 Add progress bar for long-running export operations
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/ExportScreen.kt`

- [ ] T511 Implement pagination for DocumentListScreen with 50+ items
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/ui/documents/DocumentListScreen.kt`
  - Use LazyColumn with item key to prevent unnecessary recomposition

### Testing (Optional per spec)

- [ ] T520 Write unit tests for DocumentProcessor implementations
  - Files: `tests/unit/processor/*.kt`

- [ ] T521 Write integration tests for export/import roundtrip
  - Files: `tests/androidTest/export/ExportImportRoundtripTest.kt`

### Documentation

- [ ] T530 Update API documentation for new repository methods
  - File: `/Users/davidnutting/dev/nuttingd/pocket-llm/app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`
  - KDoc comments for all public methods
