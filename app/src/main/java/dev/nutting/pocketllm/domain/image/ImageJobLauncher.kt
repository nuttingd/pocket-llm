package dev.nutting.pocketllm.domain.image

import android.util.Log
import androidx.room.withTransaction
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.imageJson
import dev.nutting.pocketllm.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/** Runs image jobs in the background (WorkManager in the app; a fake in tests). */
interface ImageJobScheduler {
    /** Starts the job's work, or with [replace] restarts it (Retry). Keeps already-running work otherwise. */
    fun enqueue(jobId: String, replace: Boolean = false)
    fun cancel(jobId: String)
}

/**
 * Starts, regenerates, retries and cancels image jobs. Each new request creates the user message, the pending
 * assistant reply and the job row in one transaction, so the conversation never shows half a request.
 */
class ImageJobLauncher(
    private val db: PocketLlmDatabase,
    private val classifier: ModelClassifier,
    private val scheduler: ImageJobScheduler,
    private val sdCpp: SdCppImageClient,
    private val apiKey: suspend (serverId: String) -> String?,
    /** For fire-and-forget server cancels that must outlive the screen. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val messages = MessageRepository(db.messageDao())

    /** Appends a prompt and a pending image reply to the conversation, and starts generating. Returns the job id. */
    suspend fun generate(conversationId: String, prompt: String, server: ServerProfileEntity, modelId: String): String {
        val conversation = db.conversationDao().getById(conversationId).first()
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val params = resolveParams(conversation.imageParamsJson, server)
        val parent = conversation.activeLeafMessageId?.let { messages.getById(it) }
        val now = clock()
        val user = MessageEntity(
            id = newId(), conversationId = conversationId, parentMessageId = parent?.id, role = "user",
            content = prompt, depth = (parent?.depth ?: -1) + 1, createdAt = now,
        )
        val job = newJob(conversationId, prompt, server, modelId, params.first, params.second, ImageJobKind.GENERATE, sourceMessageId = null, now = now)
        insertReply(conversationId, user, job, now)
        scheduler.enqueue(job.id)
        return job.id
    }

    /** Generates a new variation of [reply] as a sibling branch, using the current server, model and parameters. */
    suspend fun regenerate(reply: MessageEntity, server: ServerProfileEntity, modelId: String): String? {
        val source = db.imageJobDao().getByMessageId(reply.id) ?: return null
        val user = reply.parentMessageId?.let { messages.getById(it) } ?: return null
        val conversation = db.conversationDao().getById(reply.conversationId).first() ?: return null
        val params = resolveParams(conversation.imageParamsJson, server)
        val now = clock()
        val job = newJob(reply.conversationId, source.prompt, server, modelId, params.first, params.second, source.kind, source.sourceMessageId, now)
        insertReply(reply.conversationId, null, job, now, parentForReply = user)
        scheduler.enqueue(job.id)
        return job.id
    }

    /** Starts a failed or cancelled job again with fresh parameters (a new seed unless the user fixed one). */
    suspend fun retry(jobId: String) {
        val job = db.imageJobDao().getById(jobId) ?: return
        val server = db.serverProfileDao().getById(job.serverId).first() ?: return
        val conversation = db.conversationDao().getById(job.conversationId).first() ?: return
        val (_, params) = resolveParams(conversation.imageParamsJson, server)
        if (db.imageJobDao().resetForRetry(jobId, imageJson.encodeToString(ResolvedImageParameters.serializer(), params), clock()) > 0) {
            scheduler.enqueue(jobId, replace = true)
        }
    }

    /**
     * Stops waiting for a job. A job still queued on sd-server is cancelled there too; one already generating
     * can't be interrupted by the server, which the reply then explains. The server call runs in the app scope
     * so leaving the screen doesn't abort it; the returned job completes when it's done (null if none was needed).
     */
    suspend fun cancel(jobId: String): Job? {
        val jobs = db.imageJobDao()
        val job = jobs.getById(jobId) ?: return null
        if (jobs.markFinishedUnsuccessfully(jobId, ImageJobStatus.CANCELLED, "cancelled", "Cancelled.", clock()) == 0) return null
        scheduler.cancel(jobId)
        val remoteId = job.remoteJobId ?: return null
        if (job.dialect != ImageDialect.SD_CPP) return null
        return appScope.launch {
            val server = db.serverProfileDao().getById(job.serverId).first() ?: return@launch
            try {
                if (sdCpp.cancel(server.baseUrl, apiKey(server.id), remoteId) == SdCppImageClient.CancelOutcome.SERVER_BUSY) {
                    jobs.annotateCancelled(jobId, CANCELLED_SERVER_BUSY, "The server will finish the image it already started in the background.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't cancel the job on the server; it may still run there", e)
            }
        }
    }

    private suspend fun resolveParams(imageParamsJson: String?, server: ServerProfileEntity): Pair<ImageDialect, ResolvedImageParameters> {
        val info = classifier.serverInfo(server)
        val settings = imageParamsJson?.let {
            runCatching { imageJson.decodeFromString(ImageParameters.serializer(), it) }
                .onFailure { e -> Log.w(TAG, "Ignoring unreadable image parameters", e) }
                .getOrNull()
        } ?: ImageParameters()
        return info.dialect to settings.resolve(info.dialect, info.capabilities?.limits, random)
    }

    private fun newJob(
        conversationId: String, prompt: String, server: ServerProfileEntity, modelId: String,
        dialect: ImageDialect, params: ResolvedImageParameters, kind: ImageJobKind, sourceMessageId: String?, now: Long,
    ) = ImageJobEntity(
        id = newId(),
        conversationId = conversationId,
        messageId = newId(),
        serverId = server.id,
        modelId = modelId,
        dialect = dialect,
        kind = kind,
        prompt = prompt,
        paramsJson = imageJson.encodeToString(ResolvedImageParameters.serializer(), params),
        sourceMessageId = sourceMessageId,
        status = ImageJobStatus.QUEUED_LOCAL,
        createdAt = now,
    )

    /** Inserts the optional user message, the pending reply for [job], the job, and moves the active leaf. */
    private suspend fun insertReply(conversationId: String, user: MessageEntity?, job: ImageJobEntity, now: Long, parentForReply: MessageEntity? = user) {
        val replyParent = requireNotNull(parentForReply)
        val reply = MessageEntity(
            id = job.messageId, conversationId = conversationId, parentMessageId = replyParent.id, role = "assistant",
            content = "", serverProfileId = job.serverId, modelId = job.modelId, depth = replyParent.depth + 1, createdAt = now,
        )
        db.withTransaction {
            user?.let { messages.insertMessage(it) }
            messages.insertMessage(reply)
            db.imageJobDao().insert(job)
            db.conversationDao().updateActiveLeaf(conversationId, reply.id, now)
        }
    }

    companion object {
        private const val TAG = "ImageJobLauncher"
        const val CANCELLED_SERVER_BUSY = "cancelled_server_busy"
    }
}
