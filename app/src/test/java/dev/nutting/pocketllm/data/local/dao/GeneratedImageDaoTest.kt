package dev.nutting.pocketllm.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.domain.image.ImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GeneratedImageDaoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var database: PocketLlmDatabase

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketLlmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.conversationDao().insert(ConversationEntity(id = "c1", title = "Images", createdAt = 1, updatedAt = 1))
        database.messageDao().insert(MessageEntity(id = "u1", conversationId = "c1", role = "user", content = "a fox", createdAt = 1))
        database.messageDao().insert(
            MessageEntity(id = "a1", conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 2)
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `given an image reply is saved twice when upserted then each index is stored once with the latest data`() = runTest {
        val dao = database.generatedImageDao()

        dao.upsertAll(listOf(image("g1", idx = 0, path = "images/c1/a1_0.png"), image("g2", idx = 1, path = "images/c1/a1_1.png")))
        dao.upsertAll(listOf(image("g3", idx = 0, path = "images/c1/a1_0.webp")))

        val stored = dao.getForMessages(listOf("a1"))
        assertEquals(listOf(0, 1), stored.map { it.idx })
        assertEquals("images/c1/a1_0.webp", stored[0].filePath)
        assertEquals(listOf(stored[0], stored[1]), database.generatedImageDao().observeForConversation("c1").first())
    }

    @Test
    fun `given an image reply when its message is deleted then its job and image rows are removed`() = runTest {
        database.imageJobDao().insert(job())
        database.generatedImageDao().upsertAll(listOf(image("g1", idx = 0, path = "images/c1/a1_0.png")))

        database.messageDao().deleteById("a1")

        assertNull(database.imageJobDao().getById("j1"))
        assertTrue(database.generatedImageDao().getAllFilePaths().isEmpty())
    }

    @Test
    fun `given unfinished and finished jobs when listing active jobs then only unfinished ones are returned`() = runTest {
        database.imageJobDao().insert(job())
        database.messageDao().insert(
            MessageEntity(id = "a2", conversationId = "c1", parentMessageId = "u1", role = "assistant", content = "", depth = 1, createdAt = 3)
        )
        database.imageJobDao().insert(job(id = "j2", messageId = "a2", status = ImageJobStatus.SUCCEEDED))

        assertEquals(listOf("j1"), database.imageJobDao().getActive().map { it.id })
    }

    @Test
    fun `given a conversation with generated images when it is deleted then its rows and image files are removed`() = runTest {
        val store = ImageStore(tmp.newFolder("files"))
        val path = store.write("c1", "a1", 0, byteArrayOf(1, 2), "png")
        database.generatedImageDao().upsertAll(listOf(image("g1", idx = 0, path = path)))

        ConversationRepository(database.conversationDao(), store).delete("c1")

        assertTrue(database.generatedImageDao().getAllFilePaths().isEmpty())
        assertFalse(store.file(path).exists())
    }

    private fun image(id: String, idx: Int, path: String) = GeneratedImageEntity(
        id = id, messageId = "a1", idx = idx, filePath = path, mimeType = "image/png", width = 1024, height = 1024,
        seed = 42L + idx, prompt = "a fox", paramsJson = "{}", modelId = "sd-cpp-local", createdAt = 5,
    )

    private fun job(id: String = "j1", messageId: String = "a1", status: ImageJobStatus = ImageJobStatus.SUBMITTED) = ImageJobEntity(
        id = id, conversationId = "c1", messageId = messageId, serverId = "s1", modelId = "sd-cpp-local",
        dialect = ImageDialect.SD_CPP, kind = ImageJobKind.GENERATE, prompt = "a fox", paramsJson = "{}",
        status = status, createdAt = 1,
    )
}
