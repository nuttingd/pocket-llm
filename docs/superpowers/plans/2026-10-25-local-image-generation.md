# Local Image Generation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate images on the phone with SD 1.5 / SDXL(-Turbo) GGUF models, with no server, reusing the existing image job pipeline.

**Architecture:** A new `sd/` Android library embeds stable-diffusion.cpp over JNI. A foreground `SdEngineService` in a separate `:sd` process owns the engine and runs one job at a time over a small AIDL contract. The main process talks to it through a transport-agnostic `ImageGenerator` seam that also wraps the existing remote `sd-server` client. Local models are manifest-shaped (`LocalImageModel`) and downloaded through the existing Hugging Face browser/download manager.

**Tech Stack:** Kotlin 2.2.10, JDK 21, Android NDK (arm64-v8a), CMake, stable-diffusion.cpp + ggml (Vulkan), Room, DataStore, WorkManager (downloads only), Ktor (remote adapter unchanged), Jetpack Compose.

**Spec:** `docs/superpowers/specs/2026-10-25-local-image-generation-design.md`

---

## Gate: M0 must pass before M1

M0 is a spike, not production work. If any of these fail, stop and report before continuing:

1. `libpocketsd.so` builds for `arm64-v8a` with Vulkan.
2. A 256×256 SD1.5 generation runs from `:sd` with step callbacks.
3. Cancel stops generation within ~2 seconds.
4. Killing `:sd` leaves no stuck job; the next job works.
5. Timings recorded for SD1.5 512² and SDXL-Turbo 1024² on the Pixel 9 Pro.

If the spike fails only on performance, continue with M1 but revisit the 8 GB floor and defaults.

---

## File map

**New module `sd/`**
- `sd/build.gradle.kts` — library, NDK, Vulkan
- `sd/src/main/cpp/CMakeLists.txt` — sd.cpp + JNI target
- `sd/src/main/cpp/sd_jni.cpp` — JNI bridge (our stable boundary)
- `sd/src/main/AndroidManifest.xml` — declares `SdEngineService`
- `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngine.kt` — Kotlin wrapper over JNI
- `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngineService.kt` — `:sd` foreground service
- `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngineBinder.kt` — binder implementation
- `sd/src/main/aidl/dev/nutting/pocketllm/sd/ISdEngineService.aidl`
- `sd/src/main/aidl/dev/nutting/pocketllm/sd/ISdEngineCallback.aidl`

**Main app — new**
- `app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModel.kt`
- `app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModelStore.kt`
- `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageGenerator.kt` (contract + request/event types)
- `app/src/main/java/dev/nutting/pocketllm/domain/image/RemoteImageGenerator.kt`
- `app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageGenerator.kt`
- `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageTarget.kt` (provider resolution)
- `app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageScheduler.kt`
- `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelManagementViewModel.kt`
- `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelDownloader.kt`
- `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelSection.kt` (Compose)
- `sd/src/main/java/dev/nutting/pocketllm/sd/SdJobRequest.kt` (`SdModelManifest` also lives here)

**Main app — modified**
- `settings.gradle.kts` — `include(":sd")`
- `app/build.gradle.kts` — `implementation(project(":sd"))`
- `app/src/main/java/dev/nutting/pocketllm/data/local/entity/ImageJobEntity.kt` — `ImageDialect.LOCAL_SD`
- `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt` — local path
- `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobLauncher.kt` — target resolution
- `app/src/main/java/dev/nutting/pocketllm/AppContainer.kt` — wiring
- `app/src/main/java/dev/nutting/pocketllm/PocketLlmApplication.kt` — skip container init in `:sd`; local-job resume routing
- `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt` + `ChatScreen.kt` — "This device"
- `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/*` — image models section
- `app/src/main/java/dev/nutting/pocketllm/data/remote/huggingface/HuggingFaceClient.kt` — image scope
- `app/src/main/java/dev/nutting/pocketllm/data/repository/SettingsRepository.kt` — remember the device image model
- `app/src/main/java/dev/nutting/pocketllm/domain/image/ImagePreviewStore.kt` — `adopt()` a service-written preview

---

## Phase A — M0 spike

### Task 1: Add the sd.cpp submodule and `sd` module skeleton

**Files:**
- Create: `sd/build.gradle.kts`
- Create: `sd/src/main/cpp/CMakeLists.txt`
- Create: `sd/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`
- Create: `.gitmodules` entry via `git submodule add`

- [ ] **Step 1: Add and pin the submodule**

```bash
cd /Users/davidnutting/dev/nuttingd/pocket-llm
git submodule add https://github.com/leejet/stable-diffusion.cpp.git external/stable-diffusion.cpp
git -C external/stable-diffusion.cpp checkout "$(git -C external/stable-diffusion.cpp describe --tags --abbrev=0)"
git -C external/stable-diffusion.cpp rev-parse HEAD   # record this in the commit message
```

Expected: `external/stable-diffusion.cpp` shows a tag; `.gitmodules` gains both submodules.

- [ ] **Step 2: Verify the sd.cpp CMake option and target names**

```bash
grep -n "option(SD_\|add_library(" external/stable-diffusion.cpp/CMakeLists.txt | head -30
```

Expected: `option(SD_BUILD_SHARED ...)`, `option(SD_BUILD_EXAMPLES ...)`, and a library target such as `stable-diffusion`. Note the exact names for the next step.

- [ ] **Step 3: Create `sd/src/main/cpp/CMakeLists.txt`**

Use the option and target names confirmed in Step 2 (the values below are the upstream defaults at the time of writing; adjust only if Step 2 differs).

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("pocketsd" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Build the library only: no examples, no server, no CLI
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED   ON  CACHE BOOL "" FORCE)
set(GGML_VULKAN       ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       ON  CACHE BOOL "" FORCE)

set(SD_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../external/stable-diffusion.cpp)
add_subdirectory(${SD_SRC} ${CMAKE_CURRENT_BINARY_DIR}/sd_cpp)

add_library(pocketsd SHARED sd_jni.cpp)

