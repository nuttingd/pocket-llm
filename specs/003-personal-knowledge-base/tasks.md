# Implementation Tasks: Personal Knowledge Base

**Feature**: `003-personal-knowledge-base`  
**Branch**: `003-personal-knowledge-base`  
**Last Updated**: 2026-02-27  
**Spec**: [spec.md](./spec.md)

---

## Overview

This document outlines all implementation tasks for the Personal Knowledge Base feature, organized by phase. Each task includes file paths, implementation details, and dependencies.

**Total Tasks**: 53 (setup: 2, foundational: 40, US1: 15, US2: 9, US3: 7, US4: 15, polish: 25)

---

## Phase 1: Setup

### T001 [Setup] Configure database schema migration
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`

- Increment database version from 6 to 7
- Add new entities to `@Database` annotation:
  - `DocumentEntity::class`
  - `DocumentContentEntity::class`
  - `DocumentFts::class`
  - `ConversationLinkEntity::class`
- Create migration script `MIGRATION_6_7` in `companion object`
- Test migration with empty database and existing data (if any)

**Dependencies**: None (Foundation task)

---

### T002 [Setup] Add new dependencies to build.gradle.kts
**Status**: [X] COMPLETE
**Path**: `app/build.gradle.kts`

- Add pdfbox-android 2.0.27.0 for PDF text extraction
- Add docx4j-core 8.3.1 for DOCX text extraction  
- Verify kotlinx-serialization-json version (must be >= 1.8.0)
- Add WorkManager dependencies if not already present

**Dependencies**: None (Foundation task)

---

## Phase 2: Foundational (Database Schema & Core Infrastructure)

### T010 [Setup] Create DocumentEntity
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentEntity.kt`

- Define data class with all fields from spec
- Apply Room annotations (`@Entity`, `@PrimaryKey`, `@ColumnInfo`)
- Set table name: `"documents"`
- Add validation constraints in comments

**Dependencies**: T001 (Database schema migration)

---

### T011 [Setup] Create DocumentContentEntity
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentContentEntity.kt`

- Define data class with `documentId` and `content` fields
- Set primary key: `"document_id"`
- Add foreign key constraint to `DocumentEntity`
- Add cascade delete behavior

**Dependencies**: T010 (DocumentEntity)

---

### T012 [Setup] Create DocumentFts (FTS4 Virtual Table)
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/DocumentFts.kt`

- Define data class with single `content` field
- Annotate with `@Fts4(contentEntity = DocumentContentEntity::class)`
- Set table name: `"document_fts"`

**Dependencies**: T010 (DocumentEntity), T011 (DocumentContentEntity)

---

### T013 [Setup] Create ConversationLinkEntity
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/ConversationLinkEntity.kt`

- Define data class with fields from spec
- Set up foreign keys for both `conversationId` and `documentId`
- Add unique constraint on `(conversationId, documentId)` pair

**Dependencies**: T010 (DocumentEntity)

---

### T020 [Setup] Create DocumentMetadata model
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocumentMetadata.kt`

- Define data class for parsed metadata
- All fields nullable: title, author, creationDate, modifiedDate, keywords

**Dependencies**: None (Pure Kotlin class)

---

### T021 [Setup] Create DocumentProcessor interface
**Status**: [X] COMPLETE
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocumentProcessor.kt`

- Define `canHandle(mimeType: String, extension: String): Boolean`
- Define `process(inputStream: InputStream, documentId: String): DocumentProcessingResult`
- Include default implementations

**Dependencies**: T020 (DocumentMetadata)

---

### T022 [Setup] Create PdfDocumentProcessor implementation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/PdfDocumentProcessor.kt`

- Implement `canHandle()` for PDF MIME type and extension
- Use pdfbox-android to extract text from InputStream
- Return DocumentProcessingResult with content and metadata

**Dependencies**: T021 (DocumentProcessor interface), pdfbox-android dependency (T002)

---

### T023 [Setup] Create TextDocumentProcessor implementation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/TextDocumentProcessor.kt`

- Handle `.txt` and `.md` files
- Detect encoding from BOM or fallback to UTF-8
- Parse metadata from Markdown frontmatter if present

**Dependencies**: T021 (DocumentProcessor interface)

---

### T024 [Setup] Create DocxDocumentProcessor implementation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/processor/DocxDocumentProcessor.kt`

- Handle DOCX files using docx4j-core
- Extract text content and document properties (title, author)

