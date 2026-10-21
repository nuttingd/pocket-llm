package dev.nutting.pocketllm.domain.image

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
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
                    respond("""{"supported_modes":["img_gen"],"limits":{"max_batch_count":8}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/cancel") -> { cancelCalls += request.url.encodedPath; respond("{}", cancelStatus) }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    private fun TestScope.launcher(random: Random = Random(7)) = ImageJobLauncher(
        db = db,
        classifier = ModelClassifier(sdCpp, db.modelClassificationDao(), apiKey = { null }),
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
        val jobId = launcher().generate("c1", "a red fox in snow", server, "sd-cpp-local")

        val job = db.imageJobDao().getById(jobId)!!
        assertEquals(ImageJobStatus.QUEUED_LOCAL, job.status)
        assertEquals(ImageDialect.SD_CPP, job.dialect)
        assertEquals("a red fox in snow", job.prompt)
        assertEquals(listOf("user:a red fox in snow", "assistant:"), branch())
        val reply = db.messageDao().getById(job.messageId)!!
        assertEquals("sd-cpp-local", reply.modelId)
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

        val job = db.imageJobDao().getById(launcher().generate("c1", "a fox", server, "sd-cpp-local"))!!

        val params = imageJson.decodeFromString(ResolvedImageParameters.serializer(), job.paramsJson)
        assertEquals(listOf(768, 1344, 2, 8), listOf(params.width, params.height, params.count, params.steps))
        assertEquals(42L, params.seed)
    }

    @Test
    fun `given an image reply when regenerated then a sibling reply with the same prompt and a new seed becomes active`() = runTest {
        val launcher = launcher()
        val first = db.imageJobDao().getById(launcher.generate("c1", "a fox", server, "sd-cpp-local"))!!
        val firstReply = db.messageDao().getById(first.messageId)!!

        val secondId = launcher.regenerate(firstReply, server, "sd-cpp-local")!!

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
        val jobId = launcher.generate("c1", "a fox", server, "sd-cpp-local")
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
        val jobId = launcher.generate("c1", "a fox", server, "sd-cpp-local")
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
        val jobId = launcher.generate("c1", "a fox", server, "sd-cpp-local")
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
        val jobId = launcher.generate("c1", "a fox", server, "sd-cpp-local")
        db.imageJobDao().markSucceeded(jobId, null, 2)

        launcher.cancel(jobId)

        assertEquals(ImageJobStatus.SUCCEEDED, db.imageJobDao().getById(jobId)!!.status)
        assertTrue(scheduler.cancelled.isEmpty())
    }
}