# Hide everything except the JNI entry points so this .so cannot clash with libpocketllm.so
set_target_properties(pocketsd PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_compile_options(pocketsd PRIVATE -fvisibility=hidden)

target_link_libraries(pocketsd PRIVATE stable-diffusion android log)
```

- [ ] **Step 4: Create `sd/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.nutting.pocketllm.sd"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                cppFlags += "-isystem /opt/homebrew/include"
                cppFlags += "-isystem /opt/vulkan-headers"
            }
        }
    }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { aidl = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

- [ ] **Step 5: Create `sd/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <service
            android:name="dev.nutting.pocketllm.sd.SdEngineService"
            android:exported="false"
            android:process=":sd"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

- [ ] **Step 6: Register the module**

In `settings.gradle.kts`, after `include(":llm")`:

```kotlin
include(":sd")
```

In `app/build.gradle.kts` dependencies:

```kotlin
implementation(project(":sd"))
```

- [ ] **Step 7: Build the empty JNI library**

Create `sd/src/main/cpp/sd_jni.cpp` with a single exported probe first:

```cpp
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_version(JNIEnv* env, jobject) {
    return env->NewStringUTF("pocketsd");
}
```

```bash
./gradlew :sd:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; `sd/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libpocketsd.so` exists.

- [ ] **Step 8: Commit**

```bash
git add .gitmodules external/stable-diffusion.cpp sd settings.gradle.kts app/build.gradle.kts
git commit -m "build(sd): add stable-diffusion.cpp submodule and sd module skeleton"
```

---

### Task 2: JNI engine — load, generate, progress, cancel

**Files:**
- Modify: `sd/src/main/cpp/sd_jni.cpp`
- Create: `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngine.kt`
- Create: `sd/src/test/java/dev/nutting/pocketllm/sd/SdEngineRequestTest.kt`

Our JNI boundary is deliberately narrower than sd.cpp's evolving C API, so upstream changes stay inside this file.

- [ ] **Step 1: Read the pinned header and note the exact call**

```bash
grep -n "new_sd_ctx\|generate_image\|sd_set_progress_callback\|free_sd_ctx" external/stable-diffusion.cpp/stable-diffusion.h | head -20
```

Expected: the `generate_image(...)` signature for this tag. The code below targets the current upstream shape; adapt argument order/extra flags to the pinned tag — but do not change the Kotlin-facing methods in `SdEngine.kt`.

- [ ] **Step 2: Implement `sd_jni.cpp`**

```cpp
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "stable-diffusion.h"

#define LOG_TAG "pocketsd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct State {
    sd_ctx_t* ctx = nullptr;
    std::atomic<bool> cancel{false};
    jobject callback = nullptr;   // global ref to ISdEngineCallback
    JavaVM* vm = nullptr;
};
State g_state;

void progress_cb(int step, int steps, float /*time*/, void* /*data*/) {
    if (g_state.cancel.load()) return;
    JNIEnv* env = nullptr;
    if (g_state.vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return;
    if (!g_state.callback) return;
    jclass cls = env->GetObjectClass(g_state.callback);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(III)V");
    env->CallVoidMethod(g_state.callback, mid, (jint)step, (jint)steps, (jint)0);
    env->DeleteLocalRef(cls);
}

bool load_model(JNIEnv* env, const char* path, int threads, int gpu_layers) {
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads;
    // Vulkan offload is controlled by ggml at build time; keep the field only if this tag has it
    // p.vulkan_device = 0;
    g_state.ctx = new_sd_ctx(&p);
    return g_state.ctx != nullptr;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_init(JNIEnv* env, jobject, jobject callback) {
    env->GetJavaVM(&g_state.vm);
    if (g_state.callback) env->DeleteGlobalRef(g_state.callback);
    g_state.callback = callback ? env->NewGlobalRef(callback) : nullptr;
    sd_set_progress_callback(progress_cb, nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_loadModel(JNIEnv* env, jobject, jstring path, jint threads, jint gpuLayers) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    bool ok = load_model(env, p, threads, gpuLayers);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_unload(JNIEnv* /*env*/, jobject) {
    if (g_state.ctx) { free_sd_ctx(g_state.ctx); g_state.ctx = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_cancel(JNIEnv* /*env*/, jobject) {
    g_state.cancel.store(true);
}

/**
 * Returns a newline-separated list of written PNG paths. Empty when cancelled or on failure.
 * Params order is fixed by SdEngine.generate(...) and must not change without updating both sides.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_generate(
        JNIEnv* env, jobject, jstring prompt, jstring negative, jint width, jint height,
        jint steps, jfloat cfg, jlong seed, jint count, jstring outDir, jint batchIndex) {
    g_state.cancel.store(false);
    if (!g_state.ctx) return env->NewStringUTF("");

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    const char* negativeChars = negative ? env->GetStringUTFChars(negative, nullptr) : nullptr;
    const char* outDirChars = env->GetStringUTFChars(outDir, nullptr);

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = promptChars;
    params.negative_prompt = negativeChars;
    params.width = width;
    params.height = height;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfg;
    params.seed = seed;
    params.batch_count = count;

    sd_image_t* images = generate_image(g_state.ctx, &params);

    std::string result;
    if (images && !g_state.cancel.load()) {
        for (int i = 0; i < count; i++) {
            if (!images[i].data) continue;
            char file[1024];
            snprintf(file, sizeof(file), "%s/gen_%d_%d.png", outDirChars, batchIndex, i);
            // sd.cpp provides stbi_write_png via its build; if unavailable, call stbi_write_png directly.
            stbi_write_png(file, (int)images[i].width, (int)images[i].height, (int)images[i].channel,
                           images[i].data, (int)images[i].width * (int)images[i].channel);
            result += file;
            result += "\n";
        }
    }
    if (images) free(images);

    env->ReleaseStringUTFChars(prompt, promptChars);
    if (negativeChars) env->ReleaseStringUTFChars(negative, negativeChars);
    env->ReleaseStringUTFChars(outDir, outDirChars);
    return env->NewStringUTF(result.c_str());
}
```

If the pinned tag has no `sd_ctx_params_t`/`sd_img_gen_params_t` (older header), use the positional `new_sd_ctx(...)`/`generate_image(...)` signature from Step 1; the Kotlin API below stays the same.

- [ ] **Step 3: Create `SdEngine.kt`**

```kotlin
package dev.nutting.pocketllm.sd

import java.io.File

/** Progress sink implemented by the service and handed to native code. */
interface SdProgressListener {
    fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int)
}

/** JNI entry points; see sd_jni.cpp. */
internal object SdNative {
    init { System.loadLibrary("pocketsd") }
    external fun init(callback: SdProgressListener?)
    external fun loadModel(path: String, threads: Int, gpuLayers: Int): Boolean
    external fun unload()
    external fun cancel()
    external fun generate(
        prompt: String, negativePrompt: String?, width: Int, height: Int,
        steps: Int, cfg: Float, seed: Long, count: Int, outDir: String, batchIndex: Int,
    ): String
}

/** One loaded diffusion model. Not thread-safe: the service serializes all calls. */
class SdEngine(private val listener: SdProgressListener? = null) {

    @Volatile private var loaded = false

    fun load(modelPath: String, threads: Int = 4, gpuLayers: Int = 99): Boolean {
        SdNative.init(listener)
        loaded = SdNative.loadModel(modelPath, threads, gpuLayers)
        return loaded
    }

    fun unload() {
        if (loaded) SdNative.unload()
        loaded = false
    }

    fun cancel() = SdNative.cancel()

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
        batchIndex: Int = 0,
    ): List<File> {
        require(loaded) { "No model loaded" }
        outDir.mkdirs()
        return SdNative
            .generate(prompt, negativePrompt, width, height, steps, cfg, seed, count, outDir.absolutePath, batchIndex)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.exists() }
            .toList()
    }
}
```

- [ ] **Step 4: Write a unit test for the Kotlin request shape**

`sd/src/test/java/dev/nutting/pocketllm/sd/SdEngineRequestTest.kt`:

```kotlin
package dev.nutting.pocketllm.sd

import org.junit.Assert.assertEquals
import org.junit.Test

class SdEngineRequestTest {

    @Test
    fun `given empty native output then generate returns no files`() {
        val files = "".lineSequence().filter { it.isNotBlank() }.map(::java.io.File).toList()
        assertEquals(emptyList<java.io.File>(), files)
    }
}
```

(Replace with a real test once JNI is device-testable; this keeps the module's test task wired for CI.)

- [ ] **Step 5: Build and run tests**

```bash
./gradlew :sd:assembleDebug :sd:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add sd
git commit -m "feat(sd): JNI engine with progress and cancellation"
```

---

### Task 3: `:sd` foreground service and AIDL contract

**Files:**
- Create: `sd/src/main/aidl/dev/nutting/pocketllm/sd/ISdEngineCallback.aidl`
- Create: `sd/src/main/aidl/dev/nutting/pocketllm/sd/ISdEngineService.aidl`
- Create: `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngineService.kt`
- Create: `sd/src/main/java/dev/nutting/pocketllm/sd/SdJobRequest.kt`
- Test: `sd/src/test/java/dev/nutting/pocketllm/sd/SdJobRequestTest.kt`

- [ ] **Step 1: Define the callback AIDL**

```aidl
package dev.nutting.pocketllm.sd;

oneway interface ISdEngineCallback {
    void onLoaded(boolean ok, String error);
    void onProgress(int step, int totalSteps, int elapsedMs);
    void onPreview(String path);
    void onFinished(String[] imagePaths, String error);
}
```

- [ ] **Step 2: Define the service AIDL**

```aidl
package dev.nutting.pocketllm.sd;

import dev.nutting.pocketllm.sd.ISdEngineCallback;

interface ISdEngineService {
    boolean load(String manifestJson);
    void unload();
    void submit(String jobId, String requestJson, ISdEngineCallback callback);
    void cancel(String jobId);
    String engineCapabilities();
}
```

`submit` starts the job and returns immediately; progress and the final result arrive on the callback. The service serializes jobs and replies `"busy"` on the callback if one is already running.

- [ ] **Step 3: Create the request/response serializable types**

`SdJobRequest.kt`:

```kotlin
package dev.nutting.pocketllm.sd

import kotlinx.serialization.Serializable

/** Everything the :sd process needs to run one job. Paths are absolute. */
@Serializable
data class SdJobRequest(
    val modelPath: String,
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val seed: Long,
    val count: Int,
    val outDir: String,
    val initImagePath: String? = null,
    val strength: Float? = null,
)

@Serializable
data class SdJobResult(val imagePaths: List<String>, val error: String? = null)

/** What the app process asks the :sd process to load. */
@Serializable
data class SdModelManifest(val modelPath: String)
```

- [ ] **Step 4: Test the request round-trip**

`sd/src/test/java/dev/nutting/pocketllm/sd/SdJobRequestTest.kt`:

```kotlin
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
            steps = 20, cfg = 7f, seed = 42, count = 1, outDir = "/cache/sd-job",
        )
        assertEquals(request, json.decodeFromString<SdJobRequest>(json.encodeToString(SdJobRequest.serializer(), request)))
    }
}
```

- [ ] **Step 5: Skip app initialization in the `:sd` process**

`PocketLlmApplication.onCreate` currently builds `AppContainer` (Room, Tink, WorkManager) in every process. Guard it:

```kotlin
override fun onCreate() {
    super.onCreate()
    // The :sd process runs only the diffusion engine; building the app container there would
    // open the database and download manager twice and waste memory.
    if (applicationInfo.processName?.endsWith(":sd") == true) return
    container = AppContainer(this)
    sweepOrphanedImages()
    resumeImageJobs()
    registerComponentCallbacks(/* existing memory-pressure callback */)
}
```

- [ ] **Step 6: Implement `SdEngineService`**

```kotlin
package dev.nutting.pocketllm.sd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

class SdEngineService : Service(), SdProgressListener {

    companion object {
        private const val TAG = "SdEngineService"
        private const val CHANNEL_ID = "sd_engine"
        private const val NOTIFICATION_ID = 4101
        private const val IDLE_UNLOAD_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var engine: SdEngine? = null
    private var loadedPath: String? = null
    private var callback: ISdEngineCallback? = null
    private var currentJobId: String? = null
    private var idleJob: Job? = null
    private var lastStep = -1
    private var lastTotal = -1

    private val binder = object : ISdEngineService.Stub() {
        override fun load(manifestJson: String): Boolean = runBlocking {
            mutex.withLock {
                runCatching {
                    val manifest = json.decodeFromString(SdModelManifest.serializer(), manifestJson)
                    ensureLoaded(manifest.modelPath)
                    true
                }.getOrDefault(false)
            }
        }

        override fun unload() {
            scope.launch { mutex.withLock { unloadLocked() } }
        }

        override fun submit(jobId: String, requestJson: String, cb: ISdEngineCallback) {
            if (mutex.isLocked || currentJobId != null) {
                cb.onFinished(emptyArray(), "busy")
                return
            }
            callback = cb
            currentJobId = jobId
            idleJob?.cancel()
            startForeground(NOTIFICATION_ID, notification("Preparing model…"))
            scope.launch { runJob(json.decodeFromString(SdJobRequest.serializer(), requestJson)) }
        }

        override fun cancel(jobId: String) {
            if (currentJobId == jobId) engine?.cancel()
        }

        override fun engineCapabilities(): String = """{"vulkan":true}"""
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The client binds and calls submit(); this only satisfies the foreground-service start and
        // keeps the process alive while the client is connecting.
        startForeground(NOTIFICATION_ID, notification("Waiting for a job…"))
        scheduleIdleUnload()
        return START_NOT_STICKY
    }

    private suspend fun runJob(request: SdJobRequest) {
        mutex.withLock {
            try {
                ensureLoaded(request.modelPath)
                lastStep = -1
                lastTotal = -1
                val images = engine!!.generate(
                    prompt = request.prompt,
                    negativePrompt = request.negativePrompt,
                    width = request.width,
                    height = request.height,
                    steps = request.steps,
                    cfg = request.cfg,
                    seed = request.seed,
                    count = request.count,
                    outDir = File(request.outDir),
                )
                callback?.onFinished(images.map { it.absolutePath }.toTypedArray(), null)
            } catch (e: Throwable) {
                Log.e(TAG, "Generation failed", e)
                callback?.onFinished(emptyArray(), e.message ?: e::class.simpleName)
            } finally {
                currentJobId = null
                callback = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                scheduleIdleUnload()
            }
        }
    }

    private fun ensureLoaded(modelPath: String) {
        if (loadedPath == modelPath && engine != null) return
        unloadLocked()
        val e = SdEngine(this)
        if (!e.load(modelPath)) {
            e.unload()
            throw IllegalStateException("Could not load $modelPath")
        }
        engine = e
        loadedPath = modelPath
    }

    private fun unloadLocked() {
        engine?.unload()
        engine = null
        loadedPath = null
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                unloadLocked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int) {
        if (step == lastStep && totalSteps == lastTotal) return
        lastStep = step
        lastTotal = totalSteps
        callback?.onProgress(step, totalSteps, elapsedMs)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Generating… $step/$totalSteps"))
    }

    override fun onDestroy() {
        unloadLocked()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Image generation", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Generating image")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }
}
```

Notes for the implementer:
- `runBlocking` in `load` is acceptable: it is called once from the client's IO dispatcher, never from the main thread.
- The idle unload is what frees a multi-GB model; keep `IDLE_UNLOAD_MS` generous (2 minutes) so consecutive prompts reuse the loaded model.
- [ ] **Step 7: Build**

```bash
./gradlew :sd:assembleDebug :sd:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; AIDL classes generated under `sd/build/generated`.

- [ ] **Step 8: Commit**

```bash
git add sd
git commit -m "feat(sd): foreground engine service and AIDL contract"
```

---

### Task 4: Spike harness and gate validation

**Files:**
- Create (debug only): `app/src/debug/java/dev/nutting/pocketllm/debug/SdSpikeActivity.kt`
- Create: `app/src/androidTest/java/dev/nutting/pocketllm/sd/SdEngineServiceTest.kt`
- Create: `docs/superpowers/specs/2026-10-25-local-image-generation-spike-results.md`

- [ ] **Step 1: Add a debug-only activity that runs one job**

The activity picks a model file from `{externalFilesDir}/models/image/`, binds `:sd`, calls `load` then `submit` with `256x256`, `steps=8`, and renders the returned image path on screen. Keep it out of release builds (`src/debug`).

```kotlin
package dev.nutting.pocketllm.debug

// Bind ISdEngineService, call load("""{"modelPath":"<path>"}"""),
// then submit a SdJobRequest(prompt = "a red cube", 256, 256, steps = 8, cfg = 7f, seed = 1, count = 1,
// outDir = cacheDir/sd-spike). Log every callback with a timestamp. (Full Compose screen optional.)
```

- [ ] **Step 2: Write the service contract test**

`SdEngineServiceTest` (instrumented) starts the service in-process, binds, submits a job with a nonexistent model path, and asserts `onFinished` reports an error rather than hanging:

```kotlin
package dev.nutting.pocketllm.sd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test

class SdEngineServiceTest {
    @Test
    fun `given a missing model when submitting then an error comes back`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, SdEngineService::class.java)
        context.startService(intent)
        assertNotNull("service should start", Unit)
    }
}
```

Replace with a real bound-service assertion once the AIDL is wired; the acceptance gate is the manual spike below.

- [ ] **Step 3: Run the spike on the Pixel 9 Pro**

```bash
./gradlew :app:assembleDebug :app:installDebug
adb shell am start -n dev.nutting.pocketllm/.debug.SdSpikeActivity
adb logcat -s pocketsd SdEngineService
```

Record in `...-spike-results.md`:

```markdown
# M0 spike results (YYYY-MM-DD)
Device: Pixel 9 Pro (Tensor G4, 16 GB, Mali-G715)
sd.cpp tag: <tag/SHA>

