package dev.nutting.pocketllm.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatManagerCompactionTest {

    private lateinit var database: PocketLlmDatabase
    private lateinit var chatManager: ChatManager
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var compactionSummaryDao: CompactionSummaryDao

    // Queue of responses the mock engine returns in order
    private val responseQueue = ArrayDeque<String>()

    private val testServer = ServerProfileEntity(
        id = "server-1",
        name = "Test Server",
        baseUrl = "http://localhost:11434",
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private val testConversation = ConversationEntity(
        id = "conv-1",
        title = "Test",
        lastServerProfileId = "server-1",
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    // A three-message chain: msg1 → msg2 → msg3 (leaf)
    private val msg1 = MessageEntity(id = "msg-1", conversationId = "conv-1", role = "user",    content = "Hello",  depth = 0, createdAt = 1000L)
    private val msg2 = MessageEntity(id = "msg-2", conversationId = "conv-1", role = "assistant", content = "Hi",   depth = 1, createdAt = 2000L, parentMessageId = "msg-1")
    private val msg3 = MessageEntity(id = "msg-3", conversationId = "conv-1", role = "user",    content = "How are you?", depth = 2, createdAt = 3000L, parentMessageId = "msg-2")
    private val msg4 = MessageEntity(id = "msg-4", conversationId = "conv-1", role = "assistant", content = "Good, thanks!", depth = 3, createdAt = 4000L, parentMessageId = "msg-3")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, PocketLlmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        conversationRepository = ConversationRepository(database.conversationDao())
        messageRepository = MessageRepository(database.messageDao())
        compactionSummaryDao = database.compactionSummaryDao()

        val encryptedDataStore = EncryptedDataStore(context)
        val serverRepository = ServerRepository(
            database.serverProfileDao(),
            encryptedDataStore,
            OpenAiApiClient(),
        )

        val mockEngine = MockEngine { _ ->
            val responseJson = responseQueue.removeFirst()
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val mockApiClient = OpenAiApiClient(testClientFactory = { _ ->
            HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
                }
            }
        })

        chatManager = ChatManager(
            serverRepository = serverRepository,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            apiClient = mockApiClient,
            compactionSummaryDao = compactionSummaryDao,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given no conversation when compactConversation called then returns null`() = runTest {
        val result = chatManager.compactConversation("missing-conv", "server-1", "model-1")
        assertNull(result)
    }

    @Test
    fun `given conversation with no active leaf when compactConversation called then returns null`() = runTest {
        database.serverProfileDao().insert(testServer)
        conversationRepository.create(testConversation) // activeLeafMessageId = null by default

        val result = chatManager.compactConversation(testConversation.id, testServer.id, "model-1")
        assertNull(result)
    }

    @Test
    fun `given only one message when compactConversation called then returns null`() = runTest {
        database.serverProfileDao().insert(testServer)
        conversationRepository.create(testConversation.copy(activeLeafMessageId = "msg-1"))
        database.messageDao().insert(msg1)

        val result = chatManager.compactConversation(testConversation.id, testServer.id, "model-1")
        assertNull(result)
    }

    @Test
    fun `given exactly two messages when compactConversation called then returns null`() = runTest {
        // dropLast(2) leaves zero messages to compact, and no prior summary → returns null
        database.serverProfileDao().insert(testServer)
        conversationRepository.create(testConversation.copy(activeLeafMessageId = "msg-2"))
        database.messageDao().insert(msg1)
        database.messageDao().insert(msg2)

        val result = chatManager.compactConversation(testConversation.id, testServer.id, "model-1")
        assertNull(result)
    }

    @Test
    fun `given three messages when compactConversation called then returns api summary and saves to dao`() = runTest {
        responseQueue.addLast(compactResponse("Compact summary."))
        database.serverProfileDao().insert(testServer)
        conversationRepository.create(testConversation.copy(activeLeafMessageId = "msg-3"))
        database.messageDao().insert(msg1)
        database.messageDao().insert(msg2)
        database.messageDao().insert(msg3)

        val result = chatManager.compactConversation(testConversation.id, testServer.id, "model-1")

        assertEquals("Compact summary.", result)
        val saved = compactionSummaryDao.getLatest(testConversation.id)
        assertNotNull(saved)
        assertEquals("Compact summary.", saved?.summary)
        // 3 messages - 2 (last pair kept live) = 1 message compacted
        assertEquals(1, saved?.compactedMessageCount)
    }

    @Test
    fun `given prior compaction exists when compactConversation called with new messages then saves incremental summary`() = runTest {
        responseQueue.addLast(compactResponse("Incremental summary."))
        database.serverProfileDao().insert(testServer)
        // 4-message chain, active leaf = msg4
        conversationRepository.create(testConversation.copy(activeLeafMessageId = "msg-4"))
        database.messageDao().insert(msg1)
        database.messageDao().insert(msg2)
        database.messageDao().insert(msg3)
        database.messageDao().insert(msg4)

        // Pre-insert a prior summary indicating msg1 was already compacted
        compactionSummaryDao.insert(
            CompactionSummaryEntity(
                id = "prior-1",
                conversationId = testConversation.id,
                summary = "Prior summary.",
                compactedMessageCount = 1,
                createdAt = 1000L,
            )
        )

        val result = chatManager.compactConversation(testConversation.id, testServer.id, "model-1")

        assertEquals("Incremental summary.", result)
        val saved = compactionSummaryDao.getLatest(testConversation.id)
        assertNotNull(saved)
        assertEquals("Incremental summary.", saved?.summary)
        // 4 messages - 2 (last pair kept live) = 2 total compacted
        assertEquals(2, saved?.compactedMessageCount)
    }

    private fun compactResponse(content: String): String =
        """{"id":"cmpl-1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}]}"""
}
