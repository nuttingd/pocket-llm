package dev.nutting.pocketllm.domain.image

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.imageHttpClient
import dev.nutting.pocketllm.data.remote.image.imageJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageJobLauncherTest {

    private lateinit var db: PocketLlmDatabase
    private val server = ServerProfileEntity(id = "s1", name = "sd", baseUrl = "http://sd:8080", createdAt = 0, updatedAt = 0)
    private var cancelStatus = HttpStatusCode.OK
    private var capabilities = """{"supported_modes":["img_gen"],"limits":{"max_batch_count":8}}"""
    private val cancelCalls = mutableListOf<String>()

    private class FakeScheduler : ImageJobScheduler {
        val enqueued = mutableListOf<Pair<String, Boolean>>()
        val cancelled = mutableListOf<String>()
        override fun enqueue(jobId: String, replace: Boolean) { enqueued += jobId to replace }
        override fun cancel(jobId: String) { cancelled += jobId }
    }

    private val scheduler = FakeScheduler()

    private val sdCpp = SdCppImageClient {
        imageHttpClient(MockEngine { request ->
            when {
                request.url.encodedPath == "/sdcpp/v1/capabilities" ->
                    respond(capabilities, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/cancel") -> { cancelCalls += request.url.encodedPath; respond("{}", cancelStatus) }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    private fun TestScope.launcher(random: Random = Random(7)) = ImageJobLauncher(
        db = db,
        detector = ImageServerDetector(sdCpp, apiKey = { null }),
        scheduler = scheduler,
        sdCpp = sdCpp,
        apiKey = { null },
        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
        clock = { 5_000L },
        random = random,
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java)
            .allowMainThreadQueries().build()
        db.serverProfileDao().insert(server)
        db.conversationDao().insert(ConversationEntity(id = "c1", title = "Images", lastServerProfileId = "s1", createdAt = 0, updatedAt = 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun branch(): List<String> {
        val leaf = db.conversationDao().getById("c1").first()!!.activeLeafMessageId!!
        return db.messageDao().getActiveBranch(leaf).first().map { "${it.role}:${it.content}" }
    }

    @Test
    fun `given an image model when the user sends a prompt then the prompt, a pending reply and a job are created and started`() = runTest {
        val jobId = launcher().generate("c1", "a red fox in snow", server)

        val job = db.imageJobDao().getById(jobId)!!
        assertEquals(ImageJobStatus.QUEUED_LOCAL, job.status)
        assertEquals(ImageDialect.SD_CPP, job.dialect)
        assertEquals("a red fox in snow", job.prompt)
        assertEquals(listOf("user:a red fox in snow", "assistant:"), branch())
        val reply = db.messageDao().getById(job.messageId)!!
        assertEquals("stable-diffusion.cpp", reply.modelId)
        assertEquals(1, reply.depth)
        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson)
        assertEquals(1024, params.width)
        assertNotNull(params.seed)
        assertEquals(listOf(jobId to false), scheduler.enqueued)
    }

    @Test
    fun `given saved image settings when the user sends a prompt then the job uses them`() = runTest {
        db.conversationDao().insert(
            ConversationEntity(
                id = "c1", title = "Images", lastServerProfileId = "s1", createdAt = 0, updatedAt = 0,
                imageParamsJson = """{"width":768,"height":1344,"count":2,"seed":42,"steps":8}""",
            )
        )

        val job = db.imageJobDao().getById(launcher().generate("c1", "a fox", server))!!

        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson)
        assertEquals(listOf(768, 1344, 2, 8), listOf(params.width, params.height, params.count, params.steps))
        assertEquals(42L, params.seed)
    }

    @Test
    fun `given an image reply when regenerated then a sibling reply with the same prompt and a new seed becomes active`() = runTest {
        val launcher = launcher()
        val first = db.imageJobDao().getById(launcher.generate("c1", "a fox", server))!!
        val firstReply = db.messageDao().getById(first.messageId)!!

        val secondId = launcher.regenerate(firstReply, server, "stable-diffusion.cpp")!!

        val second = db.imageJobDao().getById(secondId)!!
        val secondReply = db.messageDao().getById(second.messageId)!!
        assertEquals(firstReply.parentMessageId, secondReply.parentMessageId)
        assertEquals("a fox", second.prompt)
        assertNotEquals(first.paramsJson, second.paramsJson)
        assertEquals(second.messageId, db.conversationDao().getById("c1").first()!!.activeLeafMessageId)
        assertEquals(2, db.messageDao().getById(firstReply.parentMessageId!!)!!.childCount)
    }

    @Test
    fun `given a failed job when retried then it starts over with a fresh seed, and a successful job is left alone`() = runTest {
        val launcher = launcher()
        val jobId = launcher.generate("c1", "a fox", server)
        val before = db.imageJobDao().getById(jobId)!!
        db.imageJobDao().markFinishedUnsuccessfully(jobId, ImageJobStatus.FAILED, "server", "boom", 1)

        launcher.retry(jobId)

        val after = db.imageJobDao().getById(jobId)!!
        assertEquals(ImageJobStatus.QUEUED_LOCAL, after.status)
        assertNull(after.errorMessage)
        assertNotEquals(before.paramsJson, after.paramsJson)
        assertEquals(jobId to true, scheduler.enqueued.last())

        db.imageJobDao().markSucceeded(jobId, null, 2)
        val enqueuedBefore = scheduler.enqueued.size
        launcher.retry(jobId)
        assertEquals(ImageJobStatus.SUCCEEDED, db.imageJobDao().getById(jobId)!!.status)
        assertEquals(enqueuedBefore, scheduler.enqueued.size)
    }

    @Test
    fun `given a job queued on the server when the user cancels then it is cancelled locally and on the server`() = runTest {
        val launcher = launcher()
        val jobId = launcher.generate("c1", "a fox", server)
        db.imageJobDao().markSubmitted(jobId, "job_9")

        launcher.cancel(jobId)?.join()

        assertEquals(ImageJobStatus.CANCELLED, db.imageJobDao().getById(jobId)!!.status)
        assertEquals(listOf(jobId), scheduler.cancelled)
        assertEquals(listOf("/sdcpp/v1/jobs/job_9/cancel"), cancelCalls)
    }

    @Test
    fun `given a job already generating when the user cancels then the reply explains the server will finish it`() = runTest {
        cancelStatus = HttpStatusCode.Conflict
        val launcher = launcher()
        val jobId = launcher.generate("c1", "a fox", server)
        db.imageJobDao().markSubmitted(jobId, "job_9")

        launcher.cancel(jobId)?.join()

        val job = db.imageJobDao().getById(jobId)!!
        assertEquals(ImageJobStatus.CANCELLED, job.status)
        assertEquals(ImageJobLauncher.CANCELLED_SERVER_BUSY, job.errorCode)
        assertTrue(job.errorMessage!!.contains("background"))
    }

    @Test
    fun `given a finished job when cancel is tapped late then nothing changes`() = runTest {
        val launcher = launcher()
        val jobId = launcher.generate("c1", "a fox", server)
        db.imageJobDao().markSucceeded(jobId, null, 2)

        launcher.cancel(jobId)

        assertEquals(ImageJobStatus.SUCCEEDED, db.imageJobDao().getById(jobId)!!.status)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    /** A PNG header is all [sniffImage] needs to read the size. */
    private fun pngDataUrl(width: Int, height: Int): String {
        val header = java.nio.ByteBuffer.allocate(33)
            .put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            .putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height).put(byteArrayOf(8, 2, 0, 0, 0)).putInt(0)
            .array()
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(header)
    }

    @Test
    fun `given attachments with an image model then an edit job is created from the prompt message, sized like the photo`() = runTest {
        val photo = pngDataUrl(600, 800)

        val job = db.imageJobDao().getById(launcher().edit("c1", "make it night time", listOf(photo), server))!!

        assertEquals(ImageJobKind.EDIT, job.kind)
        val user = db.messageDao().getById(job.sourceMessageId!!)!!
        assertEquals("make it night time", user.content)
        assertEquals(photo, user.imageUris)
        assertEquals(user.id, db.messageDao().getById(job.messageId)!!.parentMessageId)
        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson)
        assertEquals(896 to 1184, params.width to params.height)
        assertEquals(listOf(job.id to false), scheduler.enqueued)
    }

    @Test
    fun `given a size chosen in the image settings then an edit uses it instead of the photo's`() = runTest {
        db.conversationDao().insert(
            ConversationEntity(id = "c1", title = "Images", lastServerProfileId = "s1", createdAt = 0, updatedAt = 0, imageParamsJson = """{"width":512,"height":512}""")
        )

        val job = db.imageJobDao().getById(launcher().edit("c1", "night", listOf(pngDataUrl(600, 800)), server))!!

        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson)
        assertEquals(512 to 512, params.width to params.height)
    }

    @Test
    fun `given a server that says it takes no input images then an edit is refused before anything is created`() = runTest {
        capabilities = """{"supported_modes":["img_gen"],"features_by_mode":{"img_gen":{"ref_images":false}}}"""

        val refused = runCatching { launcher().edit("c1", "night", listOf(pngDataUrl(64, 64)), server) }

        assertTrue(refused.exceptionOrNull() is ImageJobLauncher.EditNotSupportedException)
        assertNull(db.conversationDao().getById("c1").first()!!.activeLeafMessageId)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `given an edit reply when regenerated then the variation edits the same photo`() = runTest {
        val launcher = launcher()
        val first = db.imageJobDao().getById(launcher.edit("c1", "night", listOf(pngDataUrl(600, 800)), server))!!

        val second = db.imageJobDao().getById(launcher.regenerate(db.messageDao().getById(first.messageId)!!, server, "stable-diffusion.cpp")!!)!!

        assertEquals(ImageJobKind.EDIT, second.kind)
        assertEquals(first.sourceMessageId, second.sourceMessageId)
        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), second.paramsJson)
        assertEquals(896 to 1184, params.width to params.height)
    }

    @Test
    fun `given an image server's model is set then jobs use it`() = runTest {
        val job = db.imageJobDao().getById(launcher().generate("c1", "a fox", server.copy(imageModelId = "flux-schnell")))!!

        assertEquals("flux-schnell", job.modelId)
    }

    @Test
    fun `given an OpenAI images server with no model set then generating is refused before anything is created`() = runTest {
        capabilities = "{}" // Not stable-diffusion.cpp
        val openAiServer = server.copy(id = "s2", baseUrl = "http://localai:8080")

        val refused = runCatching { launcher().generate("c1", "a fox", openAiServer) }

        assertTrue(refused.exceptionOrNull() is ImageJobLauncher.NoImageModelException)
        assertNull(db.conversationDao().getById("c1").first()!!.activeLeafMessageId)
    }

    @Test
    fun `given a negative prompt with a prompt then that job uses it, the next prompt doesn't, and a regenerate keeps it`() = runTest {
        val launcher = launcher()
        val first = db.imageJobDao().getById(launcher.generate("c1", "a fox", server, negativePrompt = "blurry, text"))!!
        fun negativeOf(job: dev.nutting.pocketllm.data.local.entity.ImageJobEntity) =
            imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson).negativePrompt

        assertEquals("blurry, text", negativeOf(first))
        assertNull(db.conversationDao().getById("c1").first()!!.imageParamsJson)

        val next = db.imageJobDao().getById(launcher.generate("c1", "a cat", server))!!
        assertNull(negativeOf(next))

        val again = db.imageJobDao().getById(launcher.regenerate(db.messageDao().getById(first.messageId)!!, server, "stable-diffusion.cpp")!!)!!
        assertEquals("blurry, text", negativeOf(again))
    }

    @Test
    fun `given a failed job with a negative prompt when retried then it keeps the negative prompt`() = runTest {
        val launcher = launcher()
        val jobId = launcher.generate("c1", "a fox", server, negativePrompt = "blurry")
        db.imageJobDao().markFinishedUnsuccessfully(jobId, ImageJobStatus.FAILED, "server", "boom", 1)

        launcher.retry(jobId)

        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), db.imageJobDao().getById(jobId)!!.paramsJson)
        assertEquals("blurry", params.negativePrompt)
    }
}
