package dev.nutting.pocketllm.data.local.processor

import android.util.Log
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Text document processor for .txt and .md files.
 *
 * Handles encoding detection and extracts plain text content.
 */
class TextDocumentProcessor : DocumentProcessor {
    
    private companion object {
        const val TAG = "TextDocumentProcessor"
        
        // Common encodings to try in order of preference
        val ENCodingsToTry = listOf(
            StandardCharsets.UTF_8,
            StandardCharsets.ISO_8859_1,
            StandardCharsets.US_ASCII
        )
    }
    
    override fun canHandle(mimeType: String, extension: String): Boolean {
        return mimeType == "text/plain" || 
               mimeType == "text/markdown" ||
               extension.equals(".txt", ignoreCase = true) ||
               extension.equals(".md", ignoreCase = true)
    }
    
    override suspend fun process(inputStream: InputStream, documentId: String): DocumentProcessingResult {
        return try {
            // Detect encoding and read content
            val content = detectAndReadText(inputStream)
            
            // For text files, use filename as title (may be updated later from content)
            val metadata = DocumentMetadata(
                title = null, // Will be derived from filename at upload time
                author = null,
                creationDate = null,
                modifiedDate = null,
                keywords = extractKeywordsFromContent(content)
            )
            
            val wordCount = content.countWords()
            val charCount = content.length
            
            DocumentProcessingResult(
                content = content,
                metadata = metadata,
                wordCount = wordCount,
                charCount = charCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing text document", e)
            
            // Return minimal result on error
            DocumentProcessingResult(
                content = "",
                metadata = DocumentMetadata(
                    title = null,
                    author = null,
                    creationDate = null,
                    modifiedDate = null,
                    keywords = listOf("processing-error")
                ),
                wordCount = 0,
                charCount = 0
            )
        }
    }
    
    /**
     * Attempts to read text content using multiple encodings.
     */
    private fun detectAndReadText(inputStream: InputStream): String {
        var lastException: Exception? = null
        
        for (charset in ENCodingsToTry) {
            try {
                val bytes = inputStream.readBytes()
                return String(bytes, charset)
            } catch (e: Exception) {
                lastException = e
                continue
            }
        }
        
        // If all standard encodings fail, try UTF-8 with replacement characters
        try {
            val bytes = inputStream.readBytes()
            return String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            lastException?.let { throw it }
            throw lastException ?: RuntimeException("Failed to read text document")
        }
    }
    
    /**
     * Extracts keywords from content by looking for common patterns.
     * 
     * Examples:
     * - "#keywords: android, kotlin, mobile"
     * - "Tags: #android #kotlin"
     * - Keywords on separate lines after "---" or similar delimiters
     */
    private fun extractKeywordsFromContent(content: String): List<String>? {
        val lines = content.lines()
        
        // Look for common keyword patterns
        var keywordsLineIndex = -1
        
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim().lowercase()
            
            if (line.startsWith("#keywords:") || 
                line.startsWith("keywords:") ||
                line.startsWith("tags:")) {
                keywordsLineIndex = i
                break
            }
        }
        
        return if (keywordsLineIndex >= 0) {
            val keywordText = lines[keywordsLineIndex]
            
            // Extract comma-separated or hashtag-separated keywords
            val keywords = mutableListOf<String>()
            
            // Try comma-separated first
            var foundKeywords = false
            for (part in keywordText.split(',')) {
                val trimmed = part.trim()
                if (trimmed.startsWith('#')) {
                    keywords.add(trimmed.removePrefix("#").trim())
                    foundKeywords = true
                } else if (trimmed.isNotEmpty()) {
                    keywords.add(trimmed)
                    foundKeywords = true
                }
            }
            
            // If no hashtags or commas found, try space-separated with # prefix
            if (!foundKeywords) {
                for (token in keywordText.split(Regex("\\s+"))) {
                    val trimmed = token.trim()
                    if (trimmed.startsWith('#') && trimmed.length > 1) {
                        keywords.add(trimmed.removePrefix("#"))
                        foundKeywords = true
                    }
                }
            }
            
            if (keywords.isNotEmpty()) {
                keywords
            } else null
        } else {
            null // No keywords pattern found
        }
    }
}
