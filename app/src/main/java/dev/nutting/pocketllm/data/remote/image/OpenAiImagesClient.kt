package dev.nutting.pocketllm.data.remote.image

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * The OpenAI images API as implemented by self-hosted servers (sd-server's compatibility endpoints, LocalAI,
 * vLLM-Omni, llama-swap routing). Synchronous: the response arrives when the image is done, so the request
 * timeout is the whole job's deadline, and requests are never retried automatically — generation is expensive.
 */
class OpenAiImagesClient(
    clientProvider: () -> HttpClient = ::defaultImageHttpClient,
) {
    private val client by lazy(clientProvider)

    /** An input image for an edit. */
    class InputImage(val bytes: ByteArray, val mimeType: String)

    suspend fun generate(
        baseUrl: String,
        apiKey: String?,
        request: OpenAiImagesRequest,
        timeoutMs: Long,
    ): List<ByteArray> = call(baseUrl) {
        val response = client.post("${baseUrl.trimBaseUrl()}/v1/images/generations") {
            bearer(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
        }
        if (response.status.value !in 200..299) throw response.toImageApiException()
        images(baseUrl, apiKey, response.body())
    }

    /** `POST /v1/images/edits` as multipart, repeating `image[]` (sd-server reads only one plain `image`). */
    suspend fun edit(
        baseUrl: String,
        apiKey: String?,
        request: OpenAiImagesRequest,
        images: List<InputImage>,
        timeoutMs: Long,
    ): List<ByteArray> = call(baseUrl) {
        require(images.isNotEmpty()) { "An edit needs at least one input image" }
        val form = formData {
            request.model?.let { append("model", it) }
            append("prompt", request.prompt)
            append("n", request.n.toString())
            append("size", request.size)
            append("response_format", request.responseFormat)
            append("output_format", request.outputFormat)
            images.forEachIndexed { i, image ->
                val ext = if (image.mimeType == "image/png") "png" else "jpg"
                append("image[]", image.bytes, Headers.build {
                    append(HttpHeaders.ContentType, image.mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"image$i.$ext\"")
                })
            }
        }
        val response = client.submitFormWithBinaryData("${baseUrl.trimBaseUrl()}/v1/images/edits", form) {
            bearer(apiKey)
            timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
        }
        if (response.status.value !in 200..299) throw response.toImageApiException()
        images(baseUrl, apiKey, response.body())
    }

    /** Decodes `b64_json` items, downloading any that come back as a `url` instead (LocalAI's default). */
    private suspend fun images(baseUrl: String, apiKey: String?, body: OpenAiImagesResponse): List<ByteArray> {
        if (body.data.isEmpty()) {
            throw ImageApiException(ImageApiException.Kind.BAD_RESPONSE, "The image server returned no images.")
        }
        return body.data.map { item ->
            when {
                item.b64Json != null -> decodeImageBase64(item.b64Json)
                item.url != null -> download(resolve(baseUrl, item.url), apiKey)
                else -> throw ImageApiException(ImageApiException.Kind.BAD_RESPONSE, "The image server returned an image without data.")
            }
        }
    }

    private suspend fun download(url: String, apiKey: String?): ByteArray {
        if (url.startsWith("data:")) return decodeImageBase64(url)
        val response = client.get(url) {
            bearer(apiKey)
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
        }
        if (response.status.value !in 200..299) throw response.toImageApiException()
        return response.readRawBytes()
    }

    private suspend fun <T> call(baseUrl: String, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val mapped = e.toImageApiException(hostOf(baseUrl))
        if (mapped is ImageApiException) Log.e(TAG, "Images API call failed: ${mapped.kind}", e)
        throw mapped
    }

    companion object {
        private const val TAG = "OpenAiImagesClient"
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L

        internal fun resolve(baseUrl: String, url: String): String = when {
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") -> url
            url.startsWith("/") -> {
                val scheme = baseUrl.substringBefore("://", "http")
                "$scheme://${hostOf(baseUrl)}$url"
            }
            else -> "${baseUrl.trimBaseUrl()}/$url"
        }
    }
}
