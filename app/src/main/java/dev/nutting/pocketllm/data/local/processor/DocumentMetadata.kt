package dev.nutting.pocketllm.data.local.processor

/**
 * In-memory structure for passing parsed metadata between layers.
 *
 * @property title Extracted document title (may be null if not found)
 * @property author Document author (PDF/DOCX only)
 * @property creationDate Creation timestamp (if available in metadata)
 * @property modifiedDate Last modification timestamp
 * @property keywords Document keywords/tags
 */
data class DocumentMetadata(
    val title: String?,
    val author: String?,
    val creationDate: Long?,
    val modifiedDate: Long?,
    val keywords: List<String>?,
)
