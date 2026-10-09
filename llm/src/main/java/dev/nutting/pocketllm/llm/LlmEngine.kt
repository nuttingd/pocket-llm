package dev.nutting.pocketllm.llm

import android.os.Build
import android.util.Log
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlmEngine {

    sealed class State {
        data object Unloaded : State()
        data object Loading : State()
        data object Ready : State()
        data object Inferring : State()
        data class Error(val message: String) : State()
    }

    data class InferenceProgress(val phase: String, val tokensGenerated: Int, val tokenText: String = "")

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    // Unbounded: progress carries streamed token text, and tryEmit on a full buffer would drop tokens
    private val _progress = MutableSharedFlow<InferenceProgress>(extraBufferCapacity = Int.MAX_VALUE)
    val progress: SharedFlow<InferenceProgress> = _progress.asSharedFlow()

    private val _status = MutableStateFlow<InferenceStatus?>(null)

    /** What the engine is doing during a load or inference; null when idle. */
    val status: StateFlow<InferenceStatus?> = _status.asStateFlow()

    var deviceInfo: String = ""
        private set

    fun isReady(): Boolean = _state.value is State.Ready

    @Suppress("unused") // Called from JNI
    fun onNativeProgress(phase: String, tokens: Int, tokenText: String) {
        if (tokenText.isNotEmpty()) tokenListener?.invoke(tokenText)
        _progress.tryEmit(InferenceProgress(phase, tokens, tokenText))
        InferenceStatus.fromNativePhase(phase, tokens)?.let { _status.value = it }
    }

    // Serializes inferences so each request's token listener only sees its own tokens
    private val inferMutex = Mutex()
    @Volatile private var tokenListener: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "LlmEngine"

        /** Placeholder for an image in message content; must match mtmd_default_marker() in llama.cpp. */
        const val MEDIA_MARKER = "<__media__>"

        init {
            System.loadLibrary("pocketllm-llm")
        }
    }

    fun init(apkPath: String) {
        val abi = Build.SUPPORTED_ABIS.first()
        val libPrefix = "lib/$abi/"
        val backendNames = ZipFile(apkPath).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith(libPrefix) && it.endsWith(".so") }
                .map { it.removePrefix(libPrefix) }
                .filter { it.startsWith("libggml-") }
                .toList()
                .toTypedArray()
        }
        Log.i(TAG, "Found ${backendNames.size} ggml backend libs in APK for abi $abi: ${backendNames.joinToString()}")
        nativeInit(backendNames)
        deviceInfo = nativeDeviceInfo()
        Log.i(TAG, "Devices: $deviceInfo")
    }

    suspend fun loadModel(modelPath: String, projectorPath: String = "", nThreads: Int = 0, gpuOffloadPercent: Int = 100, contextSize: Int = 2048) {
        // If a previous inference crashed, clean up the poisoned state first
        if (_state.value is State.Error) {
            Log.w(TAG, "Loading model from error state — unloading first")
            unload()
        }

        _state.value = State.Loading
        _status.value = InferenceStatus.LoadingModel
        val loadStart = System.currentTimeMillis()
        try {
            val result = withContext(Dispatchers.Default) {
                nativeLoadModel(modelPath, projectorPath, nThreads, gpuOffloadPercent, contextSize)
            }
            if (result == 0) {
                deviceInfo = nativeDeviceInfo()
                Log.i(TAG, "Devices: $deviceInfo")
                _state.value = State.Ready
                Log.i(TAG, "Model loaded in ${System.currentTimeMillis() - loadStart} ms")
            } else {
                val msg = when (result) {
                    -1 -> "Native state corrupted — please restart the app"
                    1 -> "Failed to load model"
                    2 -> "Failed to create context"
                    3 -> "Failed to load vision projector"
                    else -> "Unknown load error: $result"
                }
                _state.value = State.Error(msg)
                Log.e(TAG, msg)
            }
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Load failed")
            Log.e(TAG, "Exception during model load", e)
        } finally {
            _status.value = null
        }
    }

    /**
     * Run multi-turn chat inference.
     * @param messagesJson JSON array of {role, content} chat messages.
     * @param images Encoded images (JPEG/PNG bytes), one per [MEDIA_MARKER] in the message contents, in order.
     *   Requires a model loaded with a projector.
     * @param maxTokens Maximum tokens to generate.
     * @param temperature Sampling temperature.
     * @param topP Nucleus sampling top-p.
     * @param topK Top-k sampling.
     * @param minP Min-p sampling.
     * @param repeatPenalty Repetition penalty.
     * @param onToken Called on the inference thread with each piece of generated text, in order.
     * @return The generated assistant response text.
     */
    suspend fun inferChat(
        messagesJson: String,
        images: List<ByteArray> = emptyList(),
        maxTokens: Int = 2048,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 40,
        minP: Float = 0.05f,
        repeatPenalty: Float = 1.1f,
        onToken: ((String) -> Unit)? = null,
    ): String = inferMutex.withLock {
        tokenListener = onToken
        _state.value = State.Inferring
        try {
            val result = withContext(Dispatchers.Default) {
                nativeInferChat(messagesJson, images.toTypedArray(), maxTokens, temperature, topP, topK, minP, repeatPenalty)
            }
            _progress.tryEmit(InferenceProgress("complete", 0, nativePerfInfo()))
            _state.value = State.Ready
            result
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Inference failed")
            Log.e(TAG, "Exception during chat inference", e)
            throw e
        } finally {
            tokenListener = null
            _status.value = null
        }
    }

    fun cancel() {
        nativeCancel()
    }

    fun unload() {
        nativeUnload()
        _state.value = State.Unloaded
        Log.i(TAG, "Model unloaded")
    }

    /** Return the model name embedded in the GGUF metadata, or null. */
    fun modelName(): String? {
        val name = nativeModelName()
        return name.takeIf { it.isNotBlank() }
    }

    // Native methods
    external fun nativeSystemInfo(): String
    external fun nativeDeviceInfo(): String
    external fun nativePerfInfo(): String
    external fun nativeModelName(): String
    private external fun nativeInit(backendPaths: Array<String>)
    private external fun nativeLoadModel(modelPath: String, projectorPath: String, nThreads: Int, gpuOffloadPercent: Int, contextSize: Int): Int
    private external fun nativeInferChat(messagesJson: String, images: Array<ByteArray>, maxTokens: Int, temperature: Float, topP: Float, topK: Int, minP: Float, repeatPenalty: Float): String
    private external fun nativeCancel()
    private external fun nativeUnload()
}