**Dependencies**: T021 (DocumentProcessor interface), docx4j-core dependency (T002)

---

### T030 [Setup] Create DocumentDao interface
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/dao/DocumentDao.kt`

- CRUD operations: `insert()`, `update()`, `delete()`, `getById()`
- Search query using FTS4 MATCH operator
- Statistics queries: `getCount()`, `getTotalSizeBytes()`

**Dependencies**: T010 (DocumentEntity), T012 (DocumentFts)

---

### T031 [Setup] Create DocumentContentDao interface
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/dao/DocumentContentDao.kt`

- CRUD operations for content table
- `getContentByDocumentId()` query
- Batch insert/update support

**Dependencies**: T011 (DocumentContentEntity)

---

### T032 [Setup] Create ConversationLinkDao interface
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/dao/ConversationLinkDao.kt`

- CRUD operations for conversation links
- Get all documents linked to a conversation
- Count linked documents per conversation

**Dependencies**: T013 (ConversationLinkEntity)

---

### T040 [Setup] Create DocumentRepository interface
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`

- Abstract away DAO layer from use cases
- High-level methods: `uploadDocument()`, `searchDocuments()`, `linkToConversation()`
- Return Flow-based queries for reactive UI

**Dependencies**: T030 (DocumentDao), T031 (DocumentContentDao), T032 (ConversationLinkDao)

---

### T041 [Setup] Implement DocumentRepository
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`

- Implement all interface methods using DAO calls
- Add deduplication logic based on content hash
- Handle WorkManager worker scheduling

**Dependencies**: T040 (DocumentRepository interface)

---

### T050 [Setup] Create Export Format Models
**Path**: `app/src/main/java/dev/nutting/pocketllm/domain/model/KnowledgeBaseManifest.kt`

- Define `KnowledgeBaseManifest` with version, exportedAt, documentCount, entries
- Define `DocumentEntry` with all metadata fields including contentHash (SHA-256)

**Dependencies**: kotlinx-serialization-json dependency (T002)

---

### T051 [Setup] Create SHA-256 Utility
**Path**: `app/src/main/java/dev/nutting/pocketllm/util/HashUtils.kt`

- Implement `calculateSha256(text: String): String` function
- Handle byte array conversion and hex encoding

**Dependencies**: None (Pure utility)

---

### T052 [Setup] Create Export Result Models
**Path**: `app/src/main/java/dev/nutting/pocketllm/domain/model/ExportResult.kt`

- Define `ExportResult`, `ImportResult` sealed classes
- Include outcomes: success, failure, skipped duplicates

**Dependencies**: None (Pure data models)

---

## Phase 3: US1 - Upload and Store Documents

### T100 [US1] Implement File Picker Integration
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/FilePickerDialog.kt`

- Use Android Storage Access Framework (SAF)
- Support all document providers (device, cloud)
- Filter by supported MIME types

**Dependencies**: T020-T024 (Processors), T010-T013 (Entities)

---

### T101 [US1] Create DocumentUploadUiState
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentUploadUiState.kt`

- Define state: `Idle`, `SelectingFile`, `Processing`, `Success`, `Failed`
- Include error messages and progress indicators

**Dependencies**: None (UI layer only)

---

### T102 [US1] Implement UploadViewModel
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadViewModel.kt`

- Handle file selection flow
- Validate document before upload
- Coordinate with Repository for storage

**Dependencies**: T041 (DocumentRepository), T101 (UiState)

---

### T103 [US1] Create Document Upload Worker
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/UploadDocumentWorker.kt`

- Extend WorkManager Worker class
- Process document in background thread
- Call appropriate DocumentProcessor based on MIME type
- Update UI state with progress

**Dependencies**: T041 (DocumentRepository), T020-T024 (Processors)

---

### T104 [US1] Implement Document List Screen UI
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentListScreen.kt`

- Material 3 list layout with cards
- Display: title, filename, size, upload date, processing state
- Add "Add Document" FAB button
- Show progress indicators during upload

**Dependencies**: T102 (UploadViewModel), T010 (DocumentEntity)

---

### T105 [US1] Implement Document Detail Screen
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentDetailScreen.kt`

- Show full document metadata
- Display preview of extracted content
- Add edit/delete actions
- Show processing status clearly

**Dependencies**: T102 (UploadViewModel)

---

### T106 [US1] Create Processing State UI States
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/ProcessingStateChip.kt`

- Implement chips for: pending, processing, available, failed states
- Visual distinction for failed uploads with retry option

