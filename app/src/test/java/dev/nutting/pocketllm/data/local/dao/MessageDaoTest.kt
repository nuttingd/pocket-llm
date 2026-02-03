package dev.nutting.pocketllm.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    private lateinit var database: PocketLlmDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var serverProfileDao: ServerProfileDao

    private val testServer = ServerProfileEntity(
        id = "server-1",
        name = "Test Server",
        baseUrl = "http://localhost:11434",
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private val testConversation = ConversationEntity(
        id = "conv-1",
        title = "Test Conversation",
        lastServerProfileId = "server-1",
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PocketLlmDatabase::class.java,
        ).allowMainThreadQueries().build()
        messageDao = database.messageDao()
        conversationDao = database.conversationDao()
        serverProfileDao = database.serverProfileDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertServerAndConversation() {
        serverProfileDao.insert(testServer)
        conversationDao.insert(testConversation)
    }

    @Test
    fun `given root message inserted when getActiveBranch called then returns single message`() = runTest {
        insertServerAndConversation()
        val root = MessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "user",
            content = "Hello",
            depth = 0,
            createdAt = 1000L,
        )
        messageDao.insert(root)

        val branch = messageDao.getActiveBranch("msg-1").first()
        assertEquals(1, branch.size)
        assertEquals("msg-1", branch[0].id)
    }

    @Test
    fun `given branching messages when getChildMessages called then returns correct children`() = runTest {
        insertServerAndConversation()
        val root = MessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "user",
            content = "Hello",
            depth = 0,
            childCount = 2,
            createdAt = 1000L,
        )
        val child1 = MessageEntity(
            id = "msg-2",
            conversationId = "conv-1",
            parentMessageId = "msg-1",
            role = "assistant",
            content = "Response A",
            depth = 1,
            createdAt = 2000L,
        )
        val child2 = MessageEntity(
            id = "msg-3",
            conversationId = "conv-1",
            parentMessageId = "msg-1",
            role = "assistant",
            content = "Response B",
            depth = 1,
            createdAt = 3000L,
        )
        messageDao.insert(root)
        messageDao.insert(child1)
        messageDao.insert(child2)

        val children = messageDao.getChildMessages("msg-1").first()
        assertEquals(2, children.size)
        assertEquals("msg-2", children[0].id)
        assertEquals("msg-3", children[1].id)
    }

    @Test
    fun `given deep tree when getActiveBranch with leaf then returns full path to root`() = runTest {
        insertServerAndConversation()
        val msg1 = MessageEntity(id = "msg-1", conversationId = "conv-1", role = "user", content = "Hi", depth = 0, createdAt = 1000L)
        val msg2 = MessageEntity(id = "msg-2", conversationId = "conv-1", parentMessageId = "msg-1", role = "assistant", content = "Hello", depth = 1, createdAt = 2000L)
        val msg3 = MessageEntity(id = "msg-3", conversationId = "conv-1", parentMessageId = "msg-2", role = "user", content = "How?", depth = 2, createdAt = 3000L)
        val msg4 = MessageEntity(id = "msg-4", conversationId = "conv-1", parentMessageId = "msg-3", role = "assistant", content = "Like this", depth = 3, createdAt = 4000L)
        messageDao.insert(msg1)
        messageDao.insert(msg2)
        messageDao.insert(msg3)
        messageDao.insert(msg4)

        val branch = messageDao.getActiveBranch("msg-4").first()
        assertEquals(4, branch.size)
        assertEquals("msg-1", branch[0].id)
        assertEquals("msg-2", branch[1].id)
        assertEquals("msg-3", branch[2].id)
        assertEquals("msg-4", branch[3].id)
    }
}
