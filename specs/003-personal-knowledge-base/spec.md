# Feature Specification: Personal Knowledge Base

**Feature Branch**: `003-personal-knowledge-base`  
**Created**: 2026-02-27  
**Status**: Draft  
**Input**: User description: "based on our recent roadmapping, let's start the first major feature"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upload and Store Documents (Priority: P1)

A user wants to add documents to their personal knowledge base by selecting files from their device or downloading them from cloud storage. Once uploaded, documents are stored locally and available for search and reference in conversations.

**Why this priority**: Document upload is the foundation of the entire feature - without the ability to add content, there's nothing to search or link. This is the essential first step that enables all other functionality.

**Independent Test**: Can be fully tested by selecting a document file from device storage and verifying it appears in a document list with metadata (name, size, upload date). Delivers value: user now has their documents stored on-device for AI reference.

**Acceptance Scenarios**:

1. **Given** the app is installed and running, **When** the user taps the "Add Document" button, **Then** they see a file picker showing available document sources (device storage, cloud providers if configured)
2. **Given** the user has selected a document file, **When** they confirm upload, **Then** the system processes the file and stores it in local device storage
3. **Given** a document has been uploaded, **When** the user navigates to Documents screen, **Then** they see a list of all their documents with basic metadata (title, size, date added)
4. **Given** a document is stored, **When** the device restarts, **Then** the document remains accessible without re-downloading

---

### User Story 2 - Search Documents by Content (Priority: P1)

A user wants to find specific information within their knowledge base by typing natural language queries. The search returns relevant documents and potentially specific passages from those documents.

**Why this priority**: Without search, documents are just stored away with no way to find what you need. Search transforms the knowledge base from a file cabinet into an accessible information resource that the AI can use.

**Independent Test**: Can be fully tested by entering a search query and verifying results match documents containing relevant terms. Delivers value: user can instantly locate any document or passage they've previously added.

**Acceptance Scenarios**:

1. **Given** the user has uploaded at least one document, **When** they enter a search query in the search bar, **Then** the system displays results ordered by relevance to their query
2. **Given** multiple documents contain matching terms, **When** results are displayed, **Then** they are sorted with most relevant first (based on keyword frequency and position)
3. **Given** no documents match a query, **When** search completes, **Then** the system shows an empty state message suggesting alternative phrasing or related topics
4. **Given** the user is in a conversation, **When** they reference "search my knowledge base", **Then** they can enter a query and the results are shown inline

---

### User Story 3 - Link Documents to Conversations (Priority: P2)

A user wants to associate specific documents with a conversation so that when discussing topics related to those documents, the AI has automatic access to the relevant content.

**Why this priority**: This enables the core value proposition of V2 - making conversations context-aware by leveraging stored knowledge. While important, users can still search manually without this linking feature, making it P2.

**Independent Test**: Can be fully tested by starting a conversation and attaching documents to it, then verifying those documents appear in the conversation's linked items. Delivers value: when discussing that topic, AI automatically references relevant stored content.

**Acceptance Scenarios**:

1. **Given** the user is viewing a conversation, **When** they tap "Attach Document", **Then** they see their document library and can select one or more documents
2. **Given** documents are attached to a conversation, **When** a message is sent in that conversation, **Then** the system includes document content in the context sent to the model
3. **Given** a conversation has linked documents, **When** the conversation is reopened, **Then** all previously attached documents remain linked
4. **Given** a linked document is updated or deleted, **When** the user views that conversation, **Then** they see a clear indication of the document status (updated/deleted)

---

### User Story 4 - Export Knowledge Base (Priority: P3)

A user wants to export their entire knowledge base for backup purposes or to transfer it to another device.

**Why this priority**: This is important for data ownership and portability, but users can continue using the system without immediate export capability. The system works fine with documents stored locally; export is a convenience/backup feature.

