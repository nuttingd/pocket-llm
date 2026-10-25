package dev.nutting.pocketllm.sd

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Progress sink implemented by the service and handed to native code. */
interface SdProgressListener {
    fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int)
}

/** JNI entry points; see sd_jni.cpp. */
internal object SdNative {
    init { System.loadLibrary("pocketsd") }
    external fun version(): String
    external fun init(callback: SdProgressListener?)
    external fun loadModel(path: String, threads: Int, backend: String?): Boolean
    external fun unload()
    external fun cancel()
    external fun samplerIndex(name: String): Int
    external fun generate(
        prompt: String, negativePrompt: String?, width: Int, height: Int,
        steps: Int, cfg: Float, seed: Long, count: Int, samplerIndex: Int,
    ): Array<ByteArray>?
}

/**
 * One loaded diffusion model. Not thread-safe: the service serializes all calls.
 * Images come back from JNI as raw pixels and are encoded to PNG here, so the
 * native side needs no image-writing dependency.
 */
class SdEngine(private val listener: SdProgressListener? = null) {

    @Volatile private var loaded = false

    fun load(modelPath: String, threads: Int = 4, backend: String? = "vulkan0"): Boolean {
        SdNative.init(listener)
        loaded = SdNative.loadModel(modelPath, threads, backend)
        if (!loaded && backend != null) {
            // No Vulkan device (emulator, unusual driver): retry on the default backend.
            loaded = SdNative.loadModel(modelPath, threads, null)
        }
        return loaded
    }

    fun unload() {
        if (loaded) SdNative.unload()
        loaded = false
    }

    fun cancel() = SdNative.cancel()

    fun version(): String = SdNative.version()

    /** Maps a sampler name to the pinned enum value; unknown names fall back to euler. */
    fun samplerIndex(name: String?): Int = if (name.isNullOrBlank()) 0 else SdNative.samplerIndex(name)

    /** Writes PNGs into [outDir] and returns their paths; empty when cancelled. */
    fun generate(
        prompt: String,
        negativePrompt: String?,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        count: Int,
        outDir: File,
        samplerIndex: Int = 0,
        batchIndex: Int = 0,
    ): List<File> {
        require(loaded) { "No model loaded" }
        outDir.mkdirs()
        val raw = SdNative.generate(prompt, negativePrompt, width, height, steps, cfg, seed, count, samplerIndex)
            ?: return emptyList()
        return raw.mapIndexedNotNull { index, bytes -> encodePng(bytes, File(outDir, "gen_${batchIndex}_$index.png")) }
    }

    private fun encodePng(raw: ByteArray, out: File): File? {
        val header = parseHeader(raw) ?: return null
        val width = header.width
        val height = header.height
        val channels = header.channels

        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val o = 16 + i * channels
            val r = raw[o].toInt() and 0xFF
            val g = raw[o + 1].toInt() and 0xFF
            val b = raw[o + 2].toInt() and 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out
    }
}

internal data class RawImageHeader(val width: Int, val height: Int, val channels: Int)

/** Parses the 16-byte big-endian header produced by sd_jni.cpp; null for anything malformed. */
internal fun parseHeader(raw: ByteArray): RawImageHeader? {
    if (raw.size < 16) return null
    val header = ByteBuffer.wrap(raw, 0, 16).order(ByteOrder.BIG_ENDIAN)
    val width = header.int
    val height = header.int
    val channels = header.int
    if (width <= 0 || height <= 0 || channels !in 3..4) return null
    if (raw.size - 16 < width.toLong() * height * channels) return null
    return RawImageHeader(width, height, channels)
}
