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

    private val _progress = MutableSharedFlow<InferenceProgress>(extraBufferCapacity = 16)
    val progress: SharedFlow<InferenceProgress> = _progress.asSharedFlow()

    var deviceInfo: String = ""
        private set

    fun isReady(): Boolean = _state.value is State.Ready

    @Suppress("unused") // Called from JNI
    fun onNativeProgress(phase: String, tokens: Int, tokenText: String) {
        _progress.tryEmit(InferenceProgress(phase, tokens, tokenText))
    }

    companion object {
        private const val TAG = "LlmEngine"

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

    suspend fun loadModel(
        modelPath: String,
        projectorPath: String,
        nThreads: Int = 0,
        gpuOffloadPercent: Int = 100,
        contextSize: Int = 2048,
    ) {
        // If a previous inference crashed, clean up the poisoned state first
        if (_state.value is State.Error) {
            Log.w(TAG, "Loading model from error state — unloading first")
            unload()
        }

        _state.value = State.Loading
        try {
            val result = withContext(Dispatchers.Default) {
                nativeLoadModel(modelPath, projectorPath, nThreads, gpuOffloadPercent, contextSize)
            }
            if (result == 0) {
                deviceInfo = nativeDeviceInfo()
                Log.i(TAG, "Devices: $deviceInfo")
                _state.value = State.Ready
                Log.i(TAG, "Model loaded successfully")
            } else {
                val msg = when (result) {
                    -1 -> "Native state corrupted — please restart the app"
                    1 -> "Failed to load text model"
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
        }
    }

    suspend fun infer(imageBytes: ByteArray, prompt: String, maxTokens: Int = 2048): String {
        _state.value = State.Inferring
        return try {
            val result = withContext(Dispatchers.Default) {
                nativeInfer(imageBytes, prompt, maxTokens)
            }
            _progress.tryEmit(InferenceProgress("complete", 0, nativePerfInfo()))
            _state.value = State.Ready
            result
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Inference failed")
            Log.e(TAG, "Exception during inference", e)
            throw e
        }
    }

    suspend fun inferText(prompt: String, maxTokens: Int = 2048): String {
        _state.value = State.Inferring
        return try {
            val result = withContext(Dispatchers.Default) {
                nativeInferText(prompt, maxTokens)
            }
            _progress.tryEmit(InferenceProgress("complete", 0, nativePerfInfo()))
            _state.value = State.Ready
            result
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Inference failed")
            Log.e(TAG, "Exception during text inference", e)
            throw e
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

    // Native methods
    external fun nativeSystemInfo(): String
    external fun nativeDeviceInfo(): String
    external fun nativePerfInfo(): String
    private external fun nativeInit(backendPaths: Array<String>)
    private external fun nativeLoadModel(modelPath: String, projectorPath: String, nThreads: Int, gpuOffloadPercent: Int, contextSize: Int): Int
    private external fun nativeInfer(imageBytes: ByteArray, prompt: String, maxTokens: Int): String
    private external fun nativeInferText(prompt: String, maxTokens: Int): String
    private external fun nativeCancel()
    private external fun nativeUnload()
}