**Independent Test**: Can be fully tested by selecting "Export All Documents" and verifying the resulting file contains all uploaded content in a portable format (ZIP or similar). Delivers value: user has a complete backup of their knowledge base they control.

**Acceptance Scenarios**:

1. **Given** the user has documents in their knowledge base, **When** they select "Export Knowledge Base", **Then** they see a preview showing total document count and combined size
2. **Given** export is initiated, **When** the process completes, **Then** the system produces a single ZIP file containing all documents with metadata preserved
3. **Given** an exported knowledge base exists, **When** the user imports it on another device, **Then** all documents are restored with their original metadata intact

---

### Edge Cases

- What happens when a user tries to upload a document type that cannot be processed (e.g., executable files, encrypted archives)? → Document is stored with `processing_state: failed`, displayed in documents list with clear visual indication that it cannot be searched or linked. User can retry or delete.
- How does the system handle very large documents that might strain device memory during search processing? → Search processes content incrementally; if document exceeds ~50MB extracted text, user receives warning before indexing completes but can choose to proceed.
- What happens if the user's device runs out of storage space during document upload? → Upload fails with clear error message showing available disk space; any partially written files are cleaned up automatically.
- How does search behave when documents contain non-Latin scripts or mixed-language content? → UTF-8 aware keyword matching works across all supported scripts; language metadata is stored per-document but not used for query analysis in V1.
- What is the experience when linked documents are removed while a conversation is active? → Linked documents are soft-deleted (marked with `deleted_at` timestamp) rather than immediately purged; conversations with deleted links show warning badge and indicate which documents are missing.

## Requirements *(mandatory)*

### Functional Requirements

#### Document Management (FR-Doc)

- **FR-Doc-001**: System MUST allow users to select and upload documents from device storage
- **FR-Doc-002**: System MUST support common document formats: PDF, plain text (.txt), Markdown (.md), and DOCX
- **FR-Doc-003**: System MUST extract and store the document's title (either from filename or content metadata) AND detect/store primary language code
- **FR-Doc-004**: System MUST display document size and upload date in the documents list
- **FR-Doc-005**: System MUST prevent duplicate uploads of identical documents (based on SHA-256 content hash)
- **FR-Doc-006**: System MUST store all documents locally on device storage only (no cloud servers)
- **FR-Doc-007**: System MUST provide an option to delete individual documents from the knowledge base
- **FR-Doc-008**: System MUST show a progress indicator during document upload processing
- **FR-Doc-009**: System MUST store documents with a `processing_state` field: `pending`, `processing`, `available`, or `failed`
- **FR-Doc-010**: If document parsing fails, system MUST display clear visual indication in the documents list that the document cannot be searched or linked
- **FR-Doc-011**: System MUST soft-delete documents (mark with `deleted_at` timestamp) rather than immediately purging them when user requests deletion

#### Search Functionality (FR-Search)

- **FR-Search-001**: System MUST allow users to enter natural language queries in a search interface
- **FR-Search-002**: System MUST return documents ordered by relevance to the query terms based on keyword frequency and position within documents
- **FR-Search-003**: System MUST highlight matching terms within document titles and content previews
- **FR-Search-004**: System MUST handle empty searches gracefully (show all documents or appropriate message)
- **FR-Search-005**: System MUST indicate when search is processing with a visual indicator
- **FR-Search-006**: System MUST support searching across multiple document languages including non-Latin scripts using UTF-8 aware keyword matching; document language metadata MUST be stored but not used for query analysis in V1

#### Conversation Linking (FR-Link)

- **FR-Link-001**: System MUST allow users to attach one or more documents to a conversation from their knowledge base
- **FR-Link-002**: System MUST display attached documents as part of the conversation metadata
- **FR-Link-003**: System MUST include content from linked documents in the context sent to the AI model during chat, up to approximately 16KB total per conversation
- **FR-Link-004**: System MUST warn users when the combined size of linked documents exceeds ~16KB and offer option to remove documents before sending
- **FR-Link-005**: System MUST maintain document links across conversation reloads (app restart, device reboot)
- **FR-Link-006**: System MUST notify user if a linked document has been deleted since attachment

