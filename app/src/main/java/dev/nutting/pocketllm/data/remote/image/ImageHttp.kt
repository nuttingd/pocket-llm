package dev.nutting.pocketllm.data.remote.image

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** A failed image server call, classified so callers can decide whether to retry, and with a user-facing message. */
class ImageApiException(
    val kind: Kind,
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        UNREACHABLE, TIMEOUT, UNAUTHORIZED, QUEUE_FULL, MODEL_LOADING, MODEL_NOT_LOADED,
        /** 4xx other than the above: the server rejected the request (bad parameters, unsupported edit, …). */
        REJECTED,
        SERVER,
        /** The server no longer knows the job (restarted, or the finished result expired). */
        JOB_GONE,
        /** The server answered 2xx with something we couldn't use. */
        BAD_RESPONSE,
    }

    /** Worth retrying a status poll for: the job may still be running on the server. */
    val isTransient: Boolean get() = kind == Kind.UNREACHABLE || kind == Kind.TIMEOUT || kind == Kind.SERVER || kind == Kind.MODEL_LOADING
}

internal val imageJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/** One shared client per image API; requests that can run long override the timeouts. */
internal fun imageHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(imageJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        // Without this OkHttp's 10 s read timeout would cut off a slow submit or poll
        socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
        requestTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}

internal fun defaultImageHttpClient(): HttpClient = imageHttpClient(OkHttp.create())

internal const val CONNECT_TIMEOUT_MS = 15_000L
internal const val DEFAULT_SOCKET_TIMEOUT_MS = 60_000L

internal fun HttpRequestBuilder.bearer(apiKey: String?) {
    if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
}

internal fun String.trimBaseUrl() = trimEnd('/')

/** Decodes a base64 image (raw or data URL), reporting bad data as a server response problem. */
internal fun decodeImageBase64(data: String): ByteArray {
    val payload = if (data.startsWith("data:")) data.substringAfter(",", "") else data
    return try {
        java.util.Base64.getMimeDecoder().decode(payload)
    } catch (e: IllegalArgumentException) {
        throw ImageApiException(ImageApiException.Kind.BAD_RESPONSE, "The image server sent an image the app couldn't decode.", cause = e)
    }.also {
        if (it.isEmpty()) throw ImageApiException(ImageApiException.Kind.BAD_RESPONSE, "The image server sent an empty image.")
    }
}

/** Maps a non-2xx response to an [ImageApiException], extracting the server's message from whatever error shape it uses. */
internal suspend fun HttpResponse.toImageApiException(): ImageApiException {
    val code = status.value
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val detail = errorDetail(body)
    val serverCode = detail.code
    val serverMessage = detail.message
    fun withDetail(base: String) = if (serverMessage != null && serverMessage != base) "$base ($serverMessage)" else base
    return when {
        code == 401 || code == 403 -> ImageApiException(ImageApiException.Kind.UNAUTHORIZED, "Authentication failed. Check the server's API key.", code)
        serverCode == "model_loading" -> ImageApiException(ImageApiException.Kind.MODEL_LOADING, "The server is still loading its model. Try again shortly.", code)
        serverCode == "model_not_loaded" -> ImageApiException(ImageApiException.Kind.MODEL_NOT_LOADED, "The server has no model loaded.", code)
        code == 429 -> ImageApiException(ImageApiException.Kind.QUEUE_FULL, withDetail("The server's queue is full. Try again later."), code)
        code == 404 || code == 410 -> ImageApiException(ImageApiException.Kind.JOB_GONE, withDetail("The server no longer has this job."), code)
        code in 400..499 -> ImageApiException(ImageApiException.Kind.REJECTED, serverMessage ?: "The server rejected the request ($code).", code)
        else -> ImageApiException(ImageApiException.Kind.SERVER, serverMessage?.let { "Server error ($code): $it" } ?: "Server error ($code).", code)
    }
}

internal data class ErrorDetail(val message: String?, val code: String?)

/**
 * Pulls a human-readable message and a machine code out of the error bodies image servers send:
 * sd-server `{"error":"…"}` / `{"error":"server_error","message":"…"}` / `{"code":"model_loading",…}`,
 * OpenAI `{"error":{"message":"…","code":"…"}}`, FastAPI `{"detail":"…"}` / `{"detail":[{"msg":"…"}]}`, or plain text.
 */
internal fun errorDetail(body: String): ErrorDetail {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ErrorDetail(null, null)
    val obj = runCatching { imageJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: return ErrorDetail(trimmed.takeIf { it.length <= 500 }, null)
    val error = obj["error"]
    val errorObj = error as? JsonObject
    val message = errorObj?.string("message")
        ?: obj.string("message")
        ?: (error as? JsonPrimitive)?.contentOrNull
        ?: obj.string("detail")
        ?: ((obj["detail"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("msg")
    val code = obj.string("code") ?: errorObj?.string("code")
    return ErrorDetail(message?.takeIf { it.isNotBlank() }, code)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Maps transport failures to [ImageApiException]; cancellation and already-mapped errors pass through. */
internal fun Throwable.toImageApiException(host: String): Throwable = when (this) {
    is CancellationException, is ImageApiException -> this
    is HttpRequestTimeoutException, is SocketTimeoutException ->
        ImageApiException(ImageApiException.Kind.TIMEOUT, "The image server didn't respond in time.", cause = this)
    is UnknownHostException, is ConnectException ->
        ImageApiException(ImageApiException.Kind.UNREACHABLE, "Couldn't reach $host. Check that the image server is running.", cause = this)
    is IOException -> ImageApiException(ImageApiException.Kind.UNREACHABLE, "Lost the connection to $host.", cause = this)
    is kotlinx.serialization.SerializationException ->
        ImageApiException(ImageApiException.Kind.BAD_RESPONSE, "The image server sent a response the app couldn't read.", cause = this)
    else -> this
}