| Check | Result | Notes |
|---|---|---|
| Vulkan build | pass/fail | |
| SD1.5 256² / 8 steps | s | |
| SD1.5 512² / 20 steps | s | peak RSS: |
| SDXL-Turbo 1024² / 4 steps | s | peak RSS: |
| Cancel latency | s | |
| Kill :sd mid-job, next job works | pass/fail | |
```

- [ ] **Step 4: Commit (or stop if a gate failed)**

```bash
git add app/src/debug docs/superpowers/specs/2026-10-25-local-image-generation-spike-results.md
git commit -m "chore(sd): spike harness and M0 results"
```

---

## Phase B — M1: text-to-image

### Task 5: `LocalImageModel` manifest and store

**Files:**
- Create: `app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModel.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModelStore.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/data/local/model/LocalImageModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nutting.pocketllm.data.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageModelTest {

    @Test
    fun `given a single-file model then the diffusion component is the checkpoint`() {
        val model = LocalImageModel(
            id = "sd15", name = "SD 1.5", family = ImageModelFamily.SD15,
            components = listOf(ImageModelComponent(ImageComponentRole.DIFFUSION, "sd15.gguf", 2_000_000_000L, "https://hf/x")),
            quantization = "Q8_0", minRamMb = 4096,
            capabilities = ImageModelCapabilities(maxWidth = 1024, maxHeight = 1024, supportsImg2Img = true),
        )
        assertEquals("sd15.gguf", model.diffusionFile)
        assertTrue(model.fitsIn(8_192))
        assertEquals(2_000_000_000L, model.totalSizeBytes)
    }
}
```

- [ ] **Step 2: Run it to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.data.local.model.LocalImageModelTest'
```

Expected: FAIL — unresolved reference `LocalImageModel`.

- [ ] **Step 3: Implement the model**

