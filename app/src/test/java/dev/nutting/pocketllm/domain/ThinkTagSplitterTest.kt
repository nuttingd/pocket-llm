package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.domain.ThinkTagSplitter.Piece
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkTagSplitterTest {

    /** Feeds [chunks] in order, flushes, and merges adjacent pieces of the same kind. */
    private fun split(vararg chunks: String): List<Piece> {
        val splitter = ThinkTagSplitter()
        val pieces = chunks.flatMap { splitter.feed(it) } + splitter.flush()
        return pieces.fold(mutableListOf()) { acc, p ->
            val last = acc.lastOrNull()
            if (last != null && last.thinking == p.thinking) acc[acc.size - 1] = last.copy(text = last.text + p.text) else acc += p
            acc
        }
    }

    @Test
    fun `plain text passes through as answer`() {
        assertEquals(listOf(Piece("Hello there", false)), split("Hello", " there"))
    }

    @Test
    fun `think block is separated from the answer`() {
        assertEquals(
            listOf(Piece("let me see", true), Piece("Paris.", false)),
            split("<think>let me see</think>\n\nParis."),
        )
    }

    @Test
    fun `tags split across chunks are recognized`() {
        assertEquals(
            listOf(Piece("hmm", true), Piece("Answer", false)),
            split("<th", "ink>h", "mm</th", "ink>", "\n", "Answer"),
        )
    }

    @Test
    fun `reasoning streams before the closing tag arrives`() {
        val splitter = ThinkTagSplitter()
        assertEquals(emptyList<Piece>(), splitter.feed("<think>"))
        assertEquals(listOf(Piece("step one", true)), splitter.feed("step one"))
        assertEquals(listOf(Piece(" step two", true)), splitter.feed(" step two</thi"))
        assertEquals(listOf(Piece("Done", false)), splitter.feed("nk>Done"))
    }

    @Test
    fun `a lone angle bracket that is not a tag is emitted on flush`() {
        assertEquals(listOf(Piece("a < b and x <", false)), split("a < b and x <"))
    }

    @Test
    fun `unterminated think block stays reasoning`() {
        assertEquals(listOf(Piece("still thinking", true)), split("<think>still thinking"))
    }
}
