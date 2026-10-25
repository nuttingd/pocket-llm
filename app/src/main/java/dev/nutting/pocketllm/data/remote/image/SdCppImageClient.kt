package dev.nutting.pocketllm.data.remote.image

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/**
 * stable-diffusion.cpp `sd-server`'s native async API: submit a job, poll it for progress and the result,
 * cancel it while queued. Unlike the server's OpenAI endpoints this carries every generation parameter and
 * lets a restarted app resume a job by id.
 */
class SdCppImageClient(
    clientProvider: () -> HttpClient = ::defaultImageHttpClient,
) {
    private val client by lazy(clientProvider)

    /**
     * Returns the server's capabilities when it speaks the native API with image generation, or null when it
     * doesn't (404/405, or no `img_gen` mode). Throws on transport and other errors, so callers don't
     * mistake a briefly unreachable sd-server for a different kind of server.
     */
    suspend fun probe(baseUrl: String, apiKey: String?): SdCppCapabilities? = call(baseUrl) {
        val response = client.get("${baseUrl.trimBaseUrl()}/sdcpp/v1/capabilities") {
            bearer(apiKey)
            timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
        }
        when (response.status.value) {
            in 200..299 -> {
                // A non-sd-server that happens to answer 200 (e.g. an SPA fallback page) isn't an image server
                val capabilities = runCatching { response.body<SdCppCapabilities>() }.getOrNull()
                capabilities?.takeIf { it.supportsImgGen() }
            }
            404, 405 -> null
            // An auth proxy in front of the host (empty body, `{}`, or a login page) rejects /sdcpp/*
            // before sd-server sees it: that's "not an sd-server we can talk to", not a bad API key.
            401, 403 -> {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (isProxiedAuthResponse(response.status.value, body)) null
                else throw imageApiExceptionFor(response.status.value, body)
            }
            else -> throw response.toImageApiException()
        }
    }

    /** Submits an image job and returns the server's job id. */
    suspend fun submit(baseUrl: String, apiKey: String?, request: SdCppImgGenRequest): String = call(baseUrl) {
        val response = client.post("${baseUrl.trimBaseUrl()}/sdcpp/v1/img_gen") {
            bearer(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        when (response.status.value) {
            in 200..299 -> response.body<SdCppSubmitResponse>().id
            // On submit a 404 means the endpoint is missing, not that a job vanished
            404, 405 -> throw ImageApiException(
                ImageApiException.Kind.REJECTED,
                "This server doesn't support stable-diffusion.cpp's image API.",
                response.status.value,
            )
            else -> throw response.toImageApiException()
        }
    }

    /** Current state of a job. A 404/410 surfaces as [ImageApiException.Kind.JOB_GONE]. */
    suspend fun poll(baseUrl: String, apiKey: String?, jobId: String): SdCppJob = call(baseUrl) {
        val response = client.get("${baseUrl.trimBaseUrl()}/sdcpp/v1/jobs/${jobId.encodeURLPathPart()}") {
            bearer(apiKey)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        if (response.status.value in 200..299) response.body<SdCppJob>() else throw response.toImageApiException()
    }

    enum class CancelOutcome {
        CANCELLED,
        /** The job is already generating; sd-server can't interrupt it, so it will finish in the background. */
        SERVER_BUSY,
        /** The server no longer has the job. */
        GONE,
    }

    suspend fun cancel(baseUrl: String, apiKey: String?, jobId: String): CancelOutcome = call(baseUrl) {
        val response = client.post("${baseUrl.trimBaseUrl()}/sdcpp/v1/jobs/${jobId.encodeURLPathPart()}/cancel") {
            bearer(apiKey)
            timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
        }
        when (response.status.value) {
            in 200..299 -> CancelOutcome.CANCELLED
            409 -> CancelOutcome.SERVER_BUSY
            404, 410 -> CancelOutcome.GONE
            else -> throw response.toImageApiException()
        }
    }

    private suspend fun <T> call(baseUrl: String, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val mapped = e.toImageApiException(hostOf(baseUrl))
        if (mapped is ImageApiException) Log.e(TAG, "sd-server call failed: ${mapped.kind}", e)
        throw mapped
    }

    companion object {
        private const val TAG = "SdCppImageClient"
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }
}

internal fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://").substringBefore('/').ifBlank { baseUrl }
