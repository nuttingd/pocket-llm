package dev.nutting.pocketllm.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelHeuristicTest {

    private val visionKeywords = listOf(
        "vision", "llava", "bakllava", "cogvlm", "fuyu",
        "obsidian", "moondream", "minicpm-v", "internvl",
    )

    private fun isLikelyVisionModel(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return visionKeywords.any { lower.contains(it) }
    }

    @Test
    fun `given llava model when checking then returns true`() {
        assertTrue(isLikelyVisionModel("llava:13b"))
    }

    @Test
    fun `given llava-v1_6 model when checking then returns true`() {
        assertTrue(isLikelyVisionModel("llava-v1.6-vicuna-7b"))
    }

    @Test
    fun `given bakllava model when checking then returns true`() {
        assertTrue(isLikelyVisionModel("bakllava"))
    }

    @Test
    fun `given gpt-4-vision model when checking then returns true`() {
        assertTrue(isLikelyVisionModel("gpt-4-vision-preview"))
    }

    @Test
    fun `given moondream model when checking then returns true`() {
        assertTrue(isLikelyVisionModel("moondream2"))
    }

    @Test
    fun `given plain llama model when checking then returns false`() {
        assertFalse(isLikelyVisionModel("llama3:8b"))
    }

    @Test
    fun `given mistral model when checking then returns false`() {
        assertFalse(isLikelyVisionModel("mistral:7b-instruct"))
    }

    @Test
    fun `given qwen model when checking then returns false`() {
        assertFalse(isLikelyVisionModel("qwen2.5:14b"))
    }

    @Test
    fun `given case-insensitive match when checking then returns true`() {
        assertTrue(isLikelyVisionModel("LLaVA-Next"))
    }
}
