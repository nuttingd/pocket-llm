package dev.nutting.pocketllm.data.repository

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.dao.DocumentContentDao
import dev.nutting.pocketllm.data.local.dao.DocumentDao
import dev.nutting.pocketllm.data.local.entity.DocumentContentEntity
import dev.nutting.pocketllm.data.local.entity.DocumentEntity
import dev.nutting.pocketllm.data.local.processor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentProcessingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object { private const val TAG = "DocumentProcessingWorker" }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val documentId = inputData.getString("document_id") ?: return@withContext Result.failure()
            val uriString = inputData.getString("uri") ?: return@withContext Result.failure()
            val mimeType = inputData.getString("mimeType") ?: return@withContext Result.failure()
            val sizeBytes = inputData.getLong("sizeBytes", 0)
            val filename = inputData.getString("filename")
            
            val uri = Uri.parse(uriString) ?: return@withContext Result.failure()
            val extension = if (filename != null) { filename.substringAfterLast('.', "") } else ""
            
            val processors = listOf(PdfDocumentProcessor(), TextDocumentProcessor(), DocxDocumentProcessor())
            val processor = processors.find { it.canHandle(mimeType, ".$extension") } ?: return@withContext Result.failure()
            
            val inputStream = applicationContext.contentResolver.openInputStream(uri) ?: return@withContext Result.failure()
            val result = processor.process(inputStream, documentId)
            inputStream.close()
            
            val db = PocketLlmDatabase.create(applicationContext)
            val dao = db.documentDao()
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
            dao.insert(documentEntity)
            
            val contentDao = db.documentContentDao()
            val contentEntity = DocumentContentEntity(documentId = documentId, content = result.content)
            contentDao.insert(contentEntity)
            
            android.util.Log.i(TAG, "Successfully processed document $documentId: ${result.wordCount} words")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to process document", e)
            Result.failure()
        }
    }
}