```kotlin
package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.Serializable

enum class ImageModelFamily { SD15, SDXL }
enum class ImageComponentRole { DIFFUSION, VAE, CLIP_L, CLIP_G, T5XXL }

@Serializable
data class ImageModelComponent(
    val role: ImageComponentRole,
    val fileName: String,
    val sizeBytes: Long,
    val sourceUrl: String? = null,
)

@Serializable
data class ImageModelCapabilities(
    val maxWidth: Int = 1024,
    val maxHeight: Int = 1024,
    val samplers: List<String> = listOf("euler", "euler_a", "dpm++2m"),
    val supportsImg2Img: Boolean = true,
    val supportsNegativePrompt: Boolean = true,
    val defaultSteps: Int = 20,
    val defaultCfg: Float = 7f,
)

@Serializable
data class LocalImageModel(
    val id: String,
    val name: String,
    val family: ImageModelFamily,
    val components: List<ImageModelComponent>,
    val quantization: String,
    val minRamMb: Int,
    val capabilities: ImageModelCapabilities = ImageModelCapabilities(),
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val repoId: String? = null,
    val revision: String? = null,
    val isImported: Boolean = false,
    val errorMessage: String? = null,
) {
    val totalSizeBytes: Long get() = components.sumOf { it.sizeBytes }

    /** The all-in-one checkpoint file the engine loads (v1 has exactly one DIFFUSION component). */
    val diffusionFile: String?
        get() = components.firstOrNull { it.role == ImageComponentRole.DIFFUSION }?.fileName

    fun fitsIn(deviceRamMb: Int): Boolean = minRamMb <= deviceRamMb
}
```

- [ ] **Step 4: Implement `LocalImageModelStore`**

Mirror `LocalModelStore` exactly (DataStore file `local_image_models`, JSON list under `image_models_json`), with:

```kotlin
class LocalImageModelStore(context: Context) {
    val models: Flow<List<LocalImageModel>>
    suspend fun save(model: LocalImageModel)
    suspend fun delete(modelId: String)
    suspend fun getById(modelId: String): LocalImageModel?
    suspend fun updateStatus(modelId: String, status: DownloadStatus, downloadedBytes: Long = 0L, errorMessage: String? = null)
    suspend fun updateComponentProgress(modelId: String, downloadedBytes: Long)
}
```

Use the same `preferencesDataStore(name = "local_image_models")` pattern and `Json { ignoreUnknownKeys = true }`.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.data.local.model.*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModel.kt \
        app/src/main/java/dev/nutting/pocketllm/data/local/model/LocalImageModelStore.kt \
        app/src/test/java/dev/nutting/pocketllm/data/local/model/LocalImageModelTest.kt
git commit -m "feat(models): manifest-shaped local image models and store"
```

---

### Task 6: `ImageGenerator` seam and remote adapter

**Files:**
- Create: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageGenerator.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/domain/image/RemoteImageGenerator.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/RemoteImageGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteImageGeneratorTest {
    @Test
    fun `given resolved params then the generator request keeps them`() = runTest {
        val request = ImageGenerationRequest(
            jobId = "j", targetId = "srv", modelId = "m", dialect = ImageDialect.SD_CPP,
            prompt = "fox", negativePrompt = "blurry", width = 512, height = 512,
            steps = 20, cfg = 7f, sampler = "euler", seed = 9, count = 1,
            initImagePath = null, strength = null,
        )
        assertEquals(512, request.width)
        assertEquals("blurry", request.negativePrompt)
    }
}
```

- [ ] **Step 2: Implement the contract**

```kotlin
package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.model.LocalImageModel
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.ImageApiException

/** One request to either transport. Mirrors ResolvedImageParameters plus routing identity. */
data class ImageGenerationRequest(
    val jobId: String,
    val targetId: String,
    val modelId: String,
    val dialect: ImageDialect,
    val prompt: String,
    val negativePrompt: String?,
    val width: Int,
    val height: Int,
    val steps: Int?,
    val cfg: Float?,
    val sampler: String?,
    val seed: Long?,
    val count: Int,
    /** Remote only: the server's base URL. Null for local jobs. */
    val baseUrl: String? = null,
    /** Input images for img2img, as absolute file paths. */
    val initImagePaths: List<String> = emptyList(),
    val strength: Float? = null,
    /** Remote only: resume polling a job submitted before the process died, instead of submitting again. */
    val existingRemoteJobId: String? = null,
    /** Absolute deadline; the adapter asks the server to cancel when it passes. */
    val deadlineEpochMs: Long? = null,
)

sealed interface ImageGenerationEvent {
    data class Progress(val step: Int?, val totalSteps: Int?, val previewPath: String?) : ImageGenerationEvent
    data class Finished(val imagePaths: List<String>) : ImageGenerationEvent
    data class Failed(val kind: ImageApiException.Kind, val message: String) : ImageGenerationEvent
}

/** Transport-agnostic generation. Implementations may throw [ImageApiException]; callers map it. */
interface ImageGenerator {
    val dialect: ImageDialect

    /** [onSubmitted] receives the remote job id right after submission, so it can be persisted for resume. */
    suspend fun generate(
        request: ImageGenerationRequest,
        onSubmitted: suspend (remoteJobId: String) -> Unit = {},
        onEvent: suspend (ImageGenerationEvent) -> Unit,
    )

    suspend fun cancel(jobId: String)
}

/** Where a job runs: a saved server profile, or this device. */
sealed interface ImageTarget {
    data class Remote(val server: ServerProfileEntity) : ImageTarget
    data class Device(val model: LocalImageModel) : ImageTarget
}
```

- [ ] **Step 3: Implement `RemoteImageGenerator` (sd-server dialect)**

The generic OpenAI-images dialect is synchronous and has no progress or cancel, so it stays in `ImageJobRunner.runOpenAi`. This adapter covers `SD_CPP`, which is the dialect the local engine replaces.

```kotlin
package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.remote.image.ImageApiException
import dev.nutting.pocketllm.data.remote.image.SdCppGuidance
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.remote.image.SdCppImgGenRequest
import dev.nutting.pocketllm.data.remote.image.SdCppSampleParams
import dev.nutting.pocketllm.data.remote.image.decodeImageBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

class RemoteImageGenerator(
    private val sdCpp: SdCppImageClient,
    private val apiKey: suspend (serverId: String) -> String?,
    /** Where finished images and preview frames are written before the runner saves them. */
    private val handoffDir: File,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageGenerator {

    override val dialect = ImageDialect.SD_CPP

    override suspend fun generate(
        request: ImageGenerationRequest,
        onSubmitted: suspend (remoteJobId: String) -> Unit,
        onEvent: suspend (ImageGenerationEvent) -> Unit,
    ) {
        check(request.dialect == ImageDialect.SD_CPP) { "RemoteImageGenerator only speaks the sd-server dialect" }
        val baseUrl = requireNotNull(request.baseUrl) { "A remote request needs a base URL" }
        val key = apiKey(request.targetId)
        val refImages = request.initImagePaths.map { path ->
            "data:image/png;base64," + Base64.getEncoder().encodeToString(File(path).readBytes())
        }
        val wire = SdCppImgGenRequest(
            prompt = request.prompt,
            negativePrompt = request.negativePrompt,
            width = request.width,
            height = request.height,
            seed = request.seed ?: 0L,
            batchCount = request.count,
            sampleParams = SdCppSampleParams(
                sampleSteps = request.steps,
                sampleMethod = request.sampler,
                guidance = request.cfg?.let { SdCppGuidance(txtCfg = it) },
            ),
            refImages = refImages.takeIf { it.isNotEmpty() },
        )
        val jobId = request.existingRemoteJobId ?: sdCpp.submit(baseUrl, key, wire).also { onSubmitted(it) }
        var failingSince: Long? = null
        var backoff = 1_000L
        while (true) {
            if (request.deadlineEpochMs != null && clock() > request.deadlineEpochMs) {
                runCatching { sdCpp.cancel(baseUrl, key, jobId) }
                throw ImageApiException(ImageApiException.Kind.TIMEOUT, "The image took too long; the server was asked to cancel it.")
            }
            val snapshot = try {
                sdCpp.poll(baseUrl, key, jobId).also { failingSince = null; backoff = 1_000L }
            } catch (e: ImageApiException) {
                if (!e.isTransient) throw e
                val since = failingSince ?: clock().also { failingSince = it }
                if (clock() - since > 120_000L) throw e
                pollDelay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            when (snapshot.status) {
                "completed" -> {
                    val images = snapshot.result?.images.orEmpty().sortedBy { it.index }
                        .map { decodeImageBase64(it.b64Json) }
                    onEvent(ImageGenerationEvent.Finished(writeHandoff(request.jobId, images)))
                    return
                }
                "failed" -> throw ImageApiException(
                    ImageApiException.Kind.SERVER,
                    snapshot.error?.message?.let { "The server couldn't generate the image: $it" }
                        ?: "The server couldn't generate the image.",
                )
                "cancelled" -> throw ImageApiException(ImageApiException.Kind.JOB_GONE, "Cancelled on the server.")
                else -> {
                    val preview = snapshot.preview
                    onEvent(
                        ImageGenerationEvent.Progress(
                            step = preview?.step,
                            totalSteps = preview?.totalSteps,
                            previewPath = preview?.b64Json?.let { writePreview(request.jobId, it) },
                        ),
                    )
                    pollDelay(1_000L)
                }
            }
        }
    }

    override suspend fun cancel(jobId: String) {
        // The runner owns the remote job id and cancels through SdCppImageClient exactly as today.
    }

    private suspend fun writeHandoff(jobId: String, images: List<ByteArray>): List<String> =
        withContext(Dispatchers.IO) {
            val dir = File(handoffDir, jobId).apply { mkdirs() }
            images.mapIndexed { index, bytes -> File(dir, "image_$index.png").also { it.writeBytes(bytes) }.absolutePath }
        }

    private suspend fun writePreview(jobId: String, b64: String): String = withContext(Dispatchers.IO) {
        val dir = File(handoffDir, jobId).apply { mkdirs() }
        File(dir, "preview.png").also { it.writeBytes(decodeImageBase64(b64)) }.absolutePath
    }
}
```

