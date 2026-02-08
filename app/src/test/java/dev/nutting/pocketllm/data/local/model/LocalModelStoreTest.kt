package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val testModel = LocalModel(
        id = "test-model-1",
        name = "Test Model",
        parameterCount = "2.2B",
        quantization = "Q4_K_M",
        modelFileName = "test-model.gguf",
        projectorFileName = "test-projector.gguf",
        modelSizeBytes = 1_000_000L,
        projectorSizeBytes = 500_000L,
        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
        minimumRamMb = 4096,
        contextWindowSize = 2048,
    )

    @Test
    fun `serialize and deserialize LocalModel round-trip`() {
        val encoded = json.encodeToString(testModel)
        val decoded = json.decodeFromString<LocalModel>(encoded)
        assertEquals(testModel, decoded)
    }

    @Test
    fun `serialize and deserialize List of LocalModel round-trip`() {
        val models = listOf(
            testModel,
            testModel.copy(id = "test-model-2", name = "Second Model", downloadStatus = DownloadStatus.COMPLETE),
        )
        val encoded = json.encodeToString(models)
        val decoded = json.decodeFromString<List<LocalModel>>(encoded)
        assertEquals(2, decoded.size)
        assertEquals(models, decoded)
    }

    @Test
    fun `DownloadStatus enum serializes correctly`() {
        for (status in DownloadStatus.entries) {
            val model = testModel.copy(downloadStatus = status)
            val encoded = json.encodeToString(model)
            val decoded = json.decodeFromString<LocalModel>(encoded)
            assertEquals(status, decoded.downloadStatus)
        }
    }

    @Test
    fun `empty list serializes to empty JSON array`() {
        val encoded = json.encodeToString<List<LocalModel>>(emptyList())
        assertEquals("[]", encoded)
        val decoded = json.decodeFromString<List<LocalModel>>(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `totalSizeBytes computed correctly`() {
        assertEquals(1_500_000L, testModel.totalSizeBytes)
    }

    @Test
    fun `default values applied correctly`() {
        val minimal = LocalModel(
            id = "minimal",
            name = "Minimal",
            parameterCount = "1B",
            quantization = "Q4_0",
            modelFileName = "m.gguf",
            projectorFileName = "",
            modelSizeBytes = 100L,
            projectorSizeBytes = 0L,
        )
        assertEquals(DownloadStatus.NOT_DOWNLOADED, minimal.downloadStatus)
        assertEquals(0L, minimal.downloadedBytes)
        assertEquals(null, minimal.sourceUrl)
        assertEquals(null, minimal.projectorSourceUrl)
        assertEquals(false, minimal.isImported)
        assertEquals(4096, minimal.minimumRamMb)
        assertEquals(2048, minimal.contextWindowSize)
    }

    @Test
    fun `deserialization ignores unknown keys`() {
        val jsonWithExtra = """{"id":"x","name":"X","parameterCount":"1B","quantization":"Q4","modelFileName":"m.gguf","projectorFileName":"","modelSizeBytes":100,"projectorSizeBytes":0,"unknownField":"value"}"""
        val decoded = json.decodeFromString<LocalModel>(jsonWithExtra)
        assertEquals("x", decoded.id)
    }
}
