package dev.nutting.pocketllm.data.local.model

object ModelRegistry {
    val entries: List<ModelRegistryEntry> = listOf(
        ModelRegistryEntry(
            id = "smollm2-360m-q8",
            name = "SmolLM2 360M",
            description = "Very small and fast model. Good for quick testing on any device.",
            parameterCount = "360M",
            quantization = "Q8_0",
            modelDownloadUrl = "https://huggingface.co/ggml-org/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
            modelFileName = "SmolLM2-360M-Instruct-Q8_0.gguf",
            modelSizeBytes = 386_000_000L,
            minimumRamMb = 2048,
        ),
        ModelRegistryEntry(
            id = "smollm2-1.7b-q4km",
            name = "SmolLM2 1.7B",
            description = "Compact text model. Good balance of speed and quality for most devices.",
            parameterCount = "1.7B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "https://huggingface.co/ggml-org/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            modelFileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 1_060_000_000L,
            minimumRamMb = 3072,
        ),
        ModelRegistryEntry(
            id = "qwen3-4b-q4km",
            name = "Qwen3 4B",
            description = "Strong multilingual chat model. Good reasoning and instruction following.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "https://huggingface.co/ggml-org/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            modelFileName = "Qwen3-4B-Q4_K_M.gguf",
            modelSizeBytes = 2_700_000_000L,
            minimumRamMb = 6144,
        ),
        ModelRegistryEntry(
            id = "gemma3-4b-q4km",
            name = "Gemma 3 4B",
            description = "Google's compact chat model. Excellent instruction following.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "https://huggingface.co/ggml-org/gemma-3-4B-it-GGUF/resolve/main/gemma-3-4B-it-Q4_K_M.gguf",
            modelFileName = "gemma-3-4B-it-Q4_K_M.gguf",
            modelSizeBytes = 2_800_000_000L,
            minimumRamMb = 6144,
        ),
    )
}
