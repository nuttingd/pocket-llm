package dev.nutting.pocketllm.data.local.processor

import java.io.InputStream
import java.util.UUID

/**
 * Interface for processing different document formats.
 *
 * Implementations extract text content and metadata from various document formats.
 */
interface DocumentProcessor {
    
    /**
     * Determines if this processor can handle the given file.
     *
     * @param mimeType The MIME type of the file
     * @param extension The file extension (including dot)
     * @return true if this processor can handle the file, false otherwise
     */
    fun canHandle(mimeType: String, extension: String): Boolean
    
    /**
     * Processes a document and extracts its content and metadata.
     *
     * @param inputStream The input stream of the document to process
     * @param documentId The unique identifier for this document
     * @return DocumentProcessingResult containing extracted content and metadata
     */
    suspend fun process(inputStream: InputStream, documentId: String): DocumentProcessingResult
}

/**
 * Result of document processing.
 *
 * @property content Extracted text content from the document
 * @property metadata Parsed document metadata
 * @property wordCount Number of words in the content
 * @property charCount Number of characters in the content
 */
data class DocumentProcessingResult(
    val content: String,
    val metadata: DocumentMetadata,
    val wordCount: Int,
    val charCount: Int,
)

/**
 * Extension function to count words in a string using standard splitting.
 */
fun String.countWords(): Int {
    return this.trim().split(Regex("\\s+")).size
}
