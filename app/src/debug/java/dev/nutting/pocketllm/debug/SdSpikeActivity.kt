package dev.nutting.pocketllm.debug

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.nutting.pocketllm.sd.ISdEngineCallback
import dev.nutting.pocketllm.sd.ISdEngineService
import dev.nutting.pocketllm.sd.SdEngineService
import dev.nutting.pocketllm.sd.SdJobRequest
import dev.nutting.pocketllm.sd.SdModelManifest
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Debug-only M0 spike harness. Start with:
 *   adb shell am start -n dev.nutting.pocketllm/.debug.SdSpikeActivity --es model /path/to/model.gguf
 * Logs each callback and the wall-clock timings; the results are recorded in the spike results doc.
 */
class SdSpikeActivity : Activity() {

    private val json = Json { ignoreUnknownKeys = true }
    private val log = StringBuilder()
    private lateinit var status: TextView
    private var service: ISdEngineService? = null
    private var lastStart = 0L
    private var cancelRequestedAt = 0L
    private var modelPath: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ISdEngineService.Stub.asInterface(binder)
            line("service connected: ${service != null}")
            val path = modelPath ?: return
            val started = SystemClock.elapsedRealtime()
            val ok = runCatching {
                service?.load(json.encodeToString(SdModelManifest.serializer(), SdModelManifest(path)))
            }.getOrElse { e ->
                line("load threw: ${e.message}")
                false
            }
            line("load=${ok} in ${SystemClock.elapsedRealtime() - started} ms")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            line("service disconnected")
        }
    }

    private val callback = object : ISdEngineCallback.Stub() {
        override fun onLoaded(ok: Boolean, error: String?) = line("onLoaded ok=$ok error=$error")

        override fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int) {
            line("progress $step/$totalSteps (+${SystemClock.elapsedRealtime() - lastStart} ms)")
        }

        override fun onPreview(path: String) = line("preview $path")

        override fun onFinished(imagePaths: Array<out String>?, error: String?) {
            val took = SystemClock.elapsedRealtime() - lastStart
            val cancelTook = if (cancelRequestedAt > 0) SystemClock.elapsedRealtime() - cancelRequestedAt else -1
            line("finished in ${took} ms; cancel latency=${cancelTook} ms; error=$error; images=${imagePaths?.size ?: 0}")
            imagePaths?.forEach { line("  $it") }
            cancelRequestedAt = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelPath = intent.getStringExtra("model")
        status = TextView(this).apply { textSize = 11f }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addButton("256/8") { run(256, 8) }
            addButton("512/20") { run(512, 20) }
            addButton("1024/4") { run(1024, 4) }
            addButton("Cancel") {
                cancelRequestedAt = SystemClock.elapsedRealtime()
                runCatching { service?.cancel("spike") }
                line("cancel requested")
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(status)
        }
        setContentView(root)
        line("model=$modelPath")
        bindService(Intent(this, SdEngineService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun run(width: Int, steps: Int) {
        val svc = service ?: return line("not connected")
        val path = modelPath ?: return line("no --es model")
        val request = SdJobRequest(
            modelPath = path, prompt = "a red cube on a wooden table", width = width, height = width,
            steps = steps, cfg = 7f, seed = 42, count = 1,
            outDir = File(cacheDir, "sd-spike/$width-$steps").apply { mkdirs() }.absolutePath,
            sampler = "euler",
        )
        lastStart = SystemClock.elapsedRealtime()
        line("submit ${width}x$width steps=$steps")
        runCatching { svc.submit("spike", json.encodeToString(SdJobRequest.serializer(), request), callback) }
            .onFailure { line("submit threw: ${it.message}") }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun LinearLayout.addButton(label: String, action: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun line(text: String) {
        Log.i("SdSpike", text)
        log.append(text).append('\n')
        runOnUiThread { status.text = log.toString() }
    }
}