#### Export/Import (FR-Export)

- **FR-Export-001**: System MUST allow users to export their entire knowledge base as a single compressed file
- **FR-Export-002**: System MUST include all documents and their associated metadata in exports, organized with extracted text files and a `manifest.json` file containing document IDs, titles, content hashes, timestamps, format types, and processing states
- **FR-Export-003**: System MUST provide an import function that reads exported knowledge base files; import MUST validate manifest JSON structure and content hashes before restoring documents
- **FR-Export-004**: System MUST detect duplicate documents during import by comparing content hashes and skip re-importing unchanged documents

### Key Entities *(include if feature involves data)*

- **Document**: A file uploaded by the user containing text content. Key attributes: unique identifier (UUID), title, source file path, upload timestamp, last modified timestamp, size in bytes, content hash (SHA-256 for deduplication), format type, primary language code (e.g., "en", "ja"), processing state (`pending`, `processing`, `available`, `failed`).

- **Conversation Link**: A relationship between a conversation and one or more documents. Represents that these documents are relevant to this specific chat thread. Stores: document ID reference, attachment timestamp.

- **Search Query**: A user-entered natural language string used to find relevant documents.

- **Knowledge Base**: The complete collection of all documents associated with the user's account on this device.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can upload a 5MB document and have it available for search within 30 seconds
- **SC-002**: Search returns relevant results for common queries in under 5 seconds on devices with 4GB RAM or more
- **SC-003**: Users can successfully link at least 10 documents to a conversation without UI lag or crashes
- **SC-004**: Knowledge base export completes within 2 minutes for up to 100 documents totaling 500MB
- **SC-005**: 90% of users can complete their first document upload and search successfully on first attempt
- **SC-006**: System works offline with zero network connectivity once initial setup is complete

## Clarifications

### Session 2026-02-27

- Q: When a user uploads a document that cannot be processed (e.g., corrupted PDF, unsupported format), how should the system behave? → A: Store document with "failed" status, show clear UI indication that it cannot be searched or linked, allow user to retry or delete.
- Q: When documents are linked to a conversation, how much content from each document should be included in the AI context window? → A: Include full extracted text but show a warning when total linked content exceeds ~16KB; allow user to proceed or remove documents.
- Q: What metadata format should be included in the export/import ZIP file? → A: ZIP contains a `manifest.json` file with all document metadata (IDs, titles, content hashes, timestamps, format types) plus extracted text files; import validates hashes and detects duplicates by ID/hash.
- Q: How should search handle documents with different languages, especially non-Latin scripts? → A: Store language metadata per document but use simple UTF-8 aware keyword matching for all searches; no separate language analyzers in V1.

## Assumptions

1. **Document Processing**: First iteration uses keyword-based search rather than semantic embeddings to keep resource requirements low. Future iterations may add embedding-based similarity search.

2. **Storage Location**: Documents are stored in app-specific local storage (`/Android/data/[package]/files/documents/`) with no user-accessible folder location required for first version.

3. **File Format Support**: Initial supported formats (PDF, TXT, MD, DOCX) cover 95%+ of user document needs. More obscure formats will show unsupported errors.

4. **Search Algorithm**: Basic inverted index with keyword matching and TF-IDF scoring. Does not include fuzzy matching or complex natural language processing in first version.

5. **Memory Constraints**: Document content is processed incrementally during search rather than loading entire documents into memory at once.

6. **No Sync Across Devices**: This feature works locally on each device only. Cross-device sync would require additional infrastructure that conflicts with the local-first principle.

7. **User Technical Literacy**: Users understand basic file operations (selecting files, navigating storage). No advanced technical knowledge required for standard workflows.
