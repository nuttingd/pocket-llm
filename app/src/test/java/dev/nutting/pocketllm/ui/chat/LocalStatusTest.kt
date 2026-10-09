package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.llm.InferenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStatusTest {

    @Test
    fun `native phases parse into statuses`() {
        assertEquals(InferenceStatus.EncodingImage(1, 2), InferenceStatus.fromNativePhase("image:1:2", 0))
        assertEquals(InferenceStatus.ProcessingPrompt(512, 1200, 80), InferenceStatus.fromNativePhase("prompt:512:1200:80", 512))
        assertEquals(InferenceStatus.Generating(7, 12.5f), InferenceStatus.fromNativePhase("generating:12.5", 7))
        assertEquals(InferenceStatus.Generating(0, 0f), InferenceStatus.fromNativePhase("generating", 0))
        assertNull(InferenceStatus.fromNativePhase("complete", 0))
        assertNull(InferenceStatus.fromNativePhase("prompt:x", 0))
    }

    @Test
    fun `status text describes each phase`() {
        assertEquals("Loading model…", describeLocalStatus(InferenceStatus.LoadingModel).label)
        assertEquals("Reading image 2 of 3…", describeLocalStatus(InferenceStatus.EncodingImage(2, 3)).label)
        assertEquals("Reading image…", describeLocalStatus(InferenceStatus.EncodingImage(1, 1)).label)

        val prompt = describeLocalStatus(InferenceStatus.ProcessingPrompt(340, 1200, 512))
        assertEquals("340 / 1,200 tokens · 512 cached", prompt.detail)
        assertEquals(340f / 1200f, prompt.progress!!, 1e-6f)

        assertNull(describeLocalStatus(InferenceStatus.ProcessingPrompt(0, 0, 0)).progress)
    }

    @Test
    fun `elapsed time is compact`() {
        assertEquals("9s", formatElapsed(9))
        assertEquals("2m 5s", formatElapsed(125))
    }
}
