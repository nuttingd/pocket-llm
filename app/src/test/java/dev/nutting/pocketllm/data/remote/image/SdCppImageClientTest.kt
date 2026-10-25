package dev.nutting.pocketllm.data.remote.image

import android.app.Application
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SdCppImageClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SdCppImageClient { imageHttpClient(MockEngine { request -> requests += request; handler(request) }) }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(String((request.body as OutgoingContent.ByteArrayContent).bytes())).jsonObject

    @Test
    fun `given an sd-server with image generation when probed then its capabilities are returned`() = runTest {
        val client = client {
            json(
                """{"model":{"name":"qwen_image_2.1"},"supported_modes":["img_gen"],
                   "defaults_by_mode":{"img_gen":{"width":2048,"height":2048,"sample_params":{"sample_method":"euler","sample_steps":8,"guidance":{"txt_cfg":1.0}}}},
                   "features_by_mode":{"img_gen":{"ref_images":true,"cancel_queued":true,"cancel_generating":false,"preview":true}},
                   "samplers":["euler","euler_a"],"preview_modes":["none","proj","vae"],
                   "limits":{"min_width":64,"max_width":4096,"min_height":64,"max_height":4096,"max_batch_count":8,"max_queue_size":64}}"""
            )
        }

        val caps = client.probe("http://sd:8080/", "key")

        assertNotNull(caps)
        assertEquals("http://sd:8080/sdcpp/v1/capabilities", requests.single().url.toString())
        assertEquals("Bearer key", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(8, caps!!.imgGenDefaults?.sampleParams?.sampleSteps)
        assertEquals(8, caps.limits?.maxBatchCount)
        assertTrue(caps.imgGenFeature("ref_images"))
        assertFalse(caps.imgGenFeature("cancel_generating"))
        assertEquals(listOf("none", "proj", "vae"), caps.previewModes)
    }

    @Test
    fun `given a server without the native API when probed then it is not an sd-server`() = runTest {
        assertNull(client { json("""{"error":"not found"}""", HttpStatusCode.NotFound) }.probe("http://llama:8080", null))
        assertNull(client { json("<html>app</html>") }.probe("http://spa:8080", null))
        assertNull(client { json("""{"supported_modes":["vid_gen"]}""") }.probe("http://video:8080", null))
    }

    @Test
    fun `given an sd-server that errors when probed then the error is raised rather than misclassifying it`() = runTest {
        val result = runCatching { client { json("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }.probe("http://sd:8080", null) }

        assertEquals(ImageApiException.Kind.SERVER, (result.exceptionOrNull() as ImageApiException).kind)
    }

    @Test
    fun `given an auth proxy in front of the host when probed then it is not treated as an sd-server`() = runTest {
        // A gateway that answers before sd-server does: `{}`, an empty body, or a login page carries no
        // server error, so this is "not an sd-server", not "the API key is wrong"
        assertNull(client { json("{}", HttpStatusCode.Unauthorized) }.probe("https://litellm.example", "key"))
        assertNull(client { respond("", HttpStatusCode.Unauthorized) }.probe("http://gateway:4000", "key"))
        assertNull(
            client { respond("<html>log in</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html")) }
                .probe("http://gateway:4000", "key"),
        )
    }

    @Test
    fun `given an sd-server rejecting the key when probed then the auth failure is raised`() = runTest {
        val result = runCatching {
            client { json("""{"error":"invalid api key"}""", HttpStatusCode.Unauthorized) }.probe("http://sd:8080", "wrong")
        }

        assertEquals(ImageApiException.Kind.UNAUTHORIZED, (result.exceptionOrNull() as ImageApiException).kind)
    }

    @Test
    fun `given a job request when submitted then it posts the native body and returns the job id`() = runTest {
        val client = client { json("""{"id":"job_1","kind":"img_gen","status":"queued","created":1,"poll_url":"/sdcpp/v1/jobs/job_1"}""", HttpStatusCode.Accepted) }
        val request = SdCppImgGenRequest(prompt = "a fox", width = 1024, height = 768, seed = 7, refImages = listOf("data:image/jpeg;base64,AAAA"))

        val id = client.submit("http://sd:8080", null, request)

        assertEquals("job_1", id)
        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/sdcpp/v1/img_gen", sent.url.encodedPath)
        assertNull(sent.headers[HttpHeaders.Authorization])
        val body = bodyOf(sent)
        assertEquals("7", body["seed"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA", body["ref_images"]!!.toString().trim('[', ']', '"'))
        // Unset optional fields are omitted so the server's defaults apply
        assertFalse("negative_prompt" in body)
        assertFalse("sample_params" in body)
        assertFalse("batch_count" in body)
    }

    @Test
    fun `given a full queue or an unloaded model when submitting then the failure kind says so`() = runTest {
        suspend fun kindFor(status: HttpStatusCode, body: String) = (runCatching {
            client { json(body, status) }.submit("http://sd:8080", null, SdCppImgGenRequest(prompt = "x", width = 64, height = 64, seed = 1))
        }.exceptionOrNull() as ImageApiException).kind

        assertEquals(ImageApiException.Kind.QUEUE_FULL, kindFor(HttpStatusCode.TooManyRequests, """{"error":"job queue is full"}"""))
        assertEquals(ImageApiException.Kind.MODEL_LOADING, kindFor(HttpStatusCode.ServiceUnavailable, """{"error":"model is loading","code":"model_loading"}"""))
        assertEquals(ImageApiException.Kind.MODEL_NOT_LOADED, kindFor(HttpStatusCode.ServiceUnavailable, """{"error":"no model","code":"model_not_loaded"}"""))
        assertEquals(ImageApiException.Kind.REJECTED, kindFor(HttpStatusCode.NotFound, "Not Found"))
    }

    @Test
    fun `given a rejected job when submitting then the server's message is shown`() = runTest {
        val error = runCatching {
            client { json("""{"error":"width must be a multiple of 32"}""", HttpStatusCode.BadRequest) }
                .submit("http://sd:8080", null, SdCppImgGenRequest(prompt = "x", width = 100, height = 64, seed = 1))
        }.exceptionOrNull() as ImageApiException

        assertEquals(ImageApiException.Kind.REJECTED, error.kind)
        assertEquals("width must be a multiple of 32", error.message)
    }

    @Test
    fun `given each job state when polled then status, progress, result and error are parsed`() = runTest {
        val queued = client { json("""{"id":"j","status":"queued","created":1,"queue_position":2,"result":null,"error":null}""") }
            .poll("http://sd:8080", null, "j")
        assertEquals("queued", queued.status)
        assertEquals(2, queued.queuePosition)

        val generating = client { json("""{"id":"j","status":"generating","started":5,"preview":{"pass":1,"step":3,"total_steps":8,"b64_json":"iVBO"}}""") }
            .poll("http://sd:8080", null, "j")
        assertEquals(3, generating.preview?.step)
        assertEquals(8, generating.preview?.totalSteps)

        val done = client { json("""{"id":"j","status":"completed","started":5,"completed":9,"result":{"output_format":"png","images":[{"index":0,"b64_json":"iVBO"}]}}""") }
            .poll("http://sd:8080", null, "j")
        assertEquals("iVBO", done.result!!.images.single().b64Json)
        assertEquals(9L, done.completed)

        val failed = client { json("""{"id":"j","status":"failed","error":{"code":"generation_failed","message":"out of memory"}}""") }
            .poll("http://sd:8080", null, "j")
        assertEquals("out of memory", failed.error?.message)
    }

    @Test
    fun `given the server forgot the job when polled then it is reported as gone`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
            val error = runCatching { client { json("""{"error":"job not found"}""", status) }.poll("http://sd:8080", null, "j") }
                .exceptionOrNull() as ImageApiException
            assertEquals(ImageApiException.Kind.JOB_GONE, error.kind)
            assertFalse(error.isTransient)
        }
    }

    @Test
    fun `given cancel responses then queued jobs cancel and generating jobs report the server is busy`() = runTest {
        assertEquals(SdCppImageClient.CancelOutcome.CANCELLED, client { json("""{"id":"j","status":"cancelled"}""") }.cancel("http://sd:8080", null, "j"))
        assertEquals(
            SdCppImageClient.CancelOutcome.SERVER_BUSY,
            client { json("""{"error":"job is currently generating and cannot be interrupted yet"}""", HttpStatusCode.Conflict) }.cancel("http://sd:8080", null, "j"),
        )
        assertEquals(SdCppImageClient.CancelOutcome.GONE, client { json("{}", HttpStatusCode.Gone) }.cancel("http://sd:8080", null, "j"))
        assertEquals("/sdcpp/v1/jobs/j/cancel", requests.first().url.encodedPath)
    }

    @Test
    fun `given the server is down when called then the failure is transient and names the host`() = runTest {
        val error = runCatching {
            SdCppImageClient { imageHttpClient(MockEngine { throw java.net.ConnectException("Connection refused") }) }
                .poll("http://sd.lan:8080", null, "j")
        }.exceptionOrNull() as ImageApiException

        assertEquals(ImageApiException.Kind.UNREACHABLE, error.kind)
        assertTrue(error.isTransient)
        assertTrue(error.message!!.contains("sd.lan:8080"))
    }
}