**Dependencies**: None (UI component)

---

### T107 [US1] Implement Duplicate Detection UI Feedback
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/FilePickerDialog.kt`

- Check content hash before upload
- Show warning dialog for duplicates
- Option to proceed anyway or cancel

**Dependencies**: T051 (HashUtils), T104 (DocumentListScreen)

---

### T108 [US1] Add Document Validation Logic
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/validator/DocumentValidator.kt`

- Validate file size < 50MB extracted text
- Check supported MIME types
- Handle unsupported formats gracefully

**Dependencies**: T020-T024 (Processors)

---

### T109 [US1] Implement Document Deletion Flow
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/DocumentDeleter.kt`

- Soft delete by setting `deleted_at` timestamp
- Update UI state to reflect deletion
- Handle linked documents warning

**Dependencies**: T041 (DocumentRepository)

---

### T110 [US1] Create Export Preview Screen
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/ExportPreviewScreen.kt`

- Show total document count and combined size
- Display export format info (ZIP with manifest)
- Confirm button to proceed

**Dependencies**: T041 (DocumentRepository)

---

### T111 [US1] Implement Export Progress Tracking
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/ExportProgressScreen.kt`

- Show progress bar during export
- Display estimated time remaining
- Cancel button support

**Dependencies**: T041 (DocumentRepository)

---

### T112 [US1] Add Upload Error Handling UI
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadErrorDialog.kt`

- Clear error messages for different failure modes
- Retry options for transient failures
- Manual override for permanent failures

**Dependencies**: T104 (DocumentListScreen)

---

### T113 [US1] Implement Document Search in Upload Flow
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/upload/UploadViewModel.kt`

- Allow user to search existing docs before upload
- Show duplicate warnings with preview

**Dependencies**: T041 (DocumentRepository)

---

### T114 [US1] Add Document Statistics Dashboard
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/StatsCard.kt`

- Total documents, total size, word count
- Usage trends over time
- Quota warnings approaching 500MB limit

**Dependencies**: T041 (DocumentRepository)

---

### T120 [US1] Test Upload Flow End-to-End
**Path**: `tests/androidTest/documents/UploadFlowTest.kt`

- Test PDF upload and extraction
- Test TXT/MD upload and parsing
- Test DOCX upload with metadata
- Verify database persistence after restart

**Dependencies**: All T1xx tasks above

---

### T130 [US1] Add Accessibility to Document List
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentListScreen.kt`

- TalkBack labels for all interactive elements
- Touch target minimum 48dp
- Semantic roles properly set
- Screen reader order correct

**Dependencies**: T104 (DocumentListScreen)

---

### T131 [US1] Performance Test Large Document Upload
**Path**: `tests/androidTest/documents/LargeDocumentUploadTest.kt`

- Upload and process 50MB PDF
- Verify no ANR occurred
- Check memory usage during processing

**Dependencies**: T103 (UploadDocumentWorker)

---

## Phase 4: US2 - Search Documents by Content

### T200 [US2] Create SearchBar UI Component
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/SearchBar.kt`

- Material search input with icon
- Clear button functionality
- Auto-focus on dialog open

**Dependencies**: None (UI component)

---

### T201 [US2] Implement Search ViewModel
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/search/SearchViewModel.kt`

- Observe document search Flow from Repository
- Filter results as user types
- Highlight matching terms in preview

**Dependencies**: T041 (DocumentRepository), T200 (SearchBar)

---

### T202 [US2] Create Search Results Screen
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/search/SearchResultsScreen.kt`

- List of matching documents with rankings
- Show document preview snippets with highlighted terms
- Empty state message when no results

**Dependencies**: T201 (SearchViewModel)

---

### T203 [US2] Implement Highlighting Logic
**Path**: `app/src/main/java/dev/nutting/pocketllm/util/HighlightParser.kt`

- Parse FTS rank results to identify matching terms
- Extract surrounding context (50 characters before/after)
- Apply HTML span styling for highlighting

**Dependencies**: None (Utility function)

---

### T204 [US2] Create Search Query History
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/SearchHistoryEntity.kt`

- Store recent search queries in separate table
- Limit to last 10 searches per user

**Dependencies**: None (New entity)

---

### T205 [US2] Implement Search History Dao
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/dao/SearchHistoryDao.kt`

- Insert query with timestamp
- Get recent queries ordered by timestamp
- Clear history functionality

**Dependencies**: None (New DAO)

