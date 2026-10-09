package dev.nutting.pocketllm.llm

/** What the local engine is doing right now, for display while a reply is pending. */
sealed interface InferenceStatus {
    data object LoadingModel : InferenceStatus

    /** Encoding image [index] (1-based) of [count] for this request. */
    data class EncodingImage(val index: Int, val count: Int) : InferenceStatus

    /** Evaluating the prompt: [done] of [total] new tokens, with [cached] tokens reused from the KV cache. */
    data class ProcessingPrompt(val done: Int, val total: Int, val cached: Int) : InferenceStatus

    data class Generating(val tokens: Int, val tokensPerSecond: Float) : InferenceStatus

    companion object {
        /**
         * Parses a native progress phase: "image:<index>:<count>", "prompt:<done>:<total>:<cached>",
         * "generating" or "generating:<tok/s>". Returns null for anything else.
         */
        fun fromNativePhase(phase: String, tokens: Int): InferenceStatus? {
            val parts = phase.split(':')
            return when (parts[0]) {
                "image" -> {
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val count = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    EncodingImage(index, count)
                }
                "prompt" -> {
                    val done = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val total = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    val cached = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    ProcessingPrompt(done, total, cached)
                }
                "generating" -> Generating(tokens, parts.getOrNull(1)?.toFloatOrNull() ?: 0f)
                else -> null
            }
        }
    }
}
