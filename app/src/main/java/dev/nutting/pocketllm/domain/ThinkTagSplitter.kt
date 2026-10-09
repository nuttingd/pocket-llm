package dev.nutting.pocketllm.domain

/**
 * Splits streamed model output into answer text and `<think>…</think>` reasoning as it arrives, so
 * reasoning can be shown live. Tags may be split across chunks: a trailing fragment that could start a
 * tag is held back until the next chunk (or [flush]) resolves it.
 */
class ThinkTagSplitter(
    private val openTag: String = "<think>",
    private val closeTag: String = "</think>",
) {
    data class Piece(val text: String, val thinking: Boolean)

    private val pending = StringBuilder()
    private var inThink = false
    private var trimNextAnswer = false

    fun feed(text: String): List<Piece> {
        pending.append(text)
        val out = mutableListOf<Piece>()
        while (true) {
            val tag = if (inThink) closeTag else openTag
            val idx = pending.indexOf(tag)
            if (idx >= 0) {
                emit(out, pending.substring(0, idx))
                pending.delete(0, idx + tag.length)
                inThink = !inThink
                // The answer usually follows the reasoning after blank lines; don't start it with them
                if (!inThink) trimNextAnswer = true
                continue
            }
            val emitLen = pending.length - partialTagSuffix(tag)
            if (emitLen > 0) {
                emit(out, pending.substring(0, emitLen))
                pending.delete(0, emitLen)
            }
            return out
        }
    }

    /** Emits anything held back. Call once the stream ends. */
    fun flush(): List<Piece> {
        val out = mutableListOf<Piece>()
        emit(out, pending.toString())
        pending.clear()
        return out
    }

    private fun emit(out: MutableList<Piece>, raw: String) {
        var text = raw
        if (!inThink && trimNextAnswer) {
            text = text.trimStart()
            if (text.isEmpty()) return
            trimNextAnswer = false
        }
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.thinking == inThink) {
            out[out.size - 1] = last.copy(text = last.text + text)
        } else {
            out += Piece(text, inThink)
        }
    }

    /** Length of the longest suffix of [pending] that is a proper prefix of [tag]. */
    private fun partialTagSuffix(tag: String): Int {
        for (k in minOf(pending.length, tag.length - 1) downTo 1) {
            if (pending.endsWith(tag.substring(0, k))) return k
        }
        return 0
    }
}
