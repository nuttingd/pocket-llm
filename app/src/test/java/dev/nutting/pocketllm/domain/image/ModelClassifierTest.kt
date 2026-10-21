package dev.nutting.pocketllm.domain.image

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.imageHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelClassifierTest {

    private lateinit var database: PocketLlmDatabase
    private var probes = 0
    private var probeStatus = HttpStatusCode.OK
    private var now = 0L

    private val sdServer = ServerProfileEntity(id = "sd", name = "sd", baseUrl = "http://sd:8080", createdAt = 0, updatedAt = 0)
    private val chatServer = ServerProfileEntity(id = "llama", name = "llama", baseUrl = "http://llama:8080", createdAt = 0, updatedAt = 0)

    private val sdCpp = SdCppImageClient {
        imageHttpClient(MockEngine { request ->
            probes++
            when {
                probeStatus != HttpStatusCode.OK -> respond("", probeStatus)
                request.url.host == "sd" -> respond("""{"supported_modes":["img_gen"]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    private lateinit var classifier: ModelClassifier

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java)
            .allowMainThreadQueries().build()
        database.serverProfileDao().insert(sdServer)
        database.serverProfileDao().insert(chatServer)
        classifier = ModelClassifier(sdCpp, database.modelClassificationDao(), apiKey = { null }, clock = { now })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `given an sd-server then its model is an image model and a chat server's models are not`() = runTest {
        assertTrue(classifier.isImageModel(sdServer, "sd-cpp-local"))
        assertFalse(classifier.isImageModel(chatServer, "qwen3-8b"))
        assertEquals(ImageDialect.SD_CPP, classifier.serverInfo(sdServer).dialect)
    }

    @Test
    fun `given the user marks a model then the override wins over detection until cleared`() = runTest {
        classifier.setOverride("llama", "flux-schnell", true)
        classifier.setOverride("sd", "sd-cpp-local", false)

        assertTrue(classifier.isImageModel(chatServer, "flux-schnell"))
        assertFalse(classifier.isImageModel(sdServer, "sd-cpp-local"))
        assertEquals(mapOf("flux-schnell" to true), classifier.overrides("llama").first())

        classifier.setOverride("sd", "sd-cpp-local", null)
        assertTrue(classifier.isImageModel(sdServer, "sd-cpp-local"))
    }

    @Test
    fun `given a detected server then the probe result is cached until it expires`() = runTest {
        classifier.serverInfo(sdServer)
        classifier.serverInfo(sdServer)
        assertEquals(1, probes)

        now += 11 * 60 * 1000L
        classifier.serverInfo(sdServer)
        assertEquals(2, probes)
    }

    @Test
    fun `given the sd-server is briefly unreachable then the last known result is kept and the failure is not cached`() = runTest {
        classifier.serverInfo(sdServer)
        probeStatus = HttpStatusCode.BadGateway

        assertEquals(ImageDialect.SD_CPP, classifier.serverInfo(sdServer, forceRefresh = true).dialect)

        // An unknown server that can't be probed is treated as a chat server, but asked again next time
        val probesBefore = probes
        assertEquals(ImageDialect.OPENAI_IMAGES, classifier.serverInfo(chatServer).dialect)
        classifier.serverInfo(chatServer)
        assertEquals(probesBefore + 2, probes)
    }
}
