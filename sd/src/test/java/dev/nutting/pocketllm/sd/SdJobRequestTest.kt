package dev.nutting.pocketllm.sd

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SdJobRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `given a job request then it round-trips through json`() {
        val request = SdJobRequest(
            modelPath = "/models/image/sd15.gguf", prompt = "a fox", width = 512, height = 512,
            steps = 20, cfg = 7f, seed = 42, count = 1, outDir = "/cache/sd-job", sampler = "euler",
        )
        assertEquals(request, json.decodeFromString<SdJobRequest>(json.encodeToString(SdJobRequest.serializer(), request)))
    }

    @Test
    fun `given a model manifest then the path round-trips`() {
        val manifest = SdModelManifest("/models/image/sdxl-turbo.gguf")
        assertEquals(manifest, json.decodeFromString<SdModelManifest>(json.encodeToString(SdModelManifest.serializer(), manifest)))
    }
}
