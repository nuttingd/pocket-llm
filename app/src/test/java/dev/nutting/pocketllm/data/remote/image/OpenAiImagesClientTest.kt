package dev.nutting.pocketllm.data.remote.image

import android.app.Application
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenAiImagesClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)
    private val b64 = Base64.getEncoder().encodeToString(png)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        OpenAiImagesClient { imageHttpClient(MockEngine { request -> requests += request; handler(request) }) }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val request = OpenAiImagesRequest(model = "qwen-image", prompt = "a fox", n = 1, size = "1024x768")

    @Test
    fun `given a generation request then it asks for base64 at an explicit size and decodes the images`() = runTest {
        val client = client { json("""{"created":1,"output_format":"png","data":[{"b64_json":"$b64"},{"b64_json":"data:image/png;base64,$b64"}]}""") }

        val images = client.generate("http://img:9000/", "k", request, timeoutMs = 60_000)

        assertEquals(2, images.size)
        images.forEach { assertArrayEquals(png, it) }
        val sent = requests.single()
        assertEquals("/v1/images/generations", sent.url.encodedPath)
        assertEquals("Bearer k", sent.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(String((sent.body as OutgoingContent.ByteArrayContent).bytes())).jsonObject
        assertEquals("b64_json", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("1024x768", body["size"]!!.jsonPrimitive.content)
        assertEquals("qwen-image", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `given a server that answers with a url then the image is downloaded`() = runTest {
        val client = client { req ->
            if (req.url.encodedPath == "/generated-images/a.png") respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            else json("""{"data":[{"url":"/generated-images/a.png"}]}""")
        }

        val images = client.generate("http://localai:8080/v1", null, request, timeoutMs = 60_000)

        assertArrayEquals(png, images.single())
        assertEquals("http://localai:8080/generated-images/a.png", requests.last().url.toString())
    }

    @Test
    fun `given an edit then images are sent as repeated image parts with the model field`() = runTest {
        val client = client { json("""{"data":[{"b64_json":"$b64"}]}""") }

        client.edit(
            "http://img:9000", null, request,
            images = listOf(OpenAiImagesClient.InputImage(png, "image/png"), OpenAiImagesClient.InputImage(png, "image/jpeg")),
            timeoutMs = 60_000,
        )

        val sent = requests.single()
        assertEquals("/v1/images/edits", sent.url.encodedPath)
        val multipart = readBody(sent.body)
        assertEquals(2, Regex("name=\"?image\\[]\"?").findAll(multipart).count())
        assertTrue(multipart.contains("name=\"model\"") || multipart.contains("name=model"))
        assertTrue(multipart.contains("qwen-image"))
    }

    @Test
    fun `given an empty or undecodable response then it is reported as a bad response`() = runTest {
        for (body in listOf("""{"data":[]}""", """{"data":[{"b64_json":"!!!"}]}""", """{"data":[{}]}""")) {
            val error = runCatching { client { json(body) }.generate("http://img", null, request, 60_000) }.exceptionOrNull()
            assertEquals(body, ImageApiException.Kind.BAD_RESPONSE, (error as ImageApiException).kind)
        }
    }

    @Test
    fun `given the server lacks edit support then its message is passed through`() = runTest {
        val error = runCatching {
            client { json("""{"detail":"This model does not support image editing"}""", HttpStatusCode.BadRequest) }
                .edit("http://img", null, request, listOf(OpenAiImagesClient.InputImage(png, "image/png")), 60_000)
        }.exceptionOrNull() as ImageApiException

        assertEquals(ImageApiException.Kind.REJECTED, error.kind)
        assertEquals("This model does not support image editing", error.message)
    }

    private suspend fun readBody(content: Any): String = when (content) {
        is OutgoingContent.ByteArrayContent -> String(content.bytes())
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            content.writeTo(channel)
            channel.close()
            String(channel.readRemaining().readByteArray())
        }
        else -> error("Unexpected body type ${content::class}")
    }
}