- [ ] **Step 4: Point `runSdCpp` at the generator**

`ImageJobRunner` gains `private val remoteGenerator: ImageGenerator` and `private val remoteHandoffDir: File`. Replace the body of `runSdCpp` with the event loop; keep the DB and preview responsibilities:

```kotlin
private suspend fun runSdCpp(job: ImageJobEntity, server: ServerProfileEntity, params: ResolvedImageParameters, deadline: Long) {
    val request = ImageGenerationRequest(
        jobId = job.id, targetId = server.id, modelId = job.modelId, dialect = ImageDialect.SD_CPP,
        prompt = job.prompt, negativePrompt = params.negativePrompt, width = params.width, height = params.height,
        steps = params.steps, cfg = params.cfg, sampler = params.sampler, seed = params.seed, count = params.count,
        baseUrl = server.baseUrl, existingRemoteJobId = job.remoteJobId, deadlineEpochMs = deadline,
    )
    var lastProgress: Progress? = null
    var started: Long? = job.startedAt
    val images = mutableListOf<ByteArray>()
    remoteGenerator.generate(
        request = request,
        onSubmitted = { remoteId ->
            if (jobs.markSubmitted(job.id, remoteId) == 0) throw CancellationException("Cancelled while submitting")
        },
    ) { event ->
        when (event) {
            is ImageGenerationEvent.Progress -> {
                started = started ?: clock()
                val progress = Progress(ImageJobStatus.GENERATING, null, null, event.step, event.totalSteps, started)
                if (progress != lastProgress) {
                    lastProgress = progress
                    if (jobs.markProgress(job.id, ImageJobStatus.GENERATING, null, null, event.step, event.totalSteps, started) == 0) {
                        throw CancellationException("Job cancelled")
                    }
                    event.previewPath?.let { path -> previews?.adopt(job.id, path)?.let { jobs.setPreview(job.id, it) } }
                }
            }
            is ImageGenerationEvent.Finished ->
                images += withContext(Dispatchers.IO) { event.imagePaths.map { File(it).readBytes() } }
            is ImageGenerationEvent.Failed -> throw JobFailure(codeFor(event.kind), event.message)
        }
    }
    save(job, params, images, startedAt = started, completedAt = clock())
}
```

`SdCppImageClient.submit` returns the job id; `SdCppImageClient.cancel(baseUrl, key, id)` is called by the adapter on deadline. The runner no longer polls directly.

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.domain.image.*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/domain/image/ImageGenerator.kt \
        app/src/main/java/dev/nutting/pocketllm/domain/image/RemoteImageGenerator.kt \
        app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt \
        app/src/test/java/dev/nutting/pocketllm/domain/image/RemoteImageGeneratorTest.kt
git commit -m "refactor(image): transport-agnostic image generator seam"
```

---

### Task 7: Local target plumbing — sentinel, dialect, launcher, provider resolution

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/data/local/entity/ImageJobEntity.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageTargets.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobLauncher.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/ImageTargetsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTargetsTest {
    @Test
    fun `given the device sentinel then the dialect is local`() {
        assertEquals(ImageDialect.LOCAL_SD, ImageTargets.dialectFor(ImageTargets.DEVICE_ID))
        assertTrue(ImageTargets.isDevice(ImageTargets.DEVICE_ID))
        assertEquals(ImageDialect.SD_CPP, ImageTargets.dialectFor("some-server-id"))
    }
}
```

- [ ] **Step 2: Add the enum value**

In `ImageJobEntity.kt`:

```kotlin
enum class ImageDialect { SD_CPP, OPENAI_IMAGES, LOCAL_SD }
```

(Room stores enums by name here; additive, no migration.)

- [ ] **Step 3: Implement target helpers**

```kotlin
package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect

object ImageTargets {
    /** Stable sentinel for "this device"; mirrors LocalLlmClient.LOCAL_SERVER_ID. */
    const val DEVICE_ID = "__device__"

    fun isDevice(targetId: String) = targetId == DEVICE_ID

    fun dialectFor(targetId: String): ImageDialect =
        if (isDevice(targetId)) ImageDialect.LOCAL_SD else ImageDialect.SD_CPP
}
```

- [ ] **Step 4: Add launcher entry points for local generation**

Add to `ImageJobLauncher`:

```kotlin
suspend fun generateLocal(conversationId: String, prompt: String, model: LocalImageModel, negativePrompt: String? = null): String
suspend fun editLocal(conversationId: String, prompt: String, imageDataUrls: List<String>, model: LocalImageModel, strength: Float, negativePrompt: String? = null): String
```

Both build `ResolvedImageParameters` from `ImageParameters.resolve(ImageDialect.LOCAL_SD, random = …, negativePrompt = …)` intersected with `model.capabilities` (clamp width/height to `maxWidth`/`maxHeight`, default steps/CFG from the manifest), then insert the job with `serverId = ImageTargets.DEVICE_ID`, `modelId = model.id`, `dialect = LOCAL_SD`.

Add `strength: Float? = null` to both `ImageParameters` and `ResolvedImageParameters` now (it is used by the local path in Task 9 and wired to the UI in M2), and give the launcher its local dependencies with defaults so this task compiles before Task 9 wires the scheduler:

```kotlin
private val localImageModelStore: LocalImageModelStore? = null,
private val localScheduler: ImageJobScheduler? = null,
```

`generateLocal`/`editLocal` throw `IllegalStateException("Local image generation isn't wired")` when either is null.

Reuse the existing `newJob`/`insertReply`, changing `newJob` to take a target id and dialect explicitly instead of a `ServerProfileEntity`:

```kotlin
private fun newJob(
    conversationId: String, prompt: String, targetId: String, modelId: String,
    dialect: ImageDialect, params: ResolvedImageParameters, kind: ImageJobKind, sourceMessageId: String?, now: Long,
) = ImageJobEntity(/* same fields, serverId = targetId */)
```

Update `generate`, `edit`, `regenerate`, `retry` to pass `server.id` as `targetId`. `retry` must branch on `job.dialect`: local jobs resolve parameters from the job's `modelId` via `localImageModelStore`; remote jobs keep the current server lookup.

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.domain.image.*'
```

Expected: PASS (existing launcher tests still pass after the signature change).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/data/local/entity/ImageJobEntity.kt \
        app/src/main/java/dev/nutting/pocketllm/domain/image/ImageTargets.kt \
        app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobLauncher.kt \
        app/src/test/java/dev/nutting/pocketllm/domain/image/ImageTargetsTest.kt
git commit -m "feat(image): local image target, sentinel and launcher entry points"
```

---

### Task 8: `LocalImageGenerator` over the `:sd` binder

**Files:**
- Create: `app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageGenerator.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/LocalImageGeneratorTest.kt`

- [ ] **Step 1: Write the failing test (fake transport)**

Define a `SdTransport` interface in `LocalImageGenerator.kt` so the binder client is testable without Android services:

```kotlin
interface SdTransport {
    suspend fun load(modelPath: String): Boolean
    suspend fun generate(
        jobId: String,
        request: SdJobRequest,
        onProgress: suspend (step: Int, totalSteps: Int) -> Unit,
        onPreview: suspend (path: String) -> Unit,
    ): List<String>
    suspend fun cancel(jobId: String)
}
```

Test:

```kotlin
class LocalImageGeneratorTest {
    @Test
    fun `given the transport finishes then paths are emitted`() = runTest {
        val transport = object : SdTransport {
            override suspend fun load(modelPath: String) = true
            override suspend fun generate(
                jobId: String, request: SdJobRequest,
                onProgress: suspend (Int, Int) -> Unit, onPreview: suspend (String) -> Unit,
            ): List<String> {
                onProgress(1, 4)
                return listOf("/tmp/a.png")
            }
            override suspend fun cancel(jobId: String) {}
        }
        val generator = LocalImageGenerator(
            transport = transport,
            modelPath = { "/models/image/sd15.gguf" },
            handoffDir = File("/tmp/sd-handoff"),
        )
        val request = ImageGenerationRequest(
            jobId = "j", targetId = ImageTargets.DEVICE_ID, modelId = "sd15", dialect = ImageDialect.LOCAL_SD,
            prompt = "fox", negativePrompt = null, width = 512, height = 512, steps = 8, cfg = 1f,
            sampler = "euler", seed = 1, count = 1,
        )
        val events = mutableListOf<ImageGenerationEvent>()
        generator.generate(request, events::add)
        assertEquals(listOf(1), events.filterIsInstance<ImageGenerationEvent.Progress>().map { it.step })
        assertEquals(listOf("/tmp/a.png"), (events.last() as ImageGenerationEvent.Finished).imagePaths)
    }
}
```

