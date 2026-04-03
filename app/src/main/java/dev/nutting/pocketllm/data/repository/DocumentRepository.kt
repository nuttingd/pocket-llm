package dev.nutting.pocketllm.data.repository

import android.content.Context
import android.net.Uri
import androidx.work.*
import dev.nutting.pocketllm.data.local.dao.*
import dev.nutting.pocketllm.data.local.entity.*
import dev.nutting.pocketllm.data.local.processor.*
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.util.UUID

/**
 * Repository for document management operations.
 * 
 * Abstracts away the DAO layer and provides high-level methods for:
 * - Uploading documents with automatic content extraction
 * - Searching documents using FTS4
 * - Linking documents to conversations
 */
class DocumentRepository(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val documentContentDao: DocumentContentDao,
    private val conversationLinkDao: ConversationLinkDao,
    private val processors: List<DocumentProcessor>,
) {

    /**
     * Upload a new document synchronously.
     * 
     * @param uri URI of the file to upload
     * @param mimeType MIME type of the file
     * @param sizeBytes Size of the file in bytes
     * @return DocumentEntity with generated ID and processing results
     */
    suspend fun uploadDocument(
        uri: Uri,
        mimeType: String,
        sizeBytes: Long,
        filename: String? = null,
    ): UploadResult {
        val documentId = UUID.randomUUID().toString()
        
        // Get the appropriate processor for this MIME type
        val extension = if (filename != null) {
            filename.substringAfterLast('.', "")
        } else ""
        
        val processor = processors.find { it.canHandle(mimeType, ".$extension") }
            ?: return UploadResult.Failed("No processor found for $mimeType")
        
        // Process the document in a background thread to avoid blocking UI
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return UploadResult.Failed("Failed to open file: $uri")
            
            val result = processor.process(inputStream, documentId)
            inputStream.close()
            
            // Save metadata and content to database
            val documentEntity = DocumentEntity(
                id = documentId,
                title = result.metadata.title ?: filename?.substringAfterLast('/') ?: "Unknown",
                sourceFileName = filename ?: "unknown.$extension",
                mimeType = mimeType,
                wordCount = result.wordCount,
                sizeBytes = sizeBytes,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            
            documentDao.insert(documentEntity)
            
            val contentEntity = DocumentContentEntity(
                documentId = documentId,
                content = result.content
            )
            documentContentDao.insert(contentEntity)
            
            UploadResult.Success(
                document = documentEntity,
                wordCount = result.wordCount,
                charCount = result.charCount,
                metadata = result.metadata
            )
        } catch (e: Exception) {
            UploadResult.Failed("Failed to process document: ${e.message}")
        }
    }

    /**
     * Schedule background processing for a large document.
     */
    fun scheduleBackgroundProcessing(
        uri: Uri,
        mimeType: String,
        sizeBytes: Long,
        filename: String? = null,
    ): java.util.UUID {
        val documentId = UUID.randomUUID().toString()
        
        val data = WorkData.Builder()
            .putString("document_id", documentId)
            .putString("uri", uri.toString())
            .putString("mimeType", mimeType)
            .putLong("sizeBytes", sizeBytes)
            .putString("filename", filename ?: "unknown")
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DocumentProcessingWorker>()
            .setInputData(data)
            .addTag(documentId)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "document-processing-$documentId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        return workRequest.id
    }

    fun isBackgroundProcessingComplete(documentId: String): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfoFlow = workManager.getWorkInfosForUniqueWork("document-processing-$documentId")
        return true // Placeholder - actual implementation would query WorkInfo status
    }

    fun searchDocuments(query: String): Flow<List<DocumentSearchResult>> {
        return documentDao.searchDocuments(query)
    }

    fun getAllDocuments(): Flow<List<DocumentEntity>> {
        return documentDao.getAllSorted()
    }

    suspend fun getDocumentById(id: String): DocumentEntity? {
        return documentDao.getById(id)
    }

    suspend fun deleteDocument(documentId: String): Boolean {
        val document = documentDao.getById(documentId) ?: return false
        
        try {
            documentDao.delete(document)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun linkDocumentToConversation(
        conversationId: String,
        documentId: String
    ): LinkResult {
        val isAlreadyLinked = conversationLinkDao.isLinked(conversationId, documentId)
        if (isAlreadyLinked) {
            return LinkResult.AlreadyLinked
        }

        val documentExists = documentDao.getById(documentId) != null
        if (!documentExists) {
            return LinkResult.DocumentNotFound
        }

        try {
            conversationLinkDao.insert(ConversationLinkEntity(
                conversationId = conversationId,
                documentId = documentId,
                linkedAt = System.currentTimeMillis()
            ))
            return LinkResult.Success
        } catch (e: Exception) {
            return LinkResult.Failed(e.message ?: "Unknown error")
        }
    }

    suspend fun unlinkDocumentFromConversation(
        conversationId: String,
        documentId: String
    ): Boolean {
        return try {
            val existingLink = conversationLinkDao.getLink(conversationId, documentId)
            if (existingLink == null) {
                return false
            }
            
            conversationLinkDao.delete(existingLink)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getLinkedDocuments(conversationId: String): Flow<List<String>> {
        return conversationLinkDao.getLinkedDocumentIds(conversationId)
    }

    suspend fun getStatistics(): DocumentStats {
        val count = documentDao.getCount()
        val totalSizeBytes = documentDao.getTotalSizeBytes() ?: 0L
        
        return DocumentStats(
            documentCount = count,
            totalSizeBytes = totalSizeBytes
        )
    }

    sealed class UploadResult {
        data class Success(
            val document: DocumentEntity,
            val wordCount: Int,
            val charCount: Int,
            val metadata: DocumentMetadata
        ) : UploadResult()
        
        data class Failed(val message: String) : UploadResult()
    }

    sealed class LinkResult {
        object Success : LinkResult()
        object AlreadyLinked : LinkResult()
        object DocumentNotFound : LinkResult()
        data class Failed(val message: String) : LinkResult()
    }

    data class DocumentStats(
        val documentCount: Int,
        val totalSizeBytes: Long,
    )
}