---

### T206 [US2] Add Search to Conversation Context
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/linkeddocuments/SearchableLinkAdapter.kt`

- Inline search in conversation attachment dialog
- Show results before linking

**Dependencies**: T201 (SearchViewModel)

---

### T207 [US2] Add FTS Tokenizer Configuration
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`

- Verify FTS4 uses simple tokenizer for UTF-8 support
- Test non-Latin script searches

**Dependencies**: T001 (Database setup)

---

### T208 [US2] Performance Test Search Query
**Path**: `tests/androidTest/documents/SearchPerformanceTest.kt`

- Measure query time with 1000 documents
- Verify < 5 second response per acceptance criteria

**Dependencies**: All search implementation tasks

---

## Phase 5: US3 - Link Documents to Conversations

### T300 [US3] Create LinkedDocumentsUiState
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/LinkedDocumentsUiState.kt`

- State for showing/hiding linked documents section
- Track added/removed links in session

**Dependencies**: None (UI state)

---

### T301 [US3] Implement Link Button in Conversation UI
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/ConversationScreen.kt`

- Add link icon to conversation top bar
- Open document picker when tapped

**Dependencies**: T200 (SearchBar), T104 (DocumentListScreen)

---

### T302 [US3] Create Document Picker for Linking
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/LinkedDocumentPicker.kt`

- Reuse DocumentListScreen with link action instead of view
- Show existing links as pre-selected

**Dependencies**: T104 (DocumentListScreen)

---

### T303 [US3] Implement Linking Logic in ViewModel
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/ChatViewModel.kt`

- Add method to link document to conversation ID
- Update UI state with new links

**Dependencies**: T041 (DocumentRepository)

---

### T304 [US3] Create LinkedDocumentsSection UI
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/conversation/LinkedDocumentsSection.kt`

- Display linked documents in conversation header
- Show warning badge for deleted documents
- Remove link functionality

**Dependencies**: T301 (Link Button)

---

### T305 [US3] Implement Context Injection to AI Request
**Path**: `app/src/main/java/dev/nutting/pocketllm/domain/ChatManager.kt`

- Include linked document content in prompt context
- Enforce ~16KB total limit per conversation
- Warn user when exceeding limit

**Dependencies**: T041 (DocumentRepository), ChatManager existing code

---

### T306 [US3] Test Conversation Linking Flow
**Path**: `tests/androidTest/documents/ConversationLinkingTest.kt`

- Link document to new conversation
- Verify content appears in AI context
- Delete linked document and verify warning appears

**Dependencies**: All US3 implementation tasks

---

## Phase 6: US4 - Export/Import Knowledge Base

### T400 [US4] Implement Manifest Serialization
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/export/KnowledgeBaseExporter.kt`

- Serialize KnowledgeBaseManifest to JSON
- Write manifest.json to output directory

**Dependencies**: T050 (Export Models), kotlinx-serialization-json (T002)

---

### T401 [US4] Implement Document Copy for Export
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/export/DocumentCopier.kt`

- Copy original files to export directory
- Preserve folder structure matching manifest entries

**Dependencies**: T400 (Manifest Serialization)

---

### T402 [US4] Create ZIP Archive Generation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/export/ZipArchiver.kt`

- Use Java ZipOutputStream
- Add manifest.json and documents folder to archive

**Dependencies**: T401 (Document Copier)

---

### T403 [US4] Implement Import Manifest Parsing
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/import/KnowledgeBaseImporter.kt`

- Deserialize manifest.json from ZIP
- Validate JSON structure and required fields

**Dependencies**: T050 (Export Models)

---

### T404 [US4] Implement Import Deduplication Logic
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/import/DocumentImporter.kt`

- Check content hash against existing documents
- Handle filename conflicts with rename option

**Dependencies**: T051 (HashUtils), T403 (Manifest Parsing)

---

### T405 [US4] Implement Import Progress UI
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/import/ImportProgressScreen.kt`

- Show import progress bar
- Display documents imported/skipped counts

**Dependencies**: T404 (Deduplication Logic)

---

### T406 [US4] Create Import File Picker
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/import/ImportFilePicker.kt`

- Filter for ZIP files only
- Support SAF integration

**Dependencies**: None (UI component)

---

### T407 [US4] Implement Import Confirmation Dialog
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/import/ImportPreviewDialog.kt`

- Show document count and total size from manifest
- Warn about potential duplicates

**Dependencies**: T405 (Progress UI)

---

### T408 [US4] Add Import Error Handling
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/import/ImportErrorDialog.kt`

