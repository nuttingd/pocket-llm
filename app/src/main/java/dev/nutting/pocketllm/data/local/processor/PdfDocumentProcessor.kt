package dev.nutting.pocketllm.data.local.processor

import android.util.Log
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfDocumentProcessor : DocumentProcessor {
    companion object { private const val TAG = "PdfDocumentProcessor" }
    
    override fun canHandle(mimeType: String, extension: String): Boolean {
        return mimeType == "application/pdf" || extension.equals(".pdf", ignoreCase = true)
    }
    
    override suspend fun process(inputStream: InputStream, documentId: String): DocumentProcessingResult {
        return try {
            val pdfDocument = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val textContent = stripper.getText(pdfDocument)
            val metadata = extractMetadata(pdfDocument)
            val wordCount = textContent.countWords()
            val charCount = textContent.length
            
            DocumentProcessingResult(
                content = textContent,
                metadata = metadata,
                wordCount = wordCount,
                charCount = charCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PDF", e)
            DocumentProcessingResult(
                content = "",
                metadata = DocumentMetadata(null, null, null, null, null),
                wordCount = 0,
                charCount = 0
            )
        }
    }
    
    private fun extractMetadata(pdfDocument: PDDocument): DocumentMetadata {
        return try {
            val documentInfo = pdfDocument.documentInformation
            DocumentMetadata(
                title = documentInfo.title,
                author = documentInfo.author,
                creationDate = parseCreationDate(documentInfo.creationDate),
                modifiedDate = null, // PDFBox doesn't track modification date in metadata
                keywords = documentInfo.keywords?.toList()?.filter { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting PDF metadata", e)
            DocumentMetadata(null, null, null, null, null)
        }
    }
    
    private fun parseCreationDate(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) return null
        val patterns = listOf(
            "D:yyyy'-'MM'-'dd'T'HH':'mm':'ss",
            "D:yyyyMMddHHmmss",
            "D:yyyyMMddHHmm"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                return sdf.parse(dateString)?.time
            } catch (e: Exception) { 
                continue 
            }
        }
        return null
    }
}
