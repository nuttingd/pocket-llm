package dev.nutting.pocketllm.data.remote.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val gatewayModels = """
        {
          "data": [
            {"id": "llmbox-embed/nomic-embed-text-v1.5", "object": "model", "mode": "embedding"},
            {"id": "sd/sd-cpp-local", "object": "model", "mode": "image_generation"},
            {"id": "llmbox/DeepSeek-V4-Flash", "object": "model"}
          ]
        }
    """.trimIndent()

    private val plainModels = """
        {"data": [
            {"id": "llama3:8b", "object": "model"},
            {"id": "mistral:latest", "object": "model"}
        ]}
    """.trimIndent()

    private fun models(body: String) = json.decodeFromString<ModelsResponse>(body).data

    @Test
    fun `given a gateway that reports modes then each model is classified`() {
        val models = models(gatewayModels)

        assertEquals("embedding", models[0].mode)
        assertTrue(models[0].isEmbeddingModel)
        assertFalse(models[0].isChatModel)

        assertTrue(models[1].isImageModel)
        assertFalse(models[1].isChatModel)

        assertNull(models[2].mode)
        assertTrue(models[2].isChatModel)
        assertFalse(models[2].isImageModel)
    }

    @Test
    fun `given a gateway that reports modes then chat and image lists are split`() {
        val models = models(gatewayModels)

        assertEquals(listOf("llmbox/DeepSeek-V4-Flash"), models.chatModels().map { it.id })
        assertEquals(listOf("sd/sd-cpp-local"), models.imageModels().map { it.id })
    }

    @Test
    fun `given a server that reports no modes then every model is kept for both flows`() {
        val models = models(plainModels)

        assertEquals(listOf("llama3:8b", "mistral:latest"), models.chatModels().map { it.id })
        assertEquals(listOf("llama3:8b", "mistral:latest"), models.imageModels().map { it.id })
    }
}
