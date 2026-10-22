package dev.nutting.pocketllm.domain.image

import android.app.Application
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerKind
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.imageHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageServerDetectorTest {

    private var probes = 0
    private var probeStatus = HttpStatusCode.OK
    private var now = 0L

    private val sdServer = ServerProfileEntity(id = "sd", name = "sd", baseUrl = "http://sd:8080", kind = ServerKind.IMAGE, createdAt = 0, updatedAt = 0)
    private val otherServer = ServerProfileEntity(id = "localai", name = "localai", baseUrl = "http://localai:8080", kind = ServerKind.IMAGE, createdAt = 0, updatedAt = 0)

    private val sdCpp = SdCppImageClient {
        imageHttpClient(MockEngine { request ->
            probes++
            when {
                probeStatus != HttpStatusCode.OK -> respond("", probeStatus)
                request.url.host == "sd" -> respond(
                    """{"supported_modes":["img_gen"],"model":{"name":"qwen_image_2.1-Q4_K_M"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    private val detector = ImageServerDetector(sdCpp, apiKey = { null }, clock = { now })

    @Test
    fun `given an sd-server then it is spoken to natively and described by its model`() = runTest {
        val info = detector.serverInfo(sdServer)
        assertEquals(ImageDialect.SD_CPP, info.dialect)
        assertEquals("stable-diffusion.cpp · qwen_image_2.1-Q4_K_M", info.description)
    }

    @Test
    fun `given any other image server then it is spoken to through the OpenAI images API`() = runTest {
        val info = detector.serverInfo(otherServer)
        assertEquals(ImageDialect.OPENAI_IMAGES, info.dialect)
        assertEquals("OpenAI images API", info.description)
    }

    @Test
    fun `given a server being added then its URL can be probed before it is saved`() = runTest {
        assertEquals(ImageDialect.SD_CPP, detector.probe("http://sd:8080", null).dialect)
    }

    @Test
    fun `given a detected server then the probe result is cached until it expires`() = runTest {
        detector.serverInfo(sdServer)
        detector.serverInfo(sdServer)
        assertEquals(1, probes)

        now += 11 * 60 * 1000L
        detector.serverInfo(sdServer)
        assertEquals(2, probes)
    }

    @Test
    fun `given the sd-server is briefly unreachable then the last known result is kept and the failure is not cached`() = runTest {
        detector.serverInfo(sdServer)
        probeStatus = HttpStatusCode.BadGateway

        assertEquals(ImageDialect.SD_CPP, detector.serverInfo(sdServer, forceRefresh = true).dialect)

        // A server that can't be probed falls back to the OpenAI images API, but is asked again next time
        val probesBefore = probes
        assertEquals(ImageDialect.OPENAI_IMAGES, detector.serverInfo(otherServer).dialect)
        detector.serverInfo(otherServer)
        assertEquals(probesBefore + 2, probes)
    }
}
