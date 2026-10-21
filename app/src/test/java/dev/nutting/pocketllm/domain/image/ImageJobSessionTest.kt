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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImageJobSessionTest {

    private lateinit var db: PocketLlmDatabase
    private val jobs get() = db.imageJobDao()
    private val shown = MutableStateFlow<List<ImageJobEntity>>(emptyList())
    private val announced = mutableListOf<ImageJobEntity>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java).build()
        db.conversationDao().insert(ConversationEntity(id = "c1", title = "Images", createdAt = 0, updatedAt = 0))
        db.messageDao().insert(MessageEntity(id = "u1", conversationId = "c1", role = "user", content = "a fox", createdAt = 0))
        db.messageDao().insert(MessageEntity(id = "a1", conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 0))
        jobs.insert(
            ImageJobEntity(
                id = "j1", conversationId = "c1", messageId = "a1", serverId = "s1", modelId = "m",
                dialect = ImageDialect.SD_CPP, kind = ImageJobKind.GENERATE, prompt = "a fox", paramsJson = "{}",
                status = ImageJobStatus.QUEUED_LOCAL, createdAt = 0,
            )
        )
    }

    @After
    fun tearDown() = db.close()

    private fun session(foregroundAllowed: Boolean = true, run: suspend (String) -> Unit) = ImageJobSession(
        jobs = jobs,
        run = run,
        showProgress = { job ->
            check(foregroundAllowed) { "ForegroundServiceStartNotAllowedException" }
            shown.value += job
        },
        announce = { announced += it },
    )

    private suspend fun awaitShown(predicate: (ImageJobEntity) -> Boolean) =
        withTimeout(5_000) { shown.first { list -> list.any(predicate) } }

    @Test
    fun `given a running job then the notification follows its queue position and steps, and the result is announced`() = runBlocking {
        session { id ->
            jobs.markSubmitted(id, "job_9")
            jobs.markProgress(id, ImageJobStatus.SUBMITTED, 2, null, null, null, null)
            awaitShown { it.queuePosition == 2 }
            jobs.markProgress(id, ImageJobStatus.GENERATING, null, 1, 3, 8, 10)
            awaitShown { it.step == 3 }
            jobs.markSucceeded(id, 10, 20)
        }.execute("j1")

        assertEquals(ImageJobStatus.QUEUED_LOCAL, shown.value.first().status)
        assertEquals(ImageJobStatus.SUCCEEDED, announced.single().status)
        assertTrue("no notification update after the job finished", shown.value.none { it.status.isTerminal })
    }

    @Test
    fun `given foreground start not allowed then the job still runs to the end and is announced`() = runBlocking {
        var ran = false
        session(foregroundAllowed = false) { id ->
            ran = true
            jobs.markFinishedUnsuccessfully(id, ImageJobStatus.FAILED, "server", "boom", 5)
        }.execute("j1")

        assertTrue(ran)
        assertTrue(shown.value.isEmpty())
        assertEquals(ImageJobStatus.FAILED, announced.single().status)
    }

    @Test
    fun `given a job already finished when its work starts again then nothing runs or is announced`() = runBlocking {
        jobs.markFinishedUnsuccessfully("j1", ImageJobStatus.CANCELLED, "cancelled", "Cancelled.", 5)
        var ran = false

        session { ran = true }.execute("j1")

        assertEquals(false, ran)
        assertTrue(shown.value.isEmpty() && announced.isEmpty())
    }
}
