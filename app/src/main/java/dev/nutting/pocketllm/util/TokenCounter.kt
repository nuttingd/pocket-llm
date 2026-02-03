package dev.nutting.pocketllm.util

import dev.nutting.pocketllm.data.local.entity.MessageEntity

object TokenCounter {

    private const val CHARS_PER_TOKEN = 4

    fun estimateTokens(text: String): Int =
        (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun estimateTokens(messages: List<MessageEntity>): Int =
        messages.sumOf { estimateTokens(it.content) + 4 } // +4 per message for role/formatting overhead
}
