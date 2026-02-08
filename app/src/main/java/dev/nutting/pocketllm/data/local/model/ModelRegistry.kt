package dev.nutting.pocketllm.data.local.model

object ModelRegistry {

    // Text-only model sources
    private const val HF_QWEN3_06B = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main"
    private const val HF_QWEN3_17B = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main"
    private const val HF_QWEN3_4B = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main"
    private const val HF_LLAMA32_1B = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main"
    private const val HF_LLAMA32_3B = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main"
    private const val HF_GEMMA3_1B = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main"
    private const val HF_SMOLLM2 = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main"
    private const val HF_PHI4_MINI = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main"
    private const val HF_MISTRAL_7B = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main"

    // Vision model sources
    private const val HF_SMOLVLM = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main"
    private const val HF_QWEN3_VL = "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/main"
    private const val HF_GEMMA3_4B_VL = "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main"

    val entries: List<ModelRegistryEntry> = listOf(
        // ---- Text-only models ----
        ModelRegistryEntry(
            id = "qwen3-0.6b-q4km",
            name = "Qwen3 0.6B",
            description = "Ultra-lightweight model. Fast responses on any device, great for simple tasks.",
            parameterCount = "0.6B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_QWEN3_06B/Qwen3-0.6B-Q4_K_M.gguf",
            modelFileName = "Qwen3-0.6B-Q4_K_M.gguf",
            modelSizeBytes = 396_705_472L,
            minimumRamMb = 2048,
        ),
        ModelRegistryEntry(
            id = "gemma3-1b-q4km",
            name = "Gemma 3 1B",
            description = "Google's compact model. Good quality for its size, efficient on mobile.",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_GEMMA3_1B/gemma-3-1b-it-Q4_K_M.gguf",
            modelFileName = "gemma-3-1b-it-Q4_K_M.gguf",
            modelSizeBytes = 806_058_272L,
            minimumRamMb = 2048,
        ),
        ModelRegistryEntry(
            id = "llama32-1b-q4km",
            name = "Llama 3.2 1B",
            description = "Meta's smallest Llama. Fast and capable for basic conversations.",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_LLAMA32_1B/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            modelFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 807_694_464L,
            minimumRamMb = 2048,
        ),
        ModelRegistryEntry(
            id = "smollm2-1.7b-q4km",
            name = "SmolLM2 1.7B",
            description = "HuggingFace's efficient small model. Good reasoning for its compact size.",
            parameterCount = "1.7B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_SMOLLM2/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            modelFileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 1_055_609_824L,
            minimumRamMb = 3072,
        ),
        ModelRegistryEntry(
            id = "qwen3-1.7b-q4km",
            name = "Qwen3 1.7B",
            description = "Alibaba's efficient model. Strong multilingual and reasoning capabilities.",
            parameterCount = "1.7B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_QWEN3_17B/Qwen3-1.7B-Q4_K_M.gguf",
            modelFileName = "Qwen3-1.7B-Q4_K_M.gguf",
            modelSizeBytes = 1_107_409_472L,
            minimumRamMb = 3072,
        ),
        ModelRegistryEntry(
            id = "llama32-3b-q4km",
            name = "Llama 3.2 3B",
            description = "Great balance of speed and quality. Strong general-purpose capabilities.",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_LLAMA32_3B/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            modelFileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 2_019_377_696L,
            minimumRamMb = 4096,
        ),
        ModelRegistryEntry(
            id = "phi4-mini-q4km",
            name = "Phi-4 Mini 3.8B",
            description = "Microsoft's compact powerhouse. Excellent reasoning and coding ability.",
            parameterCount = "3.8B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_PHI4_MINI/Phi-4-mini-instruct-Q4_K_M.gguf",
            modelFileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
            modelSizeBytes = 2_491_874_272L,
            minimumRamMb = 4096,
        ),
        ModelRegistryEntry(
            id = "qwen3-4b-q4km",
            name = "Qwen3 4B",
            description = "Alibaba's best compact model. Top-tier multilingual and reasoning.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_QWEN3_4B/Qwen3-4B-Q4_K_M.gguf",
            modelFileName = "Qwen3-4B-Q4_K_M.gguf",
            modelSizeBytes = 2_497_280_256L,
            minimumRamMb = 4096,
        ),
        ModelRegistryEntry(
            id = "mistral-7b-q4km",
            name = "Mistral 7B",
            description = "Powerful 7B model. Best quality but requires a high-end device.",
            parameterCount = "7B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_MISTRAL_7B/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            modelFileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            modelSizeBytes = 4_372_812_000L,
            minimumRamMb = 6144,
        ),
        // ---- Vision models (include projector) ----
        ModelRegistryEntry(
            id = "smolvlm2-2.2b-q4km",
            name = "SmolVLM2 2.2B (Vision)",
            description = "Compact vision-language model. Understands images and text on mid-range devices.",
            parameterCount = "2.2B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_SMOLVLM/SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            modelFileName = "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 1_112_602_656L,
            projectorDownloadUrl = "$HF_SMOLVLM/mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
            projectorFileName = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
            projectorSizeBytes = 592_523_200L,
            minimumRamMb = 4096,
        ),
        ModelRegistryEntry(
            id = "qwen3-vl-4b-q4km",
            name = "Qwen3-VL 4B (Vision)",
            description = "Vision-language model with strong image understanding. Requires more RAM.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_QWEN3_VL/Qwen3VL-4B-Instruct-Q4_K_M.gguf",
            modelFileName = "Qwen3VL-4B-Instruct-Q4_K_M.gguf",
            modelSizeBytes = 2_497_281_664L,
            projectorDownloadUrl = "$HF_QWEN3_VL/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            projectorFileName = "mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf",
            projectorSizeBytes = 453_974_304L,
            minimumRamMb = 6144,
        ),
        ModelRegistryEntry(
            id = "gemma3-4b-vl-q4km",
            name = "Gemma 3 4B (Vision)",
            description = "Google's vision model. Strong image + text accuracy with moderate resources.",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            modelDownloadUrl = "$HF_GEMMA3_4B_VL/gemma-3-4b-it-Q4_K_M.gguf",
            modelFileName = "gemma-3-4b-it-Q4_K_M.gguf",
            modelSizeBytes = 2_489_894_016L,
            projectorDownloadUrl = "$HF_GEMMA3_4B_VL/mmproj-model-f16.gguf",
            projectorFileName = "mmproj-gemma-3-4b-it-f16.gguf",
            projectorSizeBytes = 851_251_328L,
            minimumRamMb = 6144,
        ),
    )
}
