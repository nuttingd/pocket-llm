package dev.nutting.pocketllm.domain.image

import android.util.Log
import androidx.room.withTransaction
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.ImageApiException
import dev.nutting.pocketllm.data.remote.image.OpenAiImagesClient
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.SdCppJob
import dev.nutting.pocketllm.data.remote.image.decodeImageBase64
import dev.nutting.pocketllm.data.remote.image.imageJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one image job to a terminal state. The `image_jobs` row is the source of truth, so this can be
 * called again after the process died and picks up where it left off:
 * - sd-server jobs with a server job id resume polling and are **never resubmitted** (no duplicate GPU work);
 * - a synchronous (OpenAI images) job found mid-request can't be recovered and fails as interrupted;
 * - a job found saving re-fetches the result and saves again (idempotent).
 * Every state write is conditional on the job not being finished, so a user's cancel always wins.
 */
class ImageJobRunner(
    private val db: PocketLlmDatabase,
    private val server: suspend (serverId: String) -> ServerProfileEntity?,
    private val apiKey: suspend (serverId: String) -> String?,
    private val sdCpp: SdCppImageClient,
    private val openAi: OpenAiImagesClient,
    private val store: ImageStore,
    /** Input images for an edit job (empty for generation). */
    private val sourceImages: suspend (ImageJobEntity) -> List<SourceImage> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    class SourceImage(val bytes: ByteArray, val mimeType: String)

    private val jobs get() = db.imageJobDao()

    suspend fun run(jobId: String) {
        val job = jobs.getById(jobId) ?: return
        if (job.status.isTerminal) return
        try {
            val server = server(job.serverId)
                ?: throw JobFailure("server_gone", "The image server for this request was removed.")
            val params = imageJson.decodeFromString<ResolvedImageParameters>(job.paramsJson)
            val deadline = job.createdAt + server.imageTimeoutSeconds * 1000L
            when (job.dialect) {
                ImageDialect.SD_CPP -> runSdCpp(job, server, params, deadline)
                ImageDialect.OPENAI_IMAGES -> runOpenAi(job, server, params, deadline)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JobFailure) {
            Log.e(TAG, "Image job ${jobId.take(8)} failed: ${e.code}", e)
            fail(jobId, e.code, e.message ?: "Image generation failed.")
        } catch (e: ImageApiException) {
            Log.e(TAG, "Image job ${jobId.take(8)} failed: ${e.kind}", e)
            fail(jobId, codeFor(e.kind), e.message ?: "Image generation failed.")
        } catch (e: Exception) {
            Log.e(TAG, "Image job ${jobId.take(8)} failed unexpectedly", e)
            fail(jobId, "unexpected", "Image generation failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun runSdCpp(job: ImageJobEntity, server: ServerProfileEntity, params: ResolvedImageParameters, deadline: Long) {
        val key = apiKey(server.id)
        val remoteId = job.remoteJobId ?: run {
            val refImages = sourceImages(job).map { "data:${it.mimeType};base64," + java.util.Base64.getEncoder().encodeToString(it.bytes) }
            val id = sdCpp.submit(server.baseUrl, key, params.toSdCppRequest(job.prompt, refImages))
            if (jobs.markSubmitted(job.id, id) == 0) {
                // Cancelled while submitting: stop the server job too if it hasn't started
                runCatching { sdCpp.cancel(server.baseUrl, key, id) }
                    .onFailure { Log.w(TAG, "Couldn't cancel server job after a local cancel", it) }
                return
            }
            id
        }

        var failingSince: Long? = null
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            if (clock() > deadline) {
                runCatching { sdCpp.cancel(server.baseUrl, key, remoteId) }
                throw JobFailure("timeout", "The image took longer than this server's image timeout (${server.imageTimeoutSeconds / 60} min).")
            }
            val snapshot = try {
                sdCpp.poll(server.baseUrl, key, remoteId)
            } catch (e: ImageApiException) {
                if (!e.isTransient) throw e
                val since = failingSince ?: clock().also { failingSince = it }
                if (clock() - since > MAX_UNREACHABLE_MS) {
                    throw JobFailure("unreachable", "Lost contact with the image server. ${e.message}")
                }
                Log.w(TAG, "Poll failed (${e.kind}); retrying in ${backoff}ms")
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            failingSince = null
            backoff = INITIAL_BACKOFF_MS

            when (snapshot.status) {
                "queued" -> {
                    if (jobs.markProgress(job.id, ImageJobStatus.SUBMITTED, snapshot.queuePosition, null, null, null, null) == 0) return
                    sleep(QUEUED_POLL_MS)
                }
                "completed" -> {
                    val images = snapshot.result?.images.orEmpty().sortedBy { it.index }.map { decodeImageBase64(it.b64Json) }
                    save(job, params, images, startedAt = snapshot.started, completedAt = snapshot.completed)
                    return
                }
                "failed" -> throw JobFailure("server", snapshot.error?.message?.let { "The server couldn't generate the image: $it" }
                    ?: "The server couldn't generate the image.")
                "cancelled" -> {
                    jobs.markFinishedUnsuccessfully(job.id, ImageJobStatus.CANCELLED, "cancelled_by_server", "Cancelled on the server.", clock())
                    return
                }
                else -> { // "generating", or a state this version doesn't know yet: keep polling
                    if (!markGenerating(job.id, snapshot)) return
                    sleep(GENERATING_POLL_MS)
                }
            }
        }
    }

    private suspend fun markGenerating(jobId: String, snapshot: SdCppJob): Boolean =
        jobs.markProgress(
            jobId, ImageJobStatus.GENERATING, queuePosition = null,
            pass = snapshot.preview?.pass, step = snapshot.preview?.step, totalSteps = snapshot.preview?.totalSteps,
            startedAt = snapshot.started,
        ) > 0

    private suspend fun runOpenAi(job: ImageJobEntity, server: ServerProfileEntity, params: ResolvedImageParameters, deadline: Long) {
        if (job.status != ImageJobStatus.QUEUED_LOCAL) {
            // The app stopped mid-request; this API can't hand the result over later
            throw JobFailure("interrupted", "The app was closed while the image was generating, and this server can't resume it. Try again.")
        }
        val startedAt = clock()
        if (jobs.markProgress(job.id, ImageJobStatus.GENERATING, null, null, null, null, startedAt) == 0) return
        val remaining = deadline - startedAt
        if (remaining <= 0) throw JobFailure("timeout", "The request expired before it could start.")
        val key = apiKey(server.id)
        val request = params.toOpenAiRequest(job.modelId, job.prompt)
        val images = if (job.kind == ImageJobKind.EDIT) {
            openAi.edit(server.baseUrl, key, request, sourceImages(job).map { OpenAiImagesClient.InputImage(it.bytes, it.mimeType) }, remaining)
        } else {
            openAi.generate(server.baseUrl, key, request, remaining)
        }
        save(job, params, images, startedAt = startedAt, completedAt = clock())
    }

    /** Writes the images and records them; if the job was cancelled meanwhile the files are discarded. */
    private suspend fun save(job: ImageJobEntity, params: ResolvedImageParameters, images: List<ByteArray>, startedAt: Long?, completedAt: Long?) {
        if (images.isEmpty()) throw JobFailure("bad_response", "The server finished but returned no images.")
        if (jobs.markProgress(job.id, ImageJobStatus.SAVING, null, null, null, null, startedAt) == 0) return
        val written = mutableListOf<String>()
        val now = clock()
        val rows = try {
            withContext(Dispatchers.IO) {
                images.mapIndexed { index, bytes ->
                    val info = sniffImage(bytes)
                        ?: throw JobFailure("bad_response", "The server returned data that isn't a PNG, JPEG or WebP image.")
                    val path = store.write(job.conversationId, job.messageId, index, bytes, info.extension).also { written += it }
                    GeneratedImageEntity(
                        id = UUID.randomUUID().toString(),
                        messageId = job.messageId,
                        idx = index,
                        filePath = path,
                        mimeType = info.mimeType,
                        width = info.width,
                        height = info.height,
                        seed = params.seedOf(index),
                        prompt = job.prompt,
                        paramsJson = job.paramsJson,
                        modelId = job.modelId,
                        durationMs = if (startedAt != null && completedAt != null) (completedAt - startedAt).coerceAtLeast(0) else null,
                        createdAt = now,
                    )
                }
            }
        } catch (e: IOException) {
            written.forEach(store::delete)
            throw JobFailure("storage", "Couldn't save the image. Your device may be out of storage.", e)
        } catch (e: Throwable) {
            written.forEach(store::delete)
            throw e
        }
        val committed = db.withTransaction {
            (jobs.markSucceeded(job.id, startedAt, completedAt ?: now) > 0).also { ok ->
                if (ok) db.generatedImageDao().upsertAll(rows)
            }
        }
        if (!committed) written.forEach(store::delete)
    }

    private suspend fun fail(jobId: String, code: String, message: String) {
        jobs.markFinishedUnsuccessfully(jobId, ImageJobStatus.FAILED, code, message, clock())
    }

    private class JobFailure(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "ImageJobRunner"
        const val QUEUED_POLL_MS = 3_000L
        const val GENERATING_POLL_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        /** How long the server may be unreachable before the job is given up on. */
        const val MAX_UNREACHABLE_MS = 2 * 60 * 1000L

        internal fun codeFor(kind: ImageApiException.Kind): String = when (kind) {
            ImageApiException.Kind.UNREACHABLE -> "unreachable"
            ImageApiException.Kind.TIMEOUT -> "timeout"
            ImageApiException.Kind.UNAUTHORIZED -> "auth"
            ImageApiException.Kind.QUEUE_FULL -> "queue_full"
            ImageApiException.Kind.MODEL_LOADING, ImageApiException.Kind.MODEL_NOT_LOADED -> "model_not_ready"
            ImageApiException.Kind.REJECTED -> "rejected"
            ImageApiException.Kind.SERVER -> "server"
            ImageApiException.Kind.JOB_GONE -> "interrupted"
            ImageApiException.Kind.BAD_RESPONSE -> "bad_response"
        }
    }
}
