package dev.nutting.pocketllm.domain.image

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.OpenAiImagesClient
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.imageJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Checks the clients and runner against a real stable-diffusion.cpp `sd-server`. Skipped unless
 * `SD_SERVER_URL` is set, e.g.:
 *
 *     sd-server --listen-port 8080 -m sdxs-512-tinySDdistilled_Q8_0.gguf --steps 1 --cfg-scale 1 -W 512 -H 512
 *     ./gradlew --stop && SD_SERVER_URL=http://127.0.0.1:8080 ./gradlew :app:testDebugUnitTest --tests '*SdServerLiveContractTest'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SdServerLiveContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseUrl: String? = System.getenv("SD_SERVER_URL")
    private lateinit var db: PocketLlmDatabase
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        assumeTrue("SD_SERVER_URL not set", baseUrl != null)
        filesDir = tmp.newFolder("files")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            db.conversationDao().insert(ConversationEntity(id = "c1", title = "Live", createdAt = 0, updatedAt = 0))
            db.messageDao().insert(MessageEntity(id = "u1", conversationId = "c1", role = "user", content = "x", createdAt = 0))
            for (id in listOf("a1", "a2", "a3", "a4")) {
                db.messageDao().insert(MessageEntity(id = id, conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 0))
            }
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private val server get() = ServerProfileEntity(id = "s1", name = "live", baseUrl = baseUrl!!, imageTimeoutSeconds = 600, createdAt = 0, updatedAt = 0)

    private val seenSteps = mutableListOf<Int>()
    private val seenFrames = mutableSetOf<String>()

    private fun runner() = ImageJobRunner(
        db = db,
        server = { server },
        apiKey = { null },
        sdCpp = SdCppImageClient(),
        openAi = OpenAiImagesClient(),
        store = ImageStore(filesDir),
        previews = ImagePreviewStore(filesDir),
        previewMode = { "proj" },
        sourceImages = { job -> loadEditSources(db.messageDao(), job.sourceMessageId) },
        sleep = { ms ->
            db.imageJobDao().getById("job-a3")?.let { job ->
                job.step?.let { if (seenSteps.lastOrNull() != it) seenSteps += it }
                job.previewPath?.let { if (File(filesDir, it).exists()) seenFrames += it }
            }
            kotlinx.coroutines.delay(ms)
        },
    )

    private suspend fun runJob(
        messageId: String,
        dialect: ImageDialect,
        params: ResolvedImageParameters,
        kind: ImageJobKind = ImageJobKind.GENERATE,
        sourceMessageId: String? = null,
    ): ImageJobEntity {
        val id = "job-$messageId"
        db.imageJobDao().insert(
            ImageJobEntity(
                id = id, conversationId = "c1", messageId = messageId, serverId = "s1", modelId = "sd-cpp-local",
                dialect = dialect, kind = kind, prompt = "a red fox in snow, watercolor", sourceMessageId = sourceMessageId,
                paramsJson = imageJson.encodeToString(ResolvedImageParameters.serializer(), params),
                status = ImageJobStatus.QUEUED_LOCAL, createdAt = System.currentTimeMillis(),
            )
        )
        runner().run(id)
        return db.imageJobDao().getById(id)!!
    }

    @Test
    fun `given a real sd-server then it is detected and native jobs produce images of the requested size`() = runBlocking {
        val caps = SdCppImageClient().probe(baseUrl!!, null)
        assertNotNull("capabilities", caps)
        println("capabilities: model=${caps!!.model?.name} limits=${caps.limits} previews=${caps.previewModes} samplers=${caps.samplers.take(5)}")

        val params = ImageParameters(width = 512, height = 384, count = 2, seed = 1234).resolve(ImageDialect.SD_CPP, caps.limits)
        val job = runJob("a1", ImageDialect.SD_CPP, params)

        assertEquals(job.errorMessage, ImageJobStatus.SUCCEEDED, job.status)
        val images = db.generatedImageDao().getForMessages(listOf("a1"))
        assertEquals(2, images.size)
        images.forEach { image ->
            val info = sniffImage(File(filesDir, image.filePath).readBytes())
            assertEquals(512 to 384, info!!.width to info.height)
            println("native image ${image.idx}: ${image.mimeType} ${info.width}x${info.height} seed=${image.seed} duration=${image.durationMs}ms")
        }
        assertEquals(listOf(1234L, 1235L), images.map { it.seed })
    }

    @Test
    fun `given a real sd-server then its OpenAI images endpoint produces an image too`() = runBlocking {
        val params = ImageParameters(width = 512, height = 512).resolve(ImageDialect.OPENAI_IMAGES)
        val job = runJob("a2", ImageDialect.OPENAI_IMAGES, params)

        assertEquals(job.errorMessage, ImageJobStatus.SUCCEEDED, job.status)
        val image = db.generatedImageDao().getForMessages(listOf("a2")).single()
        assertTrue(File(filesDir, image.filePath).length() > 0)
        assertEquals(512, image.width)
    }

    @Test
    fun `given a queued job on a real sd-server then cancel and poll behave as the contract says`() = runBlocking {
        val client = SdCppImageClient()
        val params = ImageParameters(width = 512, height = 512, seed = 1).resolve(ImageDialect.SD_CPP)
        // Two jobs: the first occupies the server, so the second is still queued when cancelled
        val first = client.submit(baseUrl!!, null, params.toSdCppRequest("a lighthouse"))
        val second = client.submit(baseUrl, null, params.toSdCppRequest("a harbor"))

        val outcome = client.cancel(baseUrl, null, second)
        val polled = client.poll(baseUrl, null, second)
        println("cancel queued: $outcome, status=${polled.status} error=${polled.error}")
        assertEquals(SdCppImageClient.CancelOutcome.CANCELLED, outcome)
        assertEquals("cancelled", polled.status)

        // Wait for the first so the server is idle for other tests
        while (client.poll(baseUrl, null, first).status in setOf("queued", "generating")) Thread.sleep(500)
    }

    @Test
    fun `given a real sd-server with proj previews then step progress and frames are recorded while it generates`() = runBlocking {
        val params = ImageParameters(width = 512, height = 512, seed = 7, steps = 8).resolve(ImageDialect.SD_CPP)
        val job = runJob("a3", ImageDialect.SD_CPP, params)

        println("steps seen: $seenSteps, frames: ${seenFrames.size}")
        assertEquals(job.errorMessage, ImageJobStatus.SUCCEEDED, job.status)
        assertTrue("saw several steps", seenSteps.size >= 3)
        assertTrue("saw several frames", seenFrames.size >= 3)
        assertTrue("frames removed after", File(filesDir, ImagePreviewStore.ROOT).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `given a real sd-server then an edit's photo is accepted as a data url ref image`() = runBlocking {
        // A generated image stands in for the user's photo, attached the way the app attaches photos
        runJob("a1", ImageDialect.SD_CPP, ImageParameters(width = 512, height = 384, seed = 3).resolve(ImageDialect.SD_CPP))
        val photo = File(filesDir, db.generatedImageDao().getForMessages(listOf("a1")).first().filePath).readBytes()
        db.messageDao().insert(
            MessageEntity(
                id = "u2", conversationId = "c1", role = "user", content = "make it night time", createdAt = 0,
                imageUris = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(photo),
            )
        )

        val job = runJob("a4", ImageDialect.SD_CPP, ImageParameters(width = 512, height = 384, seed = 4).resolve(ImageDialect.SD_CPP), ImageJobKind.EDIT, "u2")

        assertEquals(job.errorMessage, ImageJobStatus.SUCCEEDED, job.status)
        assertEquals(1, db.generatedImageDao().getForMessages(listOf("a4")).size)
    }
}