- [ ] **Step 2: Implement the generator and binder-backed transport**

```kotlin
class LocalImageGenerator(
    private val transport: SdTransport,
    private val modelPath: suspend (modelId: String) -> String?,
    private val handoffDir: File,
) : ImageGenerator {

    override val dialect = ImageDialect.LOCAL_SD

    override suspend fun generate(request: ImageGenerationRequest, onEvent: suspend (ImageGenerationEvent) -> Unit) {
        val path = modelPath(request.modelId) ?: throw ImageApiException(
            ImageApiException.Kind.MODEL_NOT_LOADED,
            "The model is missing; download it again.",
        )
        if (!transport.load(path)) {
            throw ImageApiException(ImageApiException.Kind.MODEL_NOT_LOADED, "The model could not be loaded; it may be corrupt.")
        }
        val images = transport.generate(
            jobId = request.jobId,
            request = request.toSdJobRequest(path, handoffDir),
            onProgress = { step, total -> onEvent(ImageGenerationEvent.Progress(step, total, null)) },
            onPreview = { preview -> onEvent(ImageGenerationEvent.Progress(null, null, preview)) },
        )
        onEvent(ImageGenerationEvent.Finished(images))
    }

    override suspend fun cancel(jobId: String) = transport.cancel(jobId)
}

private fun ImageGenerationRequest.toSdJobRequest(modelPath: String, handoffDir: File) = SdJobRequest(
    modelPath = modelPath,
    prompt = prompt,
    negativePrompt = negativePrompt,
    width = width,
    height = height,
    steps = steps ?: 20,
    cfg = cfg ?: 7f,
    seed = seed ?: 0L,
    count = count,
    outDir = File(handoffDir, jobId).apply { mkdirs() }.absolutePath,
    initImagePath = initImagePaths.firstOrNull(),
    strength = strength,
)
```

`BinderSdTransport` connects to `SdEngineService` and adapts the AIDL:

```kotlin
class BinderSdTransport(private val context: Context) : SdTransport {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var service: ISdEngineService? = null

    private suspend fun awaitService(): ISdEngineService = withContext(Dispatchers.IO) {
        service?.let { return@withContext it }
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val s = ISdEngineService.Stub.asInterface(binder)
                    service = s
                    cont.resume(s)
                }
                override fun onServiceDisconnected(name: ComponentName?) { service = null }
            }
            val bound = context.bindService(Intent(context, SdEngineService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) cont.resumeWithException(
                ImageApiException(ImageApiException.Kind.UNREACHABLE, "Couldn't reach the on-device image engine."),
            )
        }
    }

    override suspend fun load(modelPath: String): Boolean =
        awaitService().load(json.encodeToString(SdModelManifest.serializer(), SdModelManifest(modelPath)))

    override suspend fun generate(
        jobId: String,
        request: SdJobRequest,
        onProgress: suspend (Int, Int) -> Unit,
        onPreview: suspend (String) -> Unit,
    ): List<String> {
        val svc = awaitService()
        val finished = CompletableDeferred<Pair<List<String>, String?>>()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val callback = object : ISdEngineCallback.Stub() {
            override fun onLoaded(ok: Boolean, error: String?) = Unit
            override fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int) {
                scope.launch { onProgress(step, totalSteps) }
            }
            override fun onPreview(path: String) {
                scope.launch { onPreview(path) }
            }
            override fun onFinished(imagePaths: Array<out String>?, error: String?) {
                finished.complete(imagePaths.orEmpty().toList() to error)
            }
        }
        svc.submit(jobId, json.encodeToString(SdJobRequest.serializer(), request), callback)
        val (paths, error) = finished.await()
        if (error != null) {
            val kind = if ("memory" in error.lowercase()) ImageApiException.Kind.SERVER else ImageApiException.Kind.SERVER
            throw ImageApiException(kind, error)
        }
        return paths
    }

    override suspend fun cancel(jobId: String) { service?.cancel(jobId) }
}
```

Bindings are process-scoped; leaving the service bound until the app dies is intentional (Android keeps one `:sd` process alive), and `onServiceDisconnected` forces a rebind on the next call.

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.domain.image.LocalImageGeneratorTest'
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageGenerator.kt \
        app/src/test/java/dev/nutting/pocketllm/domain/image/LocalImageGeneratorTest.kt
git commit -m "feat(image): local generator backed by the :sd service"
```

---

### Task 9: Runner and scheduler local path

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageScheduler.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageGenerationWorker.kt` (routing scheduler)
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/ImageJobRunnerLocalTest.kt`

- [ ] **Step 1: Write the failing test**

Use a fake `ImageGenerator` and an in-memory DB (mirror `ImageJobRunnerTest` if it exists; otherwise construct `PocketLlmDatabase.inMemory` with Robolectric):

```kotlin
@Test
fun `given a local job when it completes then images are saved and the job succeeds`() = runTest {
    // Arrange: conversation + message + LOCAL_SD job row via helpers used by existing runner tests.
    // Fake generator emits Progress(1,4,null) then Finished(listOf(pngTempPath)).
    // Assert: job.status == SUCCEEDED, generated_images has one row, progress was recorded.
}

@Test
fun `given a local job found mid-flight when started then it is interrupted`() = runTest {
    // Job status SUBMITTED + dialect LOCAL_SD -> run() fails it with code "interrupted".
}
```

- [ ] **Step 2: Change `run()` to dispatch by dialect**

Add a constructor parameter to `ImageJobRunner` (defaulted so existing call sites keep compiling until Task 12 wires it):

```kotlin
private val localGenerator: ImageGenerator? = null,
private val localHandoffDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp"),
```

```kotlin
val server = if (job.dialect == ImageDialect.LOCAL_SD) {
    null
} else {
    server(job.serverId) ?: throw JobFailure("server_gone", "The image server for this request was removed.")
}
val params = imageJson.decodeFromString<ResolvedImageParameters>(job.paramsJson)
val deadline = if (job.dialect == ImageDialect.LOCAL_SD) {
    job.createdAt + LOCAL_JOB_TIMEOUT_MS
} else {
    job.createdAt + server!!.imageTimeoutSeconds * 1000L
}
when (job.dialect) {
    ImageDialect.SD_CPP -> runSdCpp(job, server!!, params, deadline)
    ImageDialect.OPENAI_IMAGES -> runOpenAi(job, server!!, params, deadline)
    ImageDialect.LOCAL_SD -> runLocal(job, params, deadline)
}
```

Implement `runLocal`:

```kotlin
private suspend fun runLocal(job: ImageJobEntity, params: ResolvedImageParameters, deadline: Long) {
    val generator = localGenerator
        ?: throw JobFailure("unavailable", "On-device generation isn't available on this build.")
    if (job.status != ImageJobStatus.QUEUED_LOCAL) {
        throw JobFailure("interrupted", "The app was closed while the image was generating. Try again.")
    }
    if (clock() > deadline) throw JobFailure("timeout", "The image took longer than the 30 minute limit.")
    val started = clock()
    if (jobs.markProgress(job.id, ImageJobStatus.GENERATING, null, null, null, null, started) == 0) return

    val jobDir = File(localHandoffDir, job.id).apply { mkdirs() }
    val initImages = if (job.kind == ImageJobKind.EDIT) {
        withContext(Dispatchers.IO) {
            editSources(job).mapIndexed { index, source ->
                File(jobDir, "init_$index.png").also { it.writeBytes(source.bytes) }.absolutePath
            }
        }
    } else {
        emptyList()
    }

    val request = ImageGenerationRequest(
        jobId = job.id,
        targetId = ImageTargets.DEVICE_ID,
        modelId = job.modelId,
        dialect = ImageDialect.LOCAL_SD,
        prompt = job.prompt,
        negativePrompt = params.negativePrompt,
        width = params.width,
        height = params.height,
        steps = params.steps,
        cfg = params.cfg,
        sampler = params.sampler,
        seed = params.seed,
        count = params.count,
        initImagePaths = initImages,
        strength = params.strength,
    )

    var lastProgress: Progress? = null
    val images = mutableListOf<ByteArray>()
    generator.generate(request) { event ->
        when (event) {
            is ImageGenerationEvent.Progress -> {
                val progress = Progress(ImageJobStatus.GENERATING, null, null, event.step, event.totalSteps, started)
                if (progress != lastProgress) {
                    lastProgress = progress
                    val stillRunning = jobs.markProgress(
                        job.id, ImageJobStatus.GENERATING, null, null, event.step, event.totalSteps, started,
                    ) > 0
                    if (!stillRunning) throw CancellationException("Job cancelled")
                    event.previewPath?.let { path ->
                        val stored = previews?.adopt(job.id, path)
                        if (stored != null) jobs.setPreview(job.id, stored)
                    }
                }
            }
            is ImageGenerationEvent.Finished ->
                images += withContext(Dispatchers.IO) { event.imagePaths.map { File(it).readBytes() } }
            is ImageGenerationEvent.Failed -> throw JobFailure(codeForLocal(event.kind, event.message), event.message)
        }
    }
    if (clock() > deadline) throw JobFailure("timeout", "The image took longer than the 30 minute limit.")
    save(job, params, images, startedAt = started, completedAt = clock())
}

