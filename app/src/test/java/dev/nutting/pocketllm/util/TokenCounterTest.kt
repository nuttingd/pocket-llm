package dev.nutting.pocketllm.util

import dev.nutting.pocketllm.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenCounterTest {

    @Test
    fun `given empty string when estimating tokens then returns zero`() {
        assertEquals(0, TokenCounter.estimateTokens(""))
    }

    @Test
    fun `given short text when estimating tokens then rounds up correctly`() {
        // "Hi" = 2 chars, ceil(2/4) = 1
        assertEquals(1, TokenCounter.estimateTokens("Hi"))
    }

    @Test
    fun `given exactly four chars when estimating then returns one token`() {
        assertEquals(1, TokenCounter.estimateTokens("abcd"))
    }

    @Test
    fun `given five chars when estimating then returns two tokens`() {
        assertEquals(2, TokenCounter.estimateTokens("abcde"))
    }

    @Test
    fun `given unicode content when estimating then counts by char length`() {
        // Each emoji is 1-2 chars in Kotlin String (surrogate pairs)
        val emoji = "\uD83D\uDE00" // grinning face, 2 chars
        assertEquals(1, TokenCounter.estimateTokens(emoji))
    }

    @Test
    fun `given long content when estimating then scales linearly`() {
        val text = "a".repeat(1000)
        assertEquals(250, TokenCounter.estimateTokens(text))
    }

    @Test
    fun `given empty message list when estimating then returns zero`() {
        assertEquals(0, TokenCounter.estimateTokens(emptyList()))
    }

    @Test
    fun `given messages when estimating then includes per-message overhead`() {
        val messages = listOf(
            MessageEntity(
                id = "1",
                conversationId = "c1",
                role = "user",
                content = "Hello",
                depth = 0,
                childCount = 0,
                createdAt = 0,
            ),
        )
        // "Hello" = 5 chars => ceil(5/4) = 2 tokens + 4 overhead = 6
        assertEquals(6, TokenCounter.estimateTokens(messages))
    }
}
