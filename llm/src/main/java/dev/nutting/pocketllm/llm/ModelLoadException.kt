package dev.nutting.pocketllm.llm

/** A model failed to load; [message] is suitable for showing to the user. */
class ModelLoadException(message: String) : Exception(message) {
    companion object {
        /**
         * Builds a user-facing message from nativeLoadModel's return [code] and the first error llama.cpp
         * logged during the load ([detail], may be blank).
         */
        fun describe(code: Int, detail: String, contextSize: Int): String {
            val base = when (code) {
                -1 -> "The on-device engine crashed earlier. Restart the app to load a model."
                1 -> if (detail.contains("unknown model architecture", ignoreCase = true)) {
                    "This model's architecture isn't supported by the app's version of llama.cpp."
                } else {
                    "Couldn't load the model file."
                }
                2 -> "Not enough memory to run the model with a $contextSize-token context."
                3 -> "Couldn't load the vision projector. It may not match this model."
                else -> "Model failed to load (code $code)."
            }
            val reason = detail.trim().removePrefix("llama_model_load: ").trim()
            return if (reason.isEmpty()) base else "$base\n$reason"
        }
    }
}