- Handle corrupted ZIP files
- Invalid manifest JSON errors
- Hash verification failures

**Dependencies**: T406 (File Picker)

---

### T409 [US4] Implement Import/Export Settings Entry
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/settings/SettingsScreen.kt`

- Add section for knowledge base management
- Export and Import buttons

**Dependencies**: All US4 implementation tasks

---

### T410 [US4] Create Export Filename Generator
**Path**: `app/src/main/java/dev/nutting/pocketllm/util/FilenameUtils.kt`

- Generate: `pocketllm_kb_export_YYYYMMDDTHHmmssZ.zip`
- Validate filename characters

**Dependencies**: None (Utility)

---

### T411 [US4] Implement Import Validation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/import/ImportValidator.kt`

- Verify ZIP integrity before extraction
- Check manifest version compatibility
- Ensure content hashes are present

**Dependencies**: T051 (HashUtils)

---

### T412 [US4] Add Import Conflict Resolution
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/import/ImportConflictDialog.kt`

- Offer options: Skip, Overwrite, Rename
- Preview filename for rename option

**Dependencies**: T405 (Progress UI)

---

### T413 [US4] Test Export Import Roundtrip
**Path**: `tests/androidTest/documents/ExportImportRoundtripTest.kt`

- Export knowledge base with multiple documents
- Delete all documents from app
- Re-import and verify restoration

**Dependencies**: All US4 implementation tasks

---

### T414 [US4] Verify Manifest SHA-256 Integrity
**Path**: `tests/unit/export/ExportImportHashTest.kt`

- Generate known hash for test content
- Verify exported manifest matches
- Confirm import fails with modified file

**Dependencies**: T051 (HashUtils)

---

## Phase 7: Polish & Cross-Cutting Concerns

### T500 [Setup] Add Database Indexes
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`

- Create indexes for common query patterns:
  - Title search, filename search
  - Upload date sorting
  - Conversation link lookups

**Dependencies**: T001 (Database migration)

---

### T501 [Setup] Add Database Triggers
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`

- FTS4 sync triggers for document_content updates
- Cascade delete from documents to content/fts

**Dependencies**: T001 (Database migration)

---

### T502 [Setup] Implement Content Hash Generation
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/PocketLlmDatabase.kt`

- Trigger-based hash update before insert/update
- Store in content table or separate hash column

**Dependencies**: None (Implementation detail)

---

### T503 [Setup] Add Memory-Efficient Search Streaming
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/repository/DocumentRepository.kt`

- Stream FTS results instead of loading all at once
- Use Room Flow with pagination support

**Dependencies**: T041 (DocumentRepository)

---

### T504 [Setup] Implement Document Quota Warning
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/QuotaWarningDialog.kt`

- Show when approaching 500MB limit
- Offer cleanup suggestions

**Dependencies**: T120 (Upload flow tests)

---

### T505 [Setup] Add Logging Strategy
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/Logger.kt`

- Log document processing errors at ERROR level
- Correlate with user-facing messages

**Dependencies**: None (Utility)

---

### T506 [Setup] Implement Crash Recovery
**Path**: `app/src/main/java/dev/nutting/pocketllm/data/local/CrashRecoveryManager.kt`

- Detect incomplete uploads on startup
- Clean up partial files and reset states

**Dependencies**: T103 (UploadDocumentWorker)

---

### T507 [Setup] Add Analytics Events
**Path**: `app/src/main/java/dev/nutting/pocketllm/util/AnalyticsEvents.kt`

- Document upload count, size, format distribution
- Search query frequency, no-results rate

**Dependencies**: None (Utility)

---

### T508 [Setup] Implement Theme Integration
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/theme/PkbTheme.kt`

- Apply Material Design 3 to all new screens
- Follow existing app color scheme

**Dependencies**: All UI tasks above

---

