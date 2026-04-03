package dev.nutting.pocketllm.data.local.processor

import android.util.Log
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class DocxDocumentProcessor : DocumentProcessor {
    companion object { private const val TAG = "DocxDocumentProcessor" }
    
    override fun canHandle(mimeType: String, extension: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || 
               extension.equals(".docx", ignoreCase = true)
    }
    
    override suspend fun process(inputStream: InputStream, documentId: String): DocumentProcessingResult {
        return try {
            val wordPackage = WordprocessingMLPackage.load(inputStream)
            val textContent = extractText(wordPackage)
            val metadata = extractMetadata(wordPackage)
            val wordCount = textContent.countWords()
            val charCount = textContent.length
            
            DocumentProcessingResult(
                content = textContent,
                metadata = metadata,
                wordCount = wordCount,
                charCount = charCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DOCX", e)
            DocumentProcessingResult(
                content = "",
                metadata = DocumentMetadata(null, null, null, null, null),
                wordCount = 0,
                charCount = 0
            )
        }
    }
    
    private fun extractText(wordPackage: WordprocessingMLPackage): String {
        return try {
            val exporter = org.docx4j.exporter.PlainTextExporter(wordPackage)
            var content = exporter.exportToString()
            content = content.replace(Regex("\\s+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            content
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting text", e)
            ""
        }
    }
    
    private fun extractMetadata(wordPackage: WordprocessingMLPackage): DocumentMetadata {
        return try {
            val coreProperties = wordPackage.coreProperties
            val titleVal = coreProperties.title
            val creatorVal = coreProperties.creator
            val keywordsVal = coreProperties.keywords
            
            DocumentMetadata(
                title = if (titleVal != null && titleVal.isNotEmpty()) titleVal else null,
                author = if (creatorVal != null && creatorVal.isNotEmpty()) creatorVal else null,
                creationDate = coreProperties.created?.timeInMillis,
                modifiedDate = coreProperties.modified?.timeInMillis,
                keywords = try {
                    if (keywordsVal != null && keywordsVal.isNotBlank()) {
                        val splitList = keywordsVal.split(";")
                        val trimmedList = splitList.map { it.trim() }.filter { it.isNotEmpty() }
                        if (trimmedList.isNotEmpty()) trimmedList else null
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing keywords", e)
                    null
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting DOCX metadata", e)
            DocumentMetadata(null, null, null, null, null)
        }
    }
}
