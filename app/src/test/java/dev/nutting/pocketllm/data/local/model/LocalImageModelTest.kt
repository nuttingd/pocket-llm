package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageModelTest {

    private val model = LocalImageModel(
        id = "sd15",
        name = "SD 1.5",
        family = ImageModelFamily.SD15,
        components = listOf(
            ImageModelComponent(ImageComponentRole.DIFFUSION, "sd15.gguf", 2_000_000_000L, "https://hf/sd15.gguf"),
            ImageModelComponent(ImageComponentRole.VAE, "vae.gguf", 300_000_000L, "https://hf/vae.gguf"),
        ),
        quantization = "Q8_0",
        minRamMb = 4096,
        capabilities = ImageModelCapabilities(maxWidth = 1024, maxHeight = 1024, supportsImg2Img = true),
    )

    @Test
    fun `given a manifest then the diffusion component and total size are derived`() {
        assertEquals("sd15.gguf", model.diffusionFile)
        assertEquals(2_300_000_000L, model.totalSizeBytes)
    }

    @Test
    fun `given device ram then fitting is decided by the manifest minimum`() {
        assertTrue(model.fitsIn(8_192))
        assertFalse(model.fitsIn(3_072))
    }

    @Test
    fun `given a model with no diffusion component then the engine cannot load it`() {
        assertNull(model.copy(components = emptyList()).diffusionFile)
    }

    @Test
    fun `given a manifest then it round-trips through json`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(model, json.decodeFromString<LocalImageModel>(json.encodeToString(LocalImageModel.serializer(), model)))
    }
}
