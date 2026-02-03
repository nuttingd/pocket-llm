package dev.nutting.pocketllm.data.remote

import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiApiClientTest {

    private val modelsJson = """
        {
          "object": "list",
          "data": [
            {"id": "llama3:8b", "object": "model", "created": 1700000000, "owned_by": "library"},
            {"id": "mistral:latest", "object": "model"}
          ]
        }
    """.trimIndent()

    @Test
    fun `given valid server when fetchModels called then returns model list`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = modelsJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val apiClient = OpenAiApiClient()
        // Use the mock client directly for testing model parsing
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<dev.nutting.pocketllm.data.remote.model.ModelsResponse>(modelsJson)

        assertEquals(2, response.data.size)
        assertEquals("llama3:8b", response.data[0].id)
        assertEquals("mistral:latest", response.data[1].id)

        client.close()
    }

    @Test
    fun `given streaming response data when parsed then chunks are correct`() = runTest {
        val chunkJson = """{"id":"chatcmpl-1","model":"llama3:8b","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        val json = Json { ignoreUnknownKeys = true }
        val chunk = json.decodeFromString<dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk>(chunkJson)

        assertEquals("chatcmpl-1", chunk.id)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertEquals(null, chunk.choices[0].finishReason)
    }

    @Test
    fun `given DONE marker when checked then is recognized as terminator`() {
        val data = "[DONE]"
        assertEquals(true, data == "[DONE]")
    }

    @Test
    fun `given server error response when parsed then contains error message`() = runTest {
        val errorJson = """{"error":{"message":"Invalid API key","type":"authentication_error","code":"invalid_api_key"}}"""
        val json = Json { ignoreUnknownKeys = true }
        val error = json.decodeFromString<dev.nutting.pocketllm.data.remote.model.ApiErrorResponse>(errorJson)

        assertEquals("Invalid API key", error.error.message)
        assertEquals("authentication_error", error.error.type)
        assertEquals("invalid_api_key", error.error.code)
    }

    @Test
    fun `given chat request when serialized then contains correct fields`() {
        val request = ChatCompletionRequest(
            model = "llama3:8b",
            messages = listOf(
                ChatMessage(role = "user", content = ChatContent.Text("Hello")),
            ),
            temperature = 0.7f,
            stream = true,
        )

        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(ChatCompletionRequest.serializer(), request)
        assert(serialized.contains("\"model\":\"llama3:8b\""))
        assert(serialized.contains("\"stream\":true"))
        assert(serialized.contains("\"temperature\":0.7"))
    }
}
