package dev.nutting.pocketllm.data.local.model

object ModelRegistry {

    private const val HF_SMOLVLM = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main"
    private const val HF_QWEN3_VL = "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/main"
    private const val HF_GEMMA3 = "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main"

    val entries: List<ModelRegistryEntry> = listOf(
        ModelRegistryEntry(
            id = "smolvlm2-2.2b-q4km",
            name = "SmolVLM2 2.2B",
            description = "Compact vision-language model. Good balance of speed and accuracy on mid-range devices.",
            parameterCount = "2.2B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_SMOLVLM/SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            projectorDownloadUrl = "$HF_SMOLVLM/mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
            modelFileName = "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            projectorFileName = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
            modelSizeBytes = 1_110_000_000L,
            projectorSizeBytes = 593_000_000L,
            minimumRamMb = 4096,
        ),
        ModelRegistryEntry(
            id = "qwen3-vl-4b-q4km",
            name = "Qwen3-VL 4B",
            description = "Larger vision-language model with improved accuracy. Requires more RAM and storage.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_QWEN3_VL/Qwen3VL-4B-Instruct-Q4_K_M.gguf",
            projectorDownloadUrl = "$HF_QWEN3_VL/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            modelFileName = "Qwen3VL-4B-Instruct-Q4_K_M.gguf",
            projectorFileName = "mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            modelSizeBytes = 2_500_000_000L,
            projectorSizeBytes = 454_000_000L,
            minimumRamMb = 6144,
        ),
        ModelRegistryEntry(
            id = "gemma3-4b-q4km",
            name = "Gemma 3 4B",
            description = "Google's compact vision model. Strong accuracy with moderate resource requirements.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_GEMMA3/gemma-3-4b-it-Q4_K_M.gguf",
            projectorDownloadUrl = "$HF_GEMMA3/mmproj-model-f16.gguf",
            modelFileName = "gemma-3-4b-it-Q4_K_M.gguf",
            projectorFileName = "mmproj-gemma-3-4b-it-f16.gguf",
            modelSizeBytes = 2_490_000_000L,
            projectorSizeBytes = 851_000_000L,
            minimumRamMb = 6144,
        ),
    )
}
