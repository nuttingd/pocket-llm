# Implementation Plan: Personal Knowledge Base

**Branch**: `003-personal-knowledge-base` | **Date**: 2026-02-27 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-personal-knowledge-base/spec.md`

## Summary

Implement a personal knowledge base feature that allows users to upload documents (PDF, TXT, Markdown, DOCX), search their content using keyword-based full-text search, and link relevant documents to conversations for context-aware AI responses. The system will use SQLite FTS4 through Room database for indexing, WorkManager for background document processing, and provide export/import functionality with SHA-256 integrity verification.

## Technical Context

**Language/Version**: Kotlin (managed by Kotlin Gradle Plugin 2.2.10), JDK 21  
**Primary Dependencies**: 
- Room 2.8.4 for database and FTS4
- pdfbox-android 2.0.27.0 for PDF text extraction
- docx4j-core 8.3.1 for DOCX text extraction
- kotlinx-serialization-json 1.8.0 for export/import formats
- WorkManager 2.9.0 for background processing

**Storage**: Room database with FTS4 virtual tables for search indexing (documents, document_content, document_fts tables)  
**Testing**: JUnit 4 + Robolectric for local tests; Room test helpers for database integration  
**Target Platform**: Android (minSdk 28, targetSdk 36, compileSdk 36), arm64-v8a architecture

**Project Type**: Mobile app (Android) - single Gradle module with MVVM-Compose architecture  
**Performance Goals**: 
- Document upload + processing: <5 seconds for 10-page PDF
- Search query response: <200ms for up to 1000 documents
- Export generation: <1 second

**Constraints**: 
- Local-first design (no cloud storage)
- Must work offline once initial setup complete
- Document processing in background via WorkManager (prevents ANR)
- Total database size should not exceed 500MB for typical user (100 docs @ ~5MB average)

**Scale/Scope**: 
- Single-user personal knowledge base
- Support up to 100 documents (estimated 500MB total storage)
- Search index must handle up to 1M+ words across documents

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Core Principles Verification

| Principle | Requirement | Status |
|-----------|-------------|--------|
| **I. MVVM-Compose Single Module** | Feature uses single `app/` module, ViewModels with `UiState`, Jetpack Compose UI | ✅ PASS - No module structure changes needed |
| **II. Simplicity & No Premature Abstraction** | DocumentProcessor interface needed for format handling (multiple implementations); Repository layer serves multiple consumers (UI + export/import) | ✅ PASS - Both patterns serve clear purposes |
| **III. Test Coverage — Value Over Volume** | Tests target: parsers, FTS queries, export/import logic; skip trivial getters | ✅ PASS - Clear test scope defined |
| **IV. Conventional Commits & Semantic Release** | All commits will follow `feat(pkb):` or `refactor(pkb):` patterns | ✅ PASS - Already in use |
| **V. Accessibility-First** | Document list screen uses Material 3 lists, search bar has contentDescription, touch targets meet 48dp minimum | ✅ PASS - Standard Material components used |
| **VI. BDD Spec-Driven Development** | Acceptance scenarios defined for all user stories; tests written in Given/When/Then format | ✅ PASS - Spec-driven approach documented |
| **VII. Polished Material Design UX** | Uses TopAppBar, NavigationDrawer patterns from existing app; proper loading/error states | ✅ PASS - Consistent with existing UI patterns |
| **VIII. Debuggable Error Handling** | Document processing errors logged at ERROR level; user-facing messages have corresponding logcat entries | ✅ PASS - Logging strategy documented |

### Five Primitives Alignment

| Primitive | Connection to Feature | Impact |
|-----------|----------------------|--------|
| **Conversation Tree** | Documents can be linked to conversation threads; extends existing message tree context | ⭐⭐⭐ Strengthens - adds document references as conversation context nodes |
| **Dual Inference Pipeline** | Not directly affected, but linked documents enhance LLM context for both remote and local models | ⭐⭐ Indirect enhancement |
| **Stream State Machine** | Document upload processed asynchronously via WorkManager; status updates to UI state | ⭐⭐ Integration pattern established |
| **Model Lifecycle** | Not affected - document management is separate from model download/loading | ⭐ No impact |
| **Parameter Space** | Not affected - document search doesn't use generation parameters | ⭐ No impact |

### Quality Gates Verification

- [x] `./gradlew assembleDebug` will succeed with zero errors (no native changes needed)
- [x] `./gradlew test` will pass all unit tests ( parsers, export/import logic)
- [x] `./gradlew lintDebug` reports zero errors (uses existing dependency versions)
- [ ] Every new screen MUST be verified with TalkBack before story complete
- [ ] Every user story acceptance scenarios MUST pass before completion

**Status**: ✅ **PASSED** - All principle checks satisfied. Proceed to Phase 0 research.

## Project Structure

### Documentation (this feature)

```text
specs/003-personal-knowledge-base/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
└── contracts/           # Phase 1 output (/speckit.plan command)
    └── export-format.json
```

### Source Code (repository root)

```text
app/src/main/java/dev/nutting/pocketllm/
├── data/
│   ├── local/
│   │   ├── model/
│   │   │   ├── DocumentEntity.kt          # Room entity: documents table
│   │   │   ├── DocumentContentEntity.kt  # Room entity: document_content table
│   │   │   └── DocumentMetadata.kt       # Parsed metadata structure
│   │   ├── dao/
│   │   │   ├── DocumentDao.kt            # CRUD + search DAO
│   │   │   └── DocumentContentDao.kt     # Content storage DAO
│   │   └── processor/
│   │       ├── DocumentProcessor.kt      # Interface
│   │       ├── PdfDocumentProcessor.kt   # PDF parsing
│   │       ├── TextDocumentProcessor.kt  # TXT/MD parsing
│   │       └── DocxDocumentProcessor.kt  # DOCX parsing
│   └── repository/
│       └── DocumentRepository.kt         # Repository layer (UI + export/import consumers)
├── domain/
│   ├── model/
│   │   └── KnowledgeBaseManifest.kt      # Export format model
│   └── usecase/
│       ├── UploadDocumentUseCase.kt      # Business logic for upload flow
│       └── SearchDocumentsUseCase.kt     # Business logic for search
├── ui/
│   ├── documents/
│   │   ├── DocumentListScreen.kt         # Main documents UI
│   │   ├── DocumentDetailScreen.kt       # Individual document view
│   │   └── upload/
│   │       ├── UploadViewModel.kt
│   │       └── FilePickerDialog.kt
│   └── conversation/
│       └── LinkedDocumentsSection.kt     # Integration point in chat UI

tests/
├── unit/                                 # Local JVM tests
│   ├── processor/
│   │   ├── PdfDocumentProcessorTest.kt
│   │   └── DocumentRepositoryTest.kt
│   └── export/
│       └── ExportImportTest.kt
└── androidTest/                          # Instrumented tests
    └── documents/
        ├── UploadFlowTest.kt
        ├── SearchIntegrationTest.kt
        └── ExportImportRoundtripTest.kt
```

**Structure Decision**: Single module structure maintained per Constitution Principle I. Feature follows existing patterns:
- `data/local/` mirrors `message/` directory structure for consistency
- `ui/documents/` mirrors `chat/` screen organization
- Repository layer added only because DocumentRepository serves multiple consumers (UI + export/import)

## Complexity Tracking

> **No violations detected** - Feature aligns with all constitutional principles without requiring exceptions.