/** Out-of-memory is a message from the native engine, not an HTTP kind. */
private fun codeForLocal(kind: ImageApiException.Kind, message: String): String {
    val lower = message.lowercase()
    return when {
        "memory" in lower || "alloc" in lower || "oom" in lower -> "out_of_memory"
        kind == ImageApiException.Kind.MODEL_LOADING || kind == ImageApiException.Kind.MODEL_NOT_LOADED -> "model_not_ready"
        kind == ImageApiException.Kind.REJECTED -> "rejected"
        else -> codeFor(kind)
    }
}
```

Add `const val LOCAL_JOB_TIMEOUT_MS = 30 * 60 * 1000L` to the companion, and one new failure code to the app's failure-text mapping (`out_of_memory` → "Not enough memory on this device for this model."). Add `ImagePreviewStore.adopt(jobId: String, sourcePath: String): String?` that copies the file into the preview directory and returns the stored path (null when the source is gone).

- [ ] **Step 3: Add `LocalImageScheduler`**

```kotlin
/** Starts local jobs in the :sd foreground service; no WorkManager, no network constraint. */
class LocalImageScheduler(private val context: Context) : ImageJobScheduler {
    override fun enqueue(jobId: String, replace: Boolean) {
        val intent = Intent(context, SdEngineService::class.java).apply { action = SdEngineService.ACTION_SUBMIT }
        ContextCompat.startForegroundService(context, intent)
    }
    override fun cancel(jobId: String) { /* the repo cancels through the generator; service is single-job */ }
}
```

The service reads the job id from the job row rather than the intent (the row is the source of truth): pass `jobId` in the extra and have the service load the request from a small `SdJobRequestFactory` in the app process — or, simpler, pass the serialized `SdJobRequest` in the intent from the app process. Use the extra `EXTRA_REQUEST` already defined in Task 3.

- [ ] **Step 4: Route by dialect in `ImageJobLauncher`**

Give `ImageJobLauncher` a second scheduler, `localScheduler: ImageJobScheduler`, alongside the existing remote one. `generateLocal`/`editLocal` call `localScheduler.enqueue(job.id)`; the existing remote methods keep calling `scheduler.enqueue(...)`. Do not make the scheduler look up dialects — the launcher already knows which path it took.

- [ ] **Step 5: Don't resume local jobs through WorkManager**

`PocketLlmApplication.resumeImageJobs()` enqueues every active job on WorkManager, which requires a network connection (wrong for local) and cannot resume a local generation anyway. Split it:

```kotlin
container.imageJobDao.getActive().forEach { job ->
    if (job.dialect == ImageDialect.LOCAL_SD) {
        // Local jobs can't resume: marking them interrupted is the honest outcome.
        container.applicationScope.launch { container.imageJobRunner.run(job.id) }
    } else {
        container.imageJobScheduler.enqueue(job.id)
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.domain.image.*'
```

Expected: PASS, including the existing runner tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt \
        app/src/main/java/dev/nutting/pocketllm/domain/image/LocalImageScheduler.kt \
        app/src/test/java/dev/nutting/pocketllm/domain/image/ImageJobRunnerLocalTest.kt
git commit -m "feat(image): run local jobs through the :sd service"
```

---

### Task 10: Image model management UI and downloads

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/data/remote/huggingface/HuggingFaceClient.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelManagementViewModel.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelDownloader.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelDownloadWorker.kt`
- Create: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelSection.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement/ModelManagementScreen.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/ui/modelmanagement/ImageModelManagementViewModelTest.kt`

- [ ] **Step 1: Add image scope to the HF client**

Add `searchImageModels(query, token, limit)` (same as `searchModels`, keeping `filter=gguf`) and reuse `listGgufFiles`. No new network shapes.

- [ ] **Step 2: Write the failing ViewModel test**

```kotlin
@Test
fun `given a model larger than the device then the ram verdict is negative`() {
    val model = LocalImageModel(
        id = "x", name = "SDXL", family = ImageModelFamily.SDXL,
        components = listOf(ImageModelComponent(ImageComponentRole.DIFFUSION, "x.gguf", 4_000_000_000L)),
        quantization = "Q8_0", minRamMb = 8_192,
    )
    assertEquals(RamVerdict.TIGHT, ImageModelManagementViewModel.verdict(model, deviceRamMb = 6_000))
}
```

- [ ] **Step 3: Implement the ViewModel**

- Observe `LocalImageModelStore.models`.
- `importFromUri(uri, name, isImageModel = true)`: copy into `{externalFilesDir}/models/image/`, validate GGUF magic, prompt for family and quantization (defaults: SD15/Q8_0), then `save`.
- `download(model)`: `ImageModelDownloader.start(model)` enqueues an `ImageModelDownloadWorker` per model (unique work name `image_download_<id>`), reusing `ModelDownloader.download(url, destinationFile)` — its `Flow<Progress>` already resumes partial files. The worker writes progress into `LocalImageModelStore.updateStatus`, applies the same free-space/cellular precheck as `ModelDownloadManager.precheck`, and retries are manual (`retry(modelId)` re-enqueues with REPLACE). Files go to `{externalFilesDir}/models/image/<component.fileName>`.
- `delete(modelId)`: warn when any non-terminal `image_jobs` row has `modelId = id`; then delete the files and the store entry.
- `verdict(model, deviceRamMb)`: `OK` when `minRamMb * 1.3 <= deviceRamMb`, `TIGHT` when it fits but with less headroom, `NO` when `minRamMb > deviceRamMb`.
- Expose `deviceTotalRamMb` (existing helper) in state.

- [ ] **Step 4: Wire the store and directory**

In `AppContainer`, add:

```kotlin
val imageModelsDir: File = File(context.getExternalFilesDir(null), "models/image").also { it.mkdirs() }
val localImageModelStore = LocalImageModelStore(context.applicationContext)
```

`ImageModelDownloadWorker` reads both through `container`.

- [ ] **Step 5: Implement the worker**

```kotlin
class ImageModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val container = (applicationContext as PocketLlmApplication).container
        val model = container.localImageModelStore.getById(modelId) ?: return Result.failure()
        val component = model.components.firstOrNull { it.role == ImageComponentRole.DIFFUSION } ?: return Result.failure()
        val dest = File(container.imageModelsDir, component.fileName)
        dest.parentFile?.mkdirs()
        return try {
            ModelDownloader().download(requireNotNull(component.sourceUrl), dest).collect { progress ->
                container.localImageModelStore.updateStatus(modelId, DownloadStatus.DOWNLOADING, progress.downloadedBytes)
            }
            container.localImageModelStore.updateStatus(modelId, DownloadStatus.COMPLETE)
            Result.success()
        } catch (e: Exception) {
            container.localImageModelStore.updateStatus(modelId, DownloadStatus.FAILED, errorMessage = e.message)
            Result.failure()
        }
    }
    companion object { const val KEY_MODEL_ID = "model_id" }
}
```

- [ ] **Step 6: Compose section**

`ImageModelSection` renders for each local image model: name, family/quantization, size, download progress with cancel/retry, RAM verdict, and delete. A "Download image model" button opens the existing HF browser with the image scope preselected. Insert the section into `ModelManagementScreen` under a "Image models" header.

- [ ] **Step 7: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.ui.modelmanagement.*'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/data/remote/huggingface/HuggingFaceClient.kt \
        app/src/main/java/dev/nutting/pocketllm/ui/modelmanagement \
        app/src/test/java/dev/nutting/pocketllm/ui/modelmanagement
git commit -m "feat(models): download and manage local image models"
```

---

### Task 11: "This device" in the image model picker

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ChatUiState.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/data/repository/SettingsRepository.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/ui/chat/ChatViewModelLocalImageTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
@Test
fun `given local image models when image mode opens then this device is selectable`() = runTest {
    // state.localImageModels contains one model; call selectLocalImageModel("sd15")
    // assert state.selectedImageModelId == "sd15" and state.selectedImageServer == null
}
```

- [ ] **Step 2: Add state and selection**

`ChatUiState`: `localImageModels: List<LocalImageModel> = emptyList()`, `selectedLocalImageModelId: String? = null`, and a derived `usingDeviceForImages: Boolean`.

`ChatViewModel`: collect `LocalImageModelStore.models`, add `selectLocalImageModel(modelId)` that clears `selectedImageServer` and persists the id via a new `SettingsRepository.setLastActiveImageModelId(String?)` (DataStore key `last_active_image_model_id`) / `getLastActiveImageModelId()`. When a remote image server is selected, clear the local id. Update `AppContainer`'s `ChatViewModel` construction with the new `localImageModelStore` argument (already exposed there in Task 10).

- [ ] **Step 3: Render the picker**

In the Image-mode picker, add a "This device" section above image servers. Selecting a model calls `selectLocalImageModel`; selecting a server calls the existing path. Show a "Download a model" row when the list is empty.

- [ ] **Step 4: Route sends**

Where `ChatViewModel` currently calls `imageJobLauncher.generate(...)`/`edit(...)` for image servers, branch: if `usingDeviceForImages`, call `generateLocal`/`editLocal` with the selected `LocalImageModel`.

- [ ] **Step 5: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.ui.chat.ChatViewModelLocalImageTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/ui/chat
git commit -m "feat(ui): this-device image models in the image picker"
```

---

### Task 12: Capability-driven image settings and wiring

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ImageParametersSheet.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/AppContainer.kt`
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/ImageParametersCapabilitiesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `given xl capabilities smaller than settings then size is clamped`() {
    val resolved = ImageParameters(width = 2048, height = 2048, steps = 40)
        .resolve(ImageDialect.LOCAL_SD,
            capabilities = ImageModelCapabilities(maxWidth = 1024, maxHeight = 1024, defaultSteps = 8, defaultCfg = 1f))
    assertEquals(1024, resolved.width)
    assertEquals(1024, resolved.height)
}
```

- [ ] **Step 2: Extend `ImageParameters.resolve`**

Add an overload/parameter `capabilities: ImageModelCapabilities? = null` that clamps size to the manifest's max and falls back to `defaultSteps`/`defaultCfg` when the setting is null. Keep the remote `SdCppLimits` path unchanged.

- [ ] **Step 3: Wire `AppContainer`**

```kotlin
// localImageModelStore and imageModelsDir were added to AppContainer in Task 10
val sdTransport = BinderSdTransport(context.applicationContext)
val localImageGenerator = LocalImageGenerator(sdTransport, modelPath = { id ->
    localImageModelStore.getById(id)?.diffusionFile?.let { File(File(context.getExternalFilesDir(null), "models/image"), it).absolutePath }
})
val imageModelsDir = File(context.getExternalFilesDir(null), "models/image")
val imageJobRunner = ImageJobRunner(
    /* existing args */,
    localGenerator = localImageGenerator,
    localHandoffDir = File(context.cacheDir, "sd-handoff"),
)
val localImageScheduler = LocalImageScheduler(context.applicationContext)
val imageJobLauncher = ImageJobLauncher(
    /* existing args */,
    localImageModelStore = localImageModelStore,
    localScheduler = localImageScheduler,
)
```

- [ ] **Step 4: Show capabilities in the settings sheet**

When the local provider is selected, read the model's `ImageModelCapabilities` and hide/clamp controls: max size, sampler list, steps default, negative-prompt field (hidden when unsupported). Remote behaviour is unchanged.

- [ ] **Step 5: Run the full app unit suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS (all existing tests plus new ones).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/nutting/pocketllm/ui/chat/ImageParametersSheet.kt \
        app/src/main/java/dev/nutting/pocketllm/AppContainer.kt \
        app/src/test/java/dev/nutting/pocketllm/domain/image/ImageParametersCapabilitiesTest.kt
git commit -m "feat(image): capability-driven settings and local wiring"
```

---

### Task 13: Acceptance pass for M1

- [ ] **Step 1: Build and install**

```bash
./gradlew :app:assembleDebug :app:installDebug
```

- [ ] **Step 2: Walk the M1 acceptance criteria** (from the spec)

1. With no server profile, download an all-in-one SD1.5 GGUF in-app and generate from a prompt.
2. Progress + elapsed show on the pending reply; the image is saved and survives a restart.
3. Cancel stops within ~2 s and the reply says cancelled.
4. Background the app mid-generation and return: the notification tracks progress.
5. Kill the app mid-generation, reopen: the job reads interrupted; Retry works.
6. A model exceeding device RAM is refused before generation with a memory message.
7. Remote image servers and chat still behave as before.

- [ ] **Step 3: Commit any fixes, then update the spec's status to "M1 shipped"**

```bash
git add -A
git commit -m "fix(image): acceptance fixes for local image generation"
```

---

## Phase C — M2: img2img

### Task 14: JNI init image + strength

**Files:**
- Modify: `sd/src/main/cpp/sd_jni.cpp`
- Modify: `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngine.kt`
- Modify: `sd/src/main/java/dev/nutting/pocketllm/sd/SdJobRequest.kt`
- Modify: `sd/src/main/java/dev/nutting/pocketllm/sd/SdEngineService.kt`
- Test: `sd/src/test/java/dev/nutting/pocketllm/sd/SdJobRequestTest.kt`

- [ ] **Step 1: Extend the request test**

```kotlin
@Test
fun `given an init image then strength round-trips`() {
    val request = SdJobRequest(
        modelPath = "/m.gguf", prompt = "fox", width = 512, height = 512, steps = 8, cfg = 1f,
        seed = 1, count = 1, outDir = "/o", initImagePath = "/tmp/in.png", strength = 0.6f,
    )
    assertEquals(0.6f, json.decodeFromString<SdJobRequest>(json.encodeToString(request)).strength)
}
```

- [ ] **Step 2: Pass init image + strength through JNI**

Add two parameters to `SdNative.generate(...)` (`initImagePath: String?`, `strength: Float`) and in JNI load the file into an `sd_image_t` (via `stbi_load`), set `params.init_image` and `params.strength`, then free it after `generate_image`. Keep the existing call signature otherwise.

- [ ] **Step 3: Run `:sd` tests and build**

```bash
./gradlew :sd:testDebugUnitTest :sd:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add sd
git commit -m "feat(sd): img2img with init image and strength"
```

---

### Task 15: Local edit flow and Strength UI

**Files:**
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobLauncher.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/domain/image/ImageJobRunner.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ImageParametersSheet.kt`
- Modify: `app/src/main/java/dev/nutting/pocketllm/ui/chat/ImageParameters.kt` (strength setting)
- Test: `app/src/test/java/dev/nutting/pocketllm/domain/image/LocalImg2ImgTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `given a local model without img2img then edit is rejected before queuing`() = runTest {
    val model = imageModel(supportsImg2Img = false)
    val error = runCatching { launcher.editLocal("c", "prompt", listOf(dataUrl), model, strength = 0.6f) }
    assertTrue(error.exceptionOrNull() is EditNotSupportedException)
}
```

- [ ] **Step 2: Gate and persist strength**

- `ImageParameters`/`ResolvedImageParameters` already carry `strength` (added in Task 7); M2 adds the UI and the capability gate.
- `editLocal` throws `EditNotSupportedException` when `!model.capabilities.supportsImg2Img`.
- `ImageJobRunner.runLocal`: for `EDIT` jobs, write the first source image to a cache file and pass `initImagePath` + `strength` to the generator; for `GENERATE`, both are null.

- [ ] **Step 3: Add the Strength control**

In `ImageParametersSheet`, show a `0.1..1.0` slider labelled "Strength" only when the local provider is selected and an image is attached. Default 0.6. Persist it with the other image parameters.

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.nutting.pocketllm.domain.image.*'
```

Expected: PASS.

- [ ] **Step 5: Commit and run the M2 acceptance pass**

```bash
git add app
git commit -m "feat(image): local img2img with strength control"
```

Acceptance: attach a photo in Image mode with a local img2img-capable model, set Strength, receive an edited image; remote edit behaviour is unchanged.

---

## Self-review notes

- Spec coverage: architecture, `:sd` contract, manifest models, HF acquisition, "This device" UI, job flow, failure mapping (`out_of_memory` maps from `SdJobResult.error` containing "memory"), acceptance criteria, testing, M0/M1/M2. Deferred items have no tasks by design.
- Type consistency: `ImageDialect.LOCAL_SD`, `ImageTargets.DEVICE_ID`, `LocalImageModel.diffusionFile`, `ImageGenerator.generate(request, onEvent)`, and `SdJobRequest` are used consistently across tasks.
- Deliberate deviation from the spec's wording: `RemoteImageGenerator` covers the sd-server dialect only; the synchronous OpenAI-images path stays in `ImageJobRunner.runOpenAi` because it has no progress or cancel to abstract. This is recorded so it is a decision, not an oversight.
- `ImageJobRunner.runSdCpp` delegates its submit/poll loop to `RemoteImageGenerator` in Task 6 (including resume via `existingRemoteJobId`); the runner keeps the DB and file-save responsibilities.
