package dev.nutting.pocketllm.data.remote.huggingface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceTest {

    // ---- parseReference ----

    @Test
    fun `given bare repo id when parsed then returns repo`() {
        assertEquals(HfReference("unsloth/Qwen3-0.6B-GGUF"), HuggingFace.parseReference("unsloth/Qwen3-0.6B-GGUF"))
    }

    @Test
    fun `given repo url when parsed then returns repo`() {
        assertEquals(
            HfReference("bartowski/Llama-3.2-1B-Instruct-GGUF"),
            HuggingFace.parseReference("https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF"),
        )
    }

    @Test
    fun `given hf co short url without scheme when parsed then returns repo`() {
        assertEquals(HfReference("ggml-org/gemma-3-4b-it-GGUF"), HuggingFace.parseReference("hf.co/ggml-org/gemma-3-4b-it-GGUF"))
    }

    @Test
    fun `given repo with quant tag when parsed then tag is dropped`() {
        assertEquals(HfReference("unsloth/gemma-3-1b-it-GGUF"), HuggingFace.parseReference("hf.co/unsloth/gemma-3-1b-it-GGUF:Q4_K_M"))
    }

    @Test
    fun `given resolve file url when parsed then returns file path and revision`() {
        val ref = HuggingFace.parseReference(
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"
        )
        assertEquals(HfReference("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", "main"), ref)
    }

    @Test
    fun `given blob url in subfolder when parsed then keeps nested path`() {
        val ref = HuggingFace.parseReference("https://huggingface.co/owner/repo/blob/dev/Q4_K_M/model.gguf")
        assertEquals(HfReference("owner/repo", "Q4_K_M/model.gguf", "dev"), ref)
    }

    @Test
    fun `given tree url when parsed then returns revision`() {
        assertEquals(HfReference("owner/repo", revision = "v2"), HuggingFace.parseReference("https://huggingface.co/owner/repo/tree/v2"))
    }

    @Test
    fun `given non hugging face inputs when parsed then returns null`() {
        assertNull(HuggingFace.parseReference("qwen3"))
        assertNull(HuggingFace.parseReference("llama 3 8b"))
        assertNull(HuggingFace.parseReference("https://example.com/owner/repo"))
        assertNull(HuggingFace.parseReference("https://huggingface.co/datasets/owner/data"))
        assertNull(HuggingFace.parseReference(""))
    }

    // ---- file name heuristics ----

    @Test
    fun `quantization is extracted from common file names`() {
        assertEquals("Q4_K_M", HuggingFace.quantizationFromFileName("Qwen3-0.6B-Q4_K_M.gguf"))
        assertEquals("Q8_0", HuggingFace.quantizationFromFileName("mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf"))
        assertEquals("IQ4_XS", HuggingFace.quantizationFromFileName("Llama-3.2-3B-Instruct-IQ4_XS.gguf"))
        assertEquals("Q4_K_XL", HuggingFace.quantizationFromFileName("Qwen3-4B-UD-Q4_K_XL.gguf"))
        assertEquals("F16", HuggingFace.quantizationFromFileName("mmproj-model-f16.gguf"))
        assertEquals("BF16", HuggingFace.quantizationFromFileName("gemma-3-1b-it-BF16.gguf"))
        assertEquals("?", HuggingFace.quantizationFromFileName("model.gguf"))
    }

    @Test
    fun `parameter count is extracted from repo names`() {
        assertEquals("0.6B", HuggingFace.parameterCountFromName("Qwen3-0.6B-GGUF"))
        assertEquals("1B", HuggingFace.parameterCountFromName("Llama-3.2-1B-Instruct-GGUF"))
        assertEquals("4B", HuggingFace.parameterCountFromName("gemma-3-4b-it-GGUF"))
        assertEquals("7B", HuggingFace.parameterCountFromName("Mistral-7B-Instruct-v0.3-GGUF"))
        assertEquals("30B", HuggingFace.parameterCountFromName("Qwen3-Coder-30B-A3B-Instruct-GGUF"))
        assertEquals("135M", HuggingFace.parameterCountFromName("SmolLM2-135M-Instruct-GGUF"))
        assertEquals("8x7B", HuggingFace.parameterCountFromName("Mixtral-8x7B-Instruct-v0.1-GGUF"))
        assertEquals("?", HuggingFace.parameterCountFromName("Phi-4-mini-instruct-GGUF"))
    }

    @Test
    fun `split shards and projectors are detected`() {
        assertTrue(HuggingFace.isSplitShard("Qwen3-235B-Q4_K_M-00001-of-00003.gguf"))
        assertFalse(HuggingFace.isSplitShard("Qwen3-4B-Q4_K_M.gguf"))
        assertTrue(HuggingFace.isProjector("mmproj-model-f16.gguf"))
        assertFalse(HuggingFace.isProjector("gemma-3-4b-it-Q4_K_M.gguf"))
    }

    @Test
    fun `resolve url encodes path segments but keeps slashes`() {
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/main/sub%20dir/model%2Bv2.gguf",
            HuggingFace.resolveUrl("owner/repo", "sub dir/model+v2.gguf"),
        )
    }

    @Test
    fun `local file name is namespaced by repo and sanitized`() {
        assertEquals(
            "unsloth--Qwen3-0.6B-GGUF--Qwen3-0.6B-Q4_K_M.gguf",
            HuggingFace.localFileName("unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
        )
        assertEquals("o--r--we_ird.gguf", HuggingFace.localFileName("o/r", "Q4/we ird.gguf"))
    }

    @Test
    fun `ram estimate rounds up to whole gigabytes with a 2GB floor`() {
        assertEquals(2048, HuggingFace.estimateMinimumRamMb(400L * 1024 * 1024))
        assertEquals(4096, HuggingFace.estimateMinimumRamMb(2_497_280_256L))
        assertEquals(6144, HuggingFace.estimateMinimumRamMb(4_372_812_000L))
    }

    @Test
    fun `is huggingface url only matches hugging face hosts`() {
        assertTrue(HuggingFace.isHuggingFaceUrl("https://huggingface.co/a/b/resolve/main/x.gguf"))
        assertTrue(HuggingFace.isHuggingFaceUrl("https://hf.co/a/b"))
        assertFalse(HuggingFace.isHuggingFaceUrl("https://huggingface.co.evil.com/a/b"))
        assertFalse(HuggingFace.isHuggingFaceUrl("https://cdn-lfs.hf.co/x"))
    }

    // ---- listing and entry construction ----

    private val treeEntries = listOf(
        HfTreeEntry(type = "file", path = "README.md", size = 1000),
        HfTreeEntry(type = "directory", path = "extras"),
        HfTreeEntry(type = "file", path = "gemma-3-4b-it-Q8_0.gguf", size = 135, lfs = HfLfsInfo(4_130_226_336)),
        HfTreeEntry(type = "file", path = "gemma-3-4b-it-Q4_K_M.gguf", size = 135, lfs = HfLfsInfo(2_489_757_856)),
        HfTreeEntry(type = "file", path = "mmproj-model-f16.gguf", size = 851_251_328),
        HfTreeEntry(type = "file", path = "mmproj-model-Q8_0.gguf", size = 591_000_000),
        HfTreeEntry(type = "file", path = "big/gemma-Q4_K_M-00001-of-00002.gguf", size = 10),
        HfTreeEntry(type = "file", path = "big/gemma-Q4_K_M-00002-of-00002.gguf", size = 10),
    )

    @Test
    fun `listing separates models, projectors and split shards`() {
        val listing = HuggingFace.buildListing("ggml-org/gemma-3-4b-it-GGUF", treeEntries)
        assertEquals(listOf("gemma-3-4b-it-Q4_K_M.gguf", "gemma-3-4b-it-Q8_0.gguf"), listing.modelFiles.map { it.path })
        // LFS size wins over the pointer size
        assertEquals(2_489_757_856, listing.modelFiles.first().sizeBytes)
        assertEquals(listOf("mmproj-model-Q8_0.gguf", "mmproj-model-f16.gguf"), listing.projectorFiles.map { it.path })
        assertEquals(2, listing.skippedSplitFiles)
    }

    @Test
    fun `default projector prefers Q8_0 then F16`() {
        val listing = HuggingFace.buildListing("r/x", treeEntries)
        assertEquals("mmproj-model-Q8_0.gguf", HuggingFace.defaultProjector(listing.projectorFiles)?.path)
        assertEquals(
            "mmproj-model-f16.gguf",
            HuggingFace.defaultProjector(listing.projectorFiles.filter { it.quantization != "Q8_0" })?.path,
        )
        assertNull(HuggingFace.defaultProjector(emptyList()))
    }

    @Test
    fun `registry entry is built from model and projector`() {
        val listing = HuggingFace.buildListing("ggml-org/gemma-3-4b-it-GGUF", treeEntries)
        val model = listing.modelFiles.first()
        val projector = listing.projectorFiles.first()

        val entry = HuggingFace.toRegistryEntry(model, projector)

        assertEquals("hf:ggml-org/gemma-3-4b-it-GGUF/gemma-3-4b-it-Q4_K_M.gguf", entry.id)
        assertEquals("gemma-3-4b-it (Vision)", entry.name)
        assertEquals("4B", entry.parameterCount)
        assertEquals("Q4_K_M", entry.quantization)
        assertEquals(
            "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            entry.modelDownloadUrl,
        )
        assertEquals("ggml-org--gemma-3-4b-it-GGUF--gemma-3-4b-it-Q4_K_M.gguf", entry.modelFileName)
        assertEquals("ggml-org--gemma-3-4b-it-GGUF--mmproj-model-Q8_0.gguf", entry.projectorFileName)
        assertEquals(model.sizeBytes + projector.sizeBytes, entry.totalSizeBytes)
        assertTrue(entry.hasProjector)
    }

    @Test
    fun `registry entry without projector is text only`() {
        val listing = HuggingFace.buildListing("ggml-org/gemma-3-4b-it-GGUF", treeEntries, revision = "v1")
        val entry = HuggingFace.toRegistryEntry(listing.modelFiles.first(), null)
        assertEquals("gemma-3-4b-it", entry.name)
        assertFalse(entry.hasProjector)
        assertTrue(entry.modelDownloadUrl.contains("/resolve/v1/"))
    }

    @Test
    fun `gated flag handles boolean and string values`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val models = json.decodeFromString<List<HfModelSummary>>(
            """[{"id":"a/b","gated":false},{"id":"c/d","gated":"manual"},{"id":"e/f"}]"""
        )
        assertEquals(listOf(false, true, false), models.map { it.isGated })
    }
}
