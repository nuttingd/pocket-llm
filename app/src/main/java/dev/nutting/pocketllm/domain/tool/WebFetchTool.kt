package dev.nutting.pocketllm.domain.tool

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

object WebFetchTool {

    private const val TAG = "WebFetchTool"

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    suspend fun fetch(url: String): String {
        return try {
            val response = client.get(url)
            val body = response.bodyAsText()
            // Strip HTML tags and trim to reasonable size
            val text = body.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(4000)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL: $url", e)
            "Error fetching URL: ${e.message}"
        }
    }
}
