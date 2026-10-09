package dev.nutting.pocketllm.data.remote.huggingface

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HuggingFaceClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun clientFor(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HuggingFaceClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return HuggingFaceClient(
            testClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    @Test
    fun `search sends gguf filter and parses results`() = runTest {
        val client = clientFor {
            json("""[{"id":"unsloth/Qwen3-4B-GGUF","downloads":12345,"likes":67,"gated":false,"extra":1}]""")
        }

        val results = client.searchModels("qwen3")

        assertEquals(1, results.size)
        assertEquals("unsloth/Qwen3-4B-GGUF", results[0].id)
        assertEquals(12345, results[0].downloads)
        val url = requests.single().url
        assertEquals("/api/models", url.encodedPath)
        assertEquals("qwen3", url.parameters["search"])
        assertEquals("gguf", url.parameters["filter"])
        assertEquals("downloads", url.parameters["sort"])
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `token is sent as bearer authorization`() = runTest {
        val client = clientFor { json("[]") }

        client.searchModels("llama", token = " hf_abc ")

        assertEquals("Bearer hf_abc", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `list files requests recursive tree and builds listing`() = runTest {
        val client = clientFor {
            json(
                """[
                  {"type":"file","path":"README.md","size":10},
                  {"type":"file","path":"Qwen3-4B-Q4_K_M.gguf","size":135,"lfs":{"size":2497280256}},
                  {"type":"file","path":"Qwen3-4B-Q8_0.gguf","size":135,"lfs":{"size":4280405248}}
                ]"""
            )
        }

        val listing = client.listGgufFiles("Qwen/Qwen3-4B-GGUF")

        assertEquals("/api/models/Qwen/Qwen3-4B-GGUF/tree/main", requests.single().url.encodedPath)
        assertEquals("true", requests.single().url.parameters["recursive"])
        assertEquals(listOf("Q4_K_M", "Q8_0"), listing.modelFiles.map { it.quantization })
        assertTrue(listing.projectorFiles.isEmpty())
    }

    @Test
    fun `not found maps to a readable error`() = runTest {
        val client = clientFor { json("""{"error":"Repository not found"}""", HttpStatusCode.NotFound) }

        try {
            client.listGgufFiles("nobody/nothing")
            fail("Expected HuggingFaceException")
        } catch (e: HuggingFaceException) {
            assertEquals(404, e.statusCode)
            assertTrue(e.message!!.contains("nobody/nothing"))
        }
    }
}
