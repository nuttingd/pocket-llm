package dev.nutting.pocketllm.data.remote.huggingface

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class HuggingFaceException(message: String, val statusCode: Int? = null) : Exception(message)

/** Thin client for the public Hugging Face Hub API, used to discover GGUF models. */
class HuggingFaceClient(
    internal val testClient: HttpClient? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient by lazy {
        testClient ?: HttpClient(OkHttp) {
            install(ContentNegotiation) { json(this@HuggingFaceClient.json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /** Searches for repositories containing GGUF files, most-downloaded first. */
    suspend fun searchModels(query: String, token: String? = null, limit: Int = 30): List<HfModelSummary> {
        val response = client.get("${HuggingFace.BASE_URL}/api/models") {
            parameter("search", query.trim())
            parameter("filter", "gguf")
            parameter("sort", "downloads")
            parameter("direction", "-1")
            parameter("limit", limit)
            listOf("downloads", "likes", "gated").forEach { parameter("expand[]", it) }
            authorize(token)
        }
        ensureSuccess(response, query)
        return response.body()
    }

    /** Lists the GGUF files in a repository, including their sizes. */
    suspend fun listGgufFiles(
        repoId: String,
        revision: String = HuggingFace.DEFAULT_REVISION,
        token: String? = null,
    ): HfRepoListing {
        val repoPath = repoId.split('/').joinToString("/") { it.encodeURLPathPart() }
        val path = "$repoPath/tree/${revision.encodeURLPathPart()}"
        val response = client.get("${HuggingFace.BASE_URL}/api/models/$path") {
            parameter("recursive", "true")
            authorize(token)
        }
        ensureSuccess(response, repoId)
        val entries: List<HfTreeEntry> = response.body()
        return HuggingFace.buildListing(repoId, entries, revision)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize(token: String?) {
        if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
    }

    private fun ensureSuccess(response: HttpResponse, subject: String) {
        if (response.status.isSuccess()) return
        val code = response.status.value
        val message = when (code) {
            401 -> "Hugging Face rejected the request. Check your access token."
            403 -> "Access to $subject is restricted. Accept its license on huggingface.co and add an access token."
            404 -> "$subject was not found on Hugging Face."
            429 -> "Hugging Face rate limit reached. Try again shortly."
            else -> "Hugging Face request failed (HTTP $code)."
        }
        throw HuggingFaceException(message, code)
    }
}
