package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalInferenceProviderTest {

    private val testModel = LocalModel(
        id = "test-model-1",
        name = "Test Model",
        parameterCount = "2.2B",
        quantization = "Q4_K_M",
        modelFileName = "test-model.gguf",
        projectorFileName = "test-projector.gguf",
        modelSizeBytes = 1_000_000L,
        projectorSizeBytes = 500_000L,
        downloadStatus = DownloadStatus.COMPLETE,
        minimumRamMb = 4096,
        contextWindowSize = 2048,
    )

    @Test
    fun `model data is correctly passed through`() {
        assertNotNull(testModel.id)
        assertEquals("test-model.gguf", testModel.modelFileName)
        assertEquals("test-projector.gguf", testModel.projectorFileName)
        assertEquals(2048, testModel.contextWindowSize)
        assertEquals(4096, testModel.minimumRamMb)
        assertEquals(DownloadStatus.COMPLETE, testModel.downloadStatus)
    }

    @Test
    fun `total size bytes is sum of model and projector`() {
        assertEquals(1_500_000L, testModel.totalSizeBytes)
    }

    @Test
    fun `download status transitions`() {
        val downloading = testModel.copy(downloadStatus = DownloadStatus.DOWNLOADING, downloadedBytes = 500_000L)
        assertEquals(DownloadStatus.DOWNLOADING, downloading.downloadStatus)
        assertEquals(500_000L, downloading.downloadedBytes)

        val complete = downloading.copy(downloadStatus = DownloadStatus.COMPLETE, downloadedBytes = 1_500_000L)
        assertEquals(DownloadStatus.COMPLETE, complete.downloadStatus)
    }

    @Test
    fun `imported model defaults`() {
        val imported = testModel.copy(
            id = "imported-123",
            isImported = true,
            downloadStatus = DownloadStatus.COMPLETE,
        )
        assertEquals(true, imported.isImported)
        assertEquals("imported-123", imported.id)
    }
}
