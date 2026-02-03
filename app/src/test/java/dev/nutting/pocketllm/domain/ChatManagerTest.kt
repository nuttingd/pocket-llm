package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChunkChoice
import dev.nutting.pocketllm.data.remote.model.Delta
import dev.nutting.pocketllm.data.remote.model.Usage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `given streaming chunks when accumulated then produces full content`() {
        val chunks = listOf(
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(role = "assistant"), null))),
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(content = "Hello"), null))),
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(content = " world"), null))),
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(content = "!"), "stop")),
                usage = Usage(promptTokens = 5, completionTokens = 3, totalTokens = 8)),
        )

        val accumulated = StringBuilder()
        var finalUsage: Usage? = null
        for (chunk in chunks) {
            chunk.choices.firstOrNull()?.delta?.content?.let { accumulated.append(it) }
            chunk.usage?.let { finalUsage = it }
        }

        assertEquals("Hello world!", accumulated.toString())
        assertEquals(8, finalUsage?.totalTokens)
    }

    @Test
    fun `given think tags in content when parsed then thinking is extracted`() {
        val content = "<think>\nLet me think about this step by step...\n</think>\n\nThe answer is 42."
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(content)

        val thinkingContent = match?.groupValues?.get(1)?.trim()
        val cleanedContent = content.replace(match!!.value, "").trim()

        assertEquals("Let me think about this step by step...", thinkingContent)
        assertEquals("The answer is 42.", cleanedContent)
    }

    @Test
    fun `given reasoning_content in delta when accumulated then separate from content`() {
        val chunks = listOf(
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(reasoningContent = "Step 1: "), null))),
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(reasoningContent = "analyze"), null))),
            ChatCompletionChunk("1", "model", listOf(ChunkChoice(0, Delta(content = "The answer is 42."), "stop"))),
        )

        val content = StringBuilder()
        val thinking = StringBuilder()
        for (chunk in chunks) {
            chunk.choices.firstOrNull()?.delta?.content?.let { content.append(it) }
            chunk.choices.firstOrNull()?.delta?.reasoningContent?.let { thinking.append(it) }
        }

        assertEquals("The answer is 42.", content.toString())
        assertEquals("Step 1: analyze", thinking.toString())
    }

    @Test
    fun `given no thinking in content when parsed then thinking is null`() {
        val content = "The answer is 42."
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(content)

        assertNull(match)
    }
}
