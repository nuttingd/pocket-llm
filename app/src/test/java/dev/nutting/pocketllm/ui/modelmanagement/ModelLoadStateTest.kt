package dev.nutting.pocketllm.ui.modelmanagement

import dev.nutting.pocketllm.domain.LocalModelState
import dev.nutting.pocketllm.llm.LlmEngine
import dev.nutting.pocketllm.llm.ModelLoadException
import dev.nutting.pocketllm.ui.chat.localModelLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadStateTest {

    @Test
    fun `load failures explain the cause`() {
        val arch = ModelLoadException.describe(
            1, "llama_model_load: error loading model architecture: unknown model architecture: 'qwen9'\n", 4096,
        )
        assertTrue(arch.startsWith("This model's architecture isn't supported"))
        assertTrue(arch.endsWith("unknown model architecture: 'qwen9'"))

        assertEquals("Not enough memory to run the model with a 8192-token context.", ModelLoadException.describe(2, "", 8192))
        assertTrue(ModelLoadException.describe(3, "", 4096).startsWith("Couldn't load the vision projector"))
        assertEquals("Couldn't load the model file.\nbad magic", ModelLoadException.describe(1, "bad magic", 4096))
    }

    @Test
    fun `chat header label reflects the selected model only`() {
        assertEquals("loaded", localModelLabel(LocalModelState.Loaded("a"), "a").text)
        assertEquals("loads when you send", localModelLabel(LocalModelState.Loaded("b"), "a").text)
        assertTrue(localModelLabel(LocalModelState.Loading("a"), "a").isBusy)
        val failed = localModelLabel(LocalModelState.Failed("a", "boom"), "a")
        assertEquals("failed to load", failed.text)
        assertTrue(failed.isError)
        assertEquals("loads when you send", localModelLabel(LocalModelState.NotLoaded, "a").text)
    }

    @Test
    fun `engine card names the model and shows failures`() {
        val names = mapOf("a" to "Qwen3 4B")
        val name = { id: String -> names[id] ?: id }
        assertEquals("Loaded: Qwen3 4B", engineStatusText(LocalModelState.Loaded("a"), LlmEngine.State.Ready, name).single().text)
        assertEquals(
            "Generating with Qwen3 4B",
            engineStatusText(LocalModelState.Loaded("a"), LlmEngine.State.Inferring, name).single().text,
        )
        val failed = engineStatusText(LocalModelState.Failed("a", "out of memory"), LlmEngine.State.Unloaded, name)
        assertEquals(listOf("Failed to load Qwen3 4B", "out of memory"), failed.map { it.text })
        assertTrue(failed.all { it.isError })
    }

    @Test
    fun `model card line only applies to that model`() {
        assertEquals("Loaded in memory", modelLoadText("a", LocalModelState.Loaded("a"))?.text)
        assertNull(modelLoadText("b", LocalModelState.Loaded("a")))
        assertEquals("boom", modelLoadText("a", LocalModelState.Failed("a", "boom"))?.text)
    }
}