### T509 [Setup] Add Keyboard Navigation Support
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/DocumentListScreen.kt`

- Ensure FAB reachable via keyboard
- Search bar focus management

**Dependencies**: None (Accessibility)

---

### T510 [Setup] Implement TalkBack Labels
**Path**: All UI files from T1xx-T4xx range

- Complete accessibility audit
- Verify screen reader order
- Test with TalkBack enabled

**Dependencies**: All UI tasks above

---

### T511 [Setup] Performance Optimizations
**Path**: `app/src/main/java/dev/nutting/pocketllm/ui/documents/`

- Lazy column for document list
- Image loading optimization (if applicable)
- Debounce search input

**Dependencies**: None (Performance tuning)

---

### T512 [Setup] Add Unit Tests
**Path**: `tests/unit/processor/`, `tests/unit/export/`, etc.

- DocumentProcessor implementations
- Export/import logic
- Hash calculation utilities
- Validator functions

**Dependencies**: All implementation tasks

---

### T513 [Setup] Add Instrumented Tests
**Path**: `tests/androidTest/documents/`

- End-to-end upload flow
- Search integration
- Conversation linking
- Export/import roundtrip

**Dependencies**: All implementation tasks

---

### T514 [Setup] Run Lint Checks
**Path**: `./gradlew lintDebug`

- Fix all lint errors and warnings
- Add suppress annotations only where justified

**Dependencies**: None (QA phase)

---

### T515 [Setup] Verify Build Success
**Path**: `./gradlew assembleDebug`

- Ensure zero compilation errors
- Check proguard/R8 rules if applicable

**Dependencies**: None (QA phase)

---

### T516 [Setup] Run Unit Tests
**Path**: `./gradlew test`

- 90%+ code coverage target
- Verify all new functionality tested

**Dependencies**: All unit test tasks above

---

### T517 [Setup] Accessibility Audit
**Path**: Manual + TalkBack testing

- Verify all screens pass accessibility checks
- Test with different font sizes and system scales

**Dependencies**: None (QA phase)

---

### T518 [Setup] Performance Benchmarking
**Path**: Manual testing with Android Studio Profiler

- Document upload time < 5 seconds for 10-page PDF
- Search response < 200ms for 100 documents
- Export time < 1 second

**Dependencies**: All implementation tasks

---

### T519 [Setup] Documentation Update
**Path**: `docs/003-personal-knowledge-base.md`

- Feature overview
- User guide for upload/search/linking
- Technical architecture notes

**Dependencies**: None (Documentation)

---

### T520 [Setup] Code Review Preparation
**Path**: Self-review and cleanup

- Remove debug logging
- Optimize imports
- Ensure consistent formatting
- Add KDoc comments where non-obvious

**Dependencies**: None (Final phase)

---

### T521 [Setup] Create Feature Branch PR Template
**Path**: `.github/PULL_REQUEST_TEMPLATE/pkb-feature.md`

- Checklist of all acceptance criteria
- Testing verification section
- Known issues and future work

**Dependencies**: None (Process improvement)

---

### T522 [Setup] Update CHANGELOG.md
**Path**: `CHANGELOG.md`

- Add entry for 003-personal-knowledge-base feature
- List breaking changes if any
- Credit contributors

**Dependencies**: None (Release prep)

---

### T523 [Setup] Final Acceptance Testing
**Path**: Full spec acceptance criteria verification

- All 4 user stories tested
- Edge cases handled per spec
- Performance targets met

**Dependencies**: All implementation and testing tasks above

---

## Task Dependency Map

```
Phase 1 (Setup) ────────────────► Phase 2 (Foundational)
      │                                    │
      ▼                                    ▼
   T001,T002                            T010-T052
                                            │
         ┌──────────────────────────────────┼──────────────────────────┐
         │                                  │                          │
         ▼                                  ▼                          ▼
    Phase 3 (US1)                      Phase 4 (US2)              Phase 5 (US3)
    Upload & Store                   Search Documents             Link Conversations
      T100-T131                         T200-T208                    T300-T306
         │                                  │                          │
         └──────────────────────────────────┴──────────────────────────┘
                                          ▼
                                   Phase 6 (US4)
                                 Export/Import
                                    T400-T414
                                          │
                                          ▼
                                 Phase 7 (Polish)
                               Cross-Cutting
                                T500-T523
```

---

## Estimated Effort

| Phase | Tasks | Estimate |
|-------|-------|----------|
| Setup | 2 | 0.5 days |
| Foundational | 40 | 10 days |
| US1 - Upload/Store | 15 | 5 days |
| US2 - Search | 9 | 3 days |
| US3 - Link Conversations | 7 | 2 days |
| US4 - Export/Import | 15 | 5 days |
| Polish & Cross-Cutting | 25 | 8 days |
| **Total** | **113** | **33.5 days** |

**Note**: This represents sequential estimate. Parallelization (especially UI and data layer) can reduce timeline to ~2-3 weeks for full implementation.
