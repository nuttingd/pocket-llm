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
import dev.nutting.pocketllm.data.remote.image.imageHttpClient
import dev.nutting.pocketllm.data.remote.image.imageJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageJobRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: PocketLlmDatabase
    private lateinit var filesDir: File
    private var now = 1_000_000L
    private val requests = mutableListOf<HttpRequestData>()
    /** Poll responses served in order; the last one repeats. */
    private val polls = ArrayDeque<Pair<HttpStatusCode, String>>()
    private var onPoll: (Int) -> Unit = {}
    private var pollCount = 0

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAC0lEQVR4nGNgwAQAABQAAX3+Hu4AAAAASUVORK5CYII=")
    private val pngB64 = Base64.getEncoder().encodeToString(png)

    private val server = ServerProfileEntity(id = "s1", name = "sd", baseUrl = "http://sd:8080", imageTimeoutSeconds = 600, createdAt = 0, updatedAt = 0)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
        requests += request
        val path = request.url.encodedPath
        when {
            path == "/sdcpp/v1/img_gen" -> json("""{"id":"job_1","status":"queued"}""", HttpStatusCode.Accepted)
            path == "/sdcpp/v1/jobs/job_1" -> {
                onPoll(++pollCount)
                val (status, body) = if (polls.size > 1) polls.removeFirst() else polls.first()
                json(body, status)
            }
            path == "/sdcpp/v1/jobs/job_1/cancel" -> json("{}", HttpStatusCode.Conflict)
            path == "/v1/images/generations" -> json("""{"data":[{"b64_json":"$pngB64"}]}""")
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    private fun runner(store: ImageStore = ImageStore(filesDir)): ImageJobRunner {
        val http = { imageHttpClient(MockEngine { handle(this, it) }) }
        return ImageJobRunner(
            db = db,
            server = { id -> server.takeIf { it.id == id } },
            apiKey = { null },
            sdCpp = SdCppImageClient(http),
            openAi = OpenAiImagesClient(http),
            store = store,
            clock = { now },
            sleep = { now += it },
        )
    }

    @Before
    fun setUp() = runTest {
        filesDir = tmp.newFolder("files")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java)
            .allowMainThreadQueries().build()
        db.conversationDao().insert(ConversationEntity(id = "c1", title = "Images", createdAt = 0, updatedAt = 0))
        db.messageDao().insert(MessageEntity(id = "u1", conversationId = "c1", role = "user", content = "a fox", createdAt = 0))
        db.messageDao().insert(MessageEntity(id = "a1", conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertJob(
        status: ImageJobStatus = ImageJobStatus.QUEUED_LOCAL,
        dialect: ImageDialect = ImageDialect.SD_CPP,
        remoteJobId: String? = null,
        seed: Long? = 100,
        count: Int = 1,
    ) = db.imageJobDao().insert(
        ImageJobEntity(
            id = "j1", conversationId = "c1", messageId = "a1", serverId = "s1", modelId = "sd-cpp-local",
            dialect = dialect, kind = ImageJobKind.GENERATE, prompt = "a fox",
            paramsJson = imageJson.encodeToString(ResolvedImageParameters.serializer(), ResolvedImageParameters(1024, 1024, count, seed = seed)),
            remoteJobId = remoteJobId, status = status, createdAt = now,
        )
    )

    private suspend fun job() = db.imageJobDao().getById("j1")!!
    private suspend fun images() = db.generatedImageDao().getForMessages(listOf("a1"))
    private fun completed(images: Int = 1) =
        HttpStatusCode.OK to """{"id":"job_1","status":"completed","started":2000,"completed":50000,
            "result":{"output_format":"png","images":[${(0 until images).joinToString { """{"index":$it,"b64_json":"$pngB64"}""" }}]}}"""

    @Test
    fun `given a queued job when run then it submits, records the server job before polling, saves the images and succeeds`() = runTest {
        insertJob(count = 2)
        var remoteIdAtFirstPoll: String? = null
        onPoll = { n -> if (n == 1) remoteIdAtFirstPoll = runBlocking { job().remoteJobId } }
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"queued","queue_position":1}"""
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"generating","started":2000,"preview":{"pass":1,"step":3,"total_steps":8}}"""
        polls += completed(images = 2)

        runner().run("j1")

        assertEquals("job_1", remoteIdAtFirstPoll)
        assertEquals(1, requests.count { it.url.encodedPath == "/sdcpp/v1/img_gen" && it.method == HttpMethod.Post })
        val done = job()
        assertEquals(ImageJobStatus.SUCCEEDED, done.status)
        assertEquals(2000L, done.startedAt)
        val stored = images()
        assertEquals(listOf(0, 1), stored.map { it.idx })
        assertEquals(listOf(100L, 101L), stored.map { it.seed })
        assertEquals(3, stored[0].width)
        assertEquals(2, stored[0].height)
        assertEquals(48_000L, stored[0].durationMs)
        assertEquals("image/png", stored[0].mimeType)
        stored.forEach { assertArrayEquals(png, File(filesDir, it.filePath).readBytes()) }
    }

    @Test
    fun `given a job the server already has when the worker restarts then polling resumes without resubmitting`() = runTest {
        insertJob(status = ImageJobStatus.GENERATING, remoteJobId = "job_1")
        polls += completed()

        runner().run("j1")

        assertTrue(requests.none { it.url.encodedPath == "/sdcpp/v1/img_gen" })
        assertEquals(ImageJobStatus.SUCCEEDED, job().status)
    }

    @Test
    fun `given a job found saving when the worker restarts then the result is fetched and saved again`() = runTest {
        insertJob(status = ImageJobStatus.SAVING, remoteJobId = "job_1")
        // A file from the interrupted save is overwritten, not duplicated
        ImageStore(filesDir).write("c1", "a1", 0, byteArrayOf(1, 2, 3), "png")
        polls += completed()

        runner().run("j1")

        assertEquals(ImageJobStatus.SUCCEEDED, job().status)
        assertArrayEquals(png, File(filesDir, images().single().filePath).readBytes())
    }

    @Test
    fun `given a synchronous job found generating on restart then it fails as interrupted without a new request`() = runTest {
        insertJob(status = ImageJobStatus.GENERATING, dialect = ImageDialect.OPENAI_IMAGES)

        runner().run("j1")

        assertEquals(ImageJobStatus.FAILED, job().status)
        assertEquals("interrupted", job().errorCode)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `given the server forgot the job when polled then the job fails as interrupted`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        polls += HttpStatusCode.Gone to """{"error":"job expired"}"""

        runner().run("j1")

        assertEquals(ImageJobStatus.FAILED, job().status)
        assertEquals("interrupted", job().errorCode)
    }

    @Test
    fun `given generation outlasts the image timeout then the job fails with a timeout and the server job is cancelled`() = runTest {
        insertJob()
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"generating"}"""

        runner().run("j1")

        assertEquals(ImageJobStatus.FAILED, job().status)
        assertEquals("timeout", job().errorCode)
        assertTrue(requests.any { it.url.encodedPath == "/sdcpp/v1/jobs/job_1/cancel" })
    }

    @Test
    fun `given the server is briefly unreachable while polling then the job recovers`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        polls += HttpStatusCode.BadGateway to "upstream down"
        polls += HttpStatusCode.BadGateway to "upstream down"
        polls += completed()

        runner().run("j1")

        assertEquals(ImageJobStatus.SUCCEEDED, job().status)
    }

    @Test
    fun `given the server stays unreachable then the job eventually fails instead of waiting forever`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        polls += HttpStatusCode.ServiceUnavailable to "down"

        runner().run("j1")

        assertEquals(ImageJobStatus.FAILED, job().status)
        assertEquals("unreachable", job().errorCode)
        assertTrue(now - 1_000_000L < 10 * 60 * 1000L)
    }

    @Test
    fun `given the server reports a failed job then the server's reason is shown`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"failed","error":{"code":"generation_failed","message":"out of memory"}}"""

        runner().run("j1")

        assertEquals("server", job().errorCode)
        assertTrue(job().errorMessage!!.contains("out of memory"))
    }

    @Test
    fun `given the user cancels while the image is generating then the runner stops and saves nothing`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        onPoll = { n ->
            if (n == 2) runBlocking { db.imageJobDao().markFinishedUnsuccessfully("j1", ImageJobStatus.CANCELLED, "cancelled", "Cancelled.", now) }
        }
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"generating"}"""
        polls += completed()

        runner().run("j1")

        assertEquals(ImageJobStatus.CANCELLED, job().status)
        assertTrue(images().isEmpty())
        assertTrue(File(filesDir, "images/c1").listFiles().isNullOrEmpty())
    }

    @Test
    fun `given saving the image fails then the job fails with a storage error and leaves no image behind`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        polls += completed()
        File(filesDir, "images").writeText("not a directory")

        runner().run("j1")

        assertEquals(ImageJobStatus.FAILED, job().status)
        assertEquals("storage", job().errorCode)
        assertTrue(images().isEmpty())
    }

    @Test
    fun `given the server returns something that is not an image then the job fails as a bad response`() = runTest {
        insertJob(remoteJobId = "job_1", status = ImageJobStatus.SUBMITTED)
        val notImage = Base64.getEncoder().encodeToString("hello".toByteArray())
        polls += HttpStatusCode.OK to """{"id":"job_1","status":"completed","result":{"images":[{"index":0,"b64_json":"$notImage"}]}}"""

        runner().run("j1")

        assertEquals("bad_response", job().errorCode)
        assertTrue(images().isEmpty())
    }

    @Test
    fun `given a generic images server then one request produces the image without a seed`() = runTest {
        insertJob(dialect = ImageDialect.OPENAI_IMAGES, seed = null)

        runner().run("j1")

        assertEquals(ImageJobStatus.SUCCEEDED, job().status)
        assertEquals(listOf("/v1/images/generations"), requests.map { it.url.encodedPath })
        assertNull(images().single().seed)
    }

    @Test
    fun `given the server was removed then the job fails with an explanation`() = runTest {
        db.imageJobDao().insert(
            ImageJobEntity(
                id = "j1", conversationId = "c1", messageId = "a1", serverId = "gone", modelId = "m", dialect = ImageDialect.SD_CPP,
                kind = ImageJobKind.GENERATE, prompt = "a fox", paramsJson = "{}", status = ImageJobStatus.QUEUED_LOCAL, createdAt = now,
            )
        )

        runner().run("j1")

        assertEquals("server_gone", job().errorCode)
    }

    @Test
    fun `given two jobs queued on the server then each tracks its own queue position`() = runBlocking {
        db.messageDao().insert(MessageEntity(id = "a2", conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 0))
        insertJob(status = ImageJobStatus.SUBMITTED, remoteJobId = "job_A")
        db.imageJobDao().insert(job().copy(id = "j2", messageId = "a2", remoteJobId = "job_B"))
        val polled = mutableMapOf<String, Int>()
        val http = {
            imageHttpClient(MockEngine { request ->
                val remote = request.url.encodedPath.substringAfterLast('/')
                val n = polled.merge(remote, 1, Int::plus)!!
                val position = if (remote == "job_A") 1 else 2
                if (n == 1) json("""{"id":"$remote","status":"queued","queue_position":$position}""")
                else json("""{"id":"$remote","status":"completed","result":{"images":[{"index":0,"b64_json":"$pngB64"}]}}""")
            })
        }
        val seen = mutableMapOf<String, Int?>()
        fun runnerFor(jobId: String) = ImageJobRunner(
            db = db, server = { server }, apiKey = { null },
            sdCpp = SdCppImageClient(http), openAi = OpenAiImagesClient(http), store = ImageStore(filesDir),
            clock = { now }, sleep = { seen[jobId] = db.imageJobDao().getById(jobId)!!.queuePosition },
        )

        runnerFor("j1").run("j1")
        runnerFor("j2").run("j2")

        assertEquals(mapOf("j1" to 1, "j2" to 2), seen)
        assertEquals(ImageJobStatus.SUCCEEDED, db.imageJobDao().getById("j1")!!.status)
        assertEquals(ImageJobStatus.SUCCEEDED, db.imageJobDao().getById("j2")!!.status)
    }
}
