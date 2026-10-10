package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.remote.model.FunctionCall
import dev.nutting.pocketllm.data.remote.model.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GenerationManagerTest {

    private var localCancels = 0
    private val titles = mutableListOf<Triple<String, TitleRequest, String>>()

    private fun TestScope.manager() = GenerationManager(
        cancelLocalInference = { localCancels++ },
        generateTitle = { id, request, reply -> titles += Triple(id, request, reply) },
        scope = backgroundScope,
        clock = { 1_000L },
    )

    private fun message(content: String) =
        MessageEntity(id = "m", conversationId = "c1", role = "assistant", content = content, createdAt = 0)

    @Test
    fun `reply state is available to an observer that attaches mid-stream`() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        val events = Channel<StreamState>(Channel.UNLIMITED)

        assertNull(manager.start("c1", isLocal = true) { events.receiveAsFlow() })
        events.send(StreamState.Delta("Hel", thinkingContent = "hmm"))
        events.send(StreamState.Delta("lo"))

        // A screen opened now (after the reply started) sees everything so far
        val gen = manager.generation("c1").first()
        assertNotNull(gen)
        assertEquals("Hello", gen!!.content)
        assertEquals("hmm", gen.thinking)
        assertEquals(1_000L, gen.startedAtMs)
        assertTrue(gen.isLocal)

        events.send(StreamState.Complete(message("Hello")))
        events.close()
        assertNull(manager.generation("c1").first())
    }

    @Test
    fun `error is kept until dismissed`() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        manager.start("c1", isLocal = false) { flow { emit(StreamState.Error("boom")) } }

        assertNull(manager.generation("c1").first())
        assertEquals("boom", manager.error("c1").first())
        manager.dismissError("c1")
        assertNull(manager.error("c1").first())
    }

    @Test
    fun `refuses a second reply in the same chat and a second on-device reply anywhere`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = manager()
            val endless: () -> Flow<StreamState> = { flow { awaitCancellation() } }

            assertNull(manager.start("c1", isLocal = true, stream = endless))
            assertNotNull(manager.start("c1", isLocal = false, stream = endless))
            assertNotNull(manager.start("c2", isLocal = true, stream = endless))
            assertNull(manager.start("c3", isLocal = false, stream = endless))
        }

    @Test
    fun `stop cancels the reply and interrupts native inference only for on-device replies`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = manager()
            manager.start("remote", isLocal = false) { flow { awaitCancellation() } }
            manager.start("local", isLocal = true) { flow { awaitCancellation() } }

            manager.stop("remote")
            assertEquals(0, localCancels)
            assertNull(manager.generation("remote").first())

            manager.stop("local")
            assertEquals(1, localCancels)
            assertNull(manager.generation("local").first())

            // Stopped chats can start again
            assertNull(manager.start("local", isLocal = true) { flow { awaitCancellation() } })
        }

    @Test
    fun `tool approval is exposed on the generation and resolved by the user`() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        val toolCalls = listOf(ToolCall(id = "t1", function = FunctionCall("clock", "{}")))
        manager.start("c1", isLocal = false) { flow { awaitCancellation() } }

        val approval = async { manager.awaitToolApproval("c1", toolCalls) }
        assertEquals(toolCalls, manager.generation("c1").first()!!.pendingToolCalls)

        manager.resolveToolCalls("c1", approved = true)
        assertTrue(approval.await())
        assertTrue(manager.generation("c1").first()!!.pendingToolCalls.isEmpty())
    }

    @Test
    fun `title is generated after the first reply completes, even with no screen attached`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = manager()
            val request = TitleRequest(serverId = "s1", modelId = "m1", userMessage = "Hi")
            manager.start("c1", isLocal = false, title = request) {
                flow { emit(StreamState.Complete(message("Hello there"))) }
            }

            assertEquals(listOf(Triple("c1", request, "Hello there")), titles)
        }

    @Test
    fun `no title is requested without a title request`() = runTest(UnconfinedTestDispatcher()) {
        val manager = manager()
        manager.start("c1", isLocal = false) { flow { emit(StreamState.Complete(message("x"))) } }
        assertFalse(titles.isNotEmpty())
    }
}
