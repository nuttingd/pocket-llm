package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerKind
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppCapabilities
import dev.nutting.pocketllm.data.remote.image.imageJson
import dev.nutting.pocketllm.domain.image.ImageParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePickerStateTest {

    private val server = ServerProfileEntity("img", "Desktop", "http://h:8080", kind = ServerKind.IMAGE, createdAt = 0, updatedAt = 0)

    @Test
    fun `given an sd-server then its one model is the image model`() {
        val state = ChatUiState(selectedImageServer = server, imageDialect = ImageDialect.SD_CPP, imageModels = listOf("qwen_image_2.1"))
        assertEquals("qwen_image_2.1", state.selectedImageModelId)
    }

    @Test
    fun `given an OpenAI images server then the model chosen for it is used, and none until one is chosen`() {
        val models = listOf("flux-schnell", "gpt-image-1")
        val unset = ChatUiState(selectedImageServer = server, imageDialect = ImageDialect.OPENAI_IMAGES, imageModels = models)
        assertNull(unset.selectedImageModelId)

        val chosen = unset.copy(selectedImageServer = server.copy(imageModelId = "gpt-image-1"))
        assertEquals("gpt-image-1", chosen.selectedImageModelId)
    }

    @Test
    fun `given image mode without an image server then prompts still go to chat`() {
        assertFalse(ChatUiState(imageMode = true).imageModeActive)
        assertTrue(ChatUiState(imageMode = true, selectedImageServer = server).imageModeActive)
    }

    @Test
    fun `given guidance 1 from the server or the settings then a negative prompt is flagged as ignored`() {
        val turbo = imageJson.decodeFromString(
            SdCppCapabilities.serializer(),
            """{"supported_modes":["img_gen"],"defaults_by_mode":{"img_gen":{"sample_params":{"guidance":{"txt_cfg":1.0}}}}}""",
        )
        val state = ChatUiState(selectedImageServer = server, imageDialect = ImageDialect.SD_CPP, imageCapabilities = turbo)

        assertTrue(state.negativePromptIgnored)
        assertFalse(state.copy(imageParams = ImageParameters(cfg = 4f)).negativePromptIgnored)
        assertFalse(ChatUiState(selectedImageServer = server).negativePromptIgnored)
    }
}
