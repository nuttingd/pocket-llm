package dev.nutting.pocketllm.ui.conversations

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class ConversationListViewModelTest {

    private lateinit var database: PocketLlmDatabase
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageRepository: MessageRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PocketLlmDatabase::class.java,
        ).allowMainThreadQueries().build()
        conversationRepository = ConversationRepository(database.conversationDao())
        messageRepository = MessageRepository(database.messageDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given conversations exist when queried then sorted by updatedAt desc`() = runTest {
        val server = ServerProfileEntity(
            id = "s1", name = "Test", baseUrl = "http://localhost",
            createdAt = 1000L, updatedAt = 1000L,
        )
        database.serverProfileDao().insert(server)

        conversationRepository.create(
            ConversationEntity(id = "c1", title = "Old", lastServerProfileId = "s1", createdAt = 1000L, updatedAt = 1000L)
        )
        conversationRepository.create(
            ConversationEntity(id = "c2", title = "New", lastServerProfileId = "s1", createdAt = 2000L, updatedAt = 3000L)
        )
        conversationRepository.create(
            ConversationEntity(id = "c3", title = "Middle", lastServerProfileId = "s1", createdAt = 1500L, updatedAt = 2000L)
        )

        val conversations = conversationRepository.getAllSorted().first()
        assertEquals(3, conversations.size)
        assertEquals("c2", conversations[0].id) // newest updatedAt
        assertEquals("c3", conversations[1].id)
        assertEquals("c1", conversations[2].id) // oldest updatedAt
    }

    @Test
    fun `given conversation when renamed then title updates`() = runTest {
        conversationRepository.create(
            ConversationEntity(id = "c1", title = "Original", createdAt = 1000L, updatedAt = 1000L)
        )

        conversationRepository.rename("c1", "Renamed")

        val conversation = conversationRepository.getById("c1").first()
        assertEquals("Renamed", conversation?.title)
    }

    @Test
    fun `given conversation when deleted then removed from list`() = runTest {
        conversationRepository.create(
            ConversationEntity(id = "c1", title = "To Delete", createdAt = 1000L, updatedAt = 1000L)
        )
        conversationRepository.create(
            ConversationEntity(id = "c2", title = "To Keep", createdAt = 2000L, updatedAt = 2000L)
        )

        conversationRepository.delete("c1")

        val conversations = conversationRepository.getAllSorted().first()
        assertEquals(1, conversations.size)
        assertEquals("c2", conversations[0].id)
    }
}
