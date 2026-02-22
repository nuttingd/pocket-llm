#include <android/log.h>
#include <jni.h>
#include <string>
#include <sstream>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <signal.h>
#include <setjmp.h>

#include "llama.h"
#include "gguf.h"
#include "common.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <nlohmann/json.hpp>

#define LOG_TAG "PocketLLM"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 6;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int BATCH_SIZE = 512;

static llama_model   *g_model   = nullptr;
static llama_context *g_context = nullptr;
static mtmd_context  *g_mtmd    = nullptr;
static std::atomic<bool> g_cancel(false);
static std::mutex g_engine_mutex;

// ---- Signal handler guard ----
static thread_local sigjmp_buf  g_jmp_buf;
static thread_local bool        g_in_guarded_section = false;
static struct sigaction         g_old_sigsegv;
static struct sigaction         g_old_sigbus;
static std::atomic<bool>        g_handlers_installed(false);
static std::atomic<bool>        g_poisoned(false);

static void crash_handler(int sig, siginfo_t *info, void *ucontext) {
    if (g_in_guarded_section) {
        g_in_guarded_section = false;
        siglongjmp(g_jmp_buf, sig);
    }
    struct sigaction *old = (sig == SIGSEGV) ? &g_old_sigsegv : &g_old_sigbus;
    if (old->sa_flags & SA_SIGINFO) {
        old->sa_sigaction(sig, info, ucontext);
    } else if (old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    } else {
        struct sigaction dfl{};
        dfl.sa_handler = SIG_DFL;
        sigaction(sig, &dfl, nullptr);
        raise(sig);
    }
}

static void install_crash_handlers() {
    if (g_handlers_installed.exchange(true)) return;
    struct sigaction sa{};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags     = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &g_old_sigsegv);
    sigaction(SIGBUS,  &sa, &g_old_sigbus);
    LOGi("Native crash handlers installed");
}

static void poison_native_state() {
    g_poisoned.store(true);
    g_model   = nullptr;
    g_context = nullptr;
    g_mtmd    = nullptr;
    LOGe("Native state poisoned after crash — model must be reloaded");
}

static int get_n_threads() {
    return std::max(N_THREADS_MIN,
        std::min(N_THREADS_MAX, (int)sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
}

// ---- Init / Backend ----

extern "C"
JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeInit(JNIEnv *env, jobject, jobjectArray backendPaths) {
    install_crash_handlers();

    llama_log_set([](enum ggml_log_level level, const char *text, void *) {
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text); break;
            case GGML_LOG_LEVEL_WARN:  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, "%s", text); break;
            case GGML_LOG_LEVEL_INFO:  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, "%s", text); break;
            default:                   __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "%s", text); break;
        }
    }, nullptr);

    jsize count = env->GetArrayLength(backendPaths);
    LOGi("Loading %d ggml backends individually", count);
    for (jsize i = 0; i < count; i++) {
        auto jName = (jstring)env->GetObjectArrayElement(backendPaths, i);
        const char *name = env->GetStringUTFChars(jName, nullptr);
        LOGi("Loading backend: %s", name);
        auto *reg = ggml_backend_load(name);
        if (reg) {
            LOGi("Loaded backend: %s", ggml_backend_reg_name(reg));
        } else {
            LOGw("Failed to load backend: %s", name);
        }
        env->ReleaseStringUTFChars(jName, name);
        env->DeleteLocalRef(jName);
    }

    llama_backend_init();

    size_t dev_count = ggml_backend_dev_count();
    LOGi("Backend initialized: %zu devices registered", dev_count);
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char *dev_name = ggml_backend_dev_name(dev);
        const char *dev_desc = ggml_backend_dev_description(dev);
        enum ggml_backend_dev_type dev_type = ggml_backend_dev_type(dev);
        const char *type_str = dev_type == GGML_BACKEND_DEVICE_TYPE_GPU ? "GPU"
                             : dev_type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "IGPU"
                             : dev_type == GGML_BACKEND_DEVICE_TYPE_ACCEL ? "ACCEL" : "CPU";
        LOGi("  Device %zu: name=%s desc=%s type=%s", i, dev_name, dev_desc, type_str);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// ---- Model Load ----

extern "C"
JNIEXPORT jint JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeLoadModel(
    JNIEnv *env, jobject,
    jstring jModelPath, jstring jProjectorPath, jint nThreads, jint gpuOffloadPercent, jint contextSize
) {
    if (g_poisoned.load()) {
        LOGe("nativeLoadModel called while native state is poisoned");
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_engine_mutex);

    const auto *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    const auto *proj_path  = env->GetStringUTFChars(jProjectorPath, nullptr);
    LOGi("Loading model: %s", model_path);
    LOGi("Loading projector: %s", proj_path);

    // Load text model — offload layers to GPU based on percentage setting
    llama_model_params model_params = llama_model_default_params();
    int n_gpu_layers = -1;
    {
        gguf_init_params gguf_params = { .no_alloc = true, .ctx = nullptr };
        gguf_context *gguf_ctx = gguf_init_from_file(model_path, gguf_params);
        if (gguf_ctx) {
            int64_t arch_key = gguf_find_key(gguf_ctx, "general.architecture");
            if (arch_key >= 0) {
                const char *arch = gguf_get_val_str(gguf_ctx, arch_key);
                char block_key[128];
                snprintf(block_key, sizeof(block_key), "%s.block_count", arch);
                int64_t block_key_id = gguf_find_key(gguf_ctx, block_key);
                if (block_key_id >= 0) {
                    int32_t total_layers = (int32_t)gguf_get_val_u32(gguf_ctx, block_key_id);
                    n_gpu_layers = (int)(total_layers * gpuOffloadPercent / 100);
                    LOGi("Model has %d layers, offloading %d%% = %d layers to GPU",
                         total_layers, (int)gpuOffloadPercent, n_gpu_layers);
                }
            }
            gguf_free(gguf_ctx);
        }
    }
    model_params.n_gpu_layers = n_gpu_layers;
    auto *model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        LOGe("Failed to load model from %s", model_path);
        env->ReleaseStringUTFChars(jModelPath, model_path);
        env->ReleaseStringUTFChars(jProjectorPath, proj_path);
        return 1;
    }
    g_model = model;

    // Create context with user-configurable context size
    int threads = nThreads > 0 ? nThreads : get_n_threads();
    int ctx_size = contextSize > 0 ? contextSize : 2048;
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        LOGe("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(jModelPath, model_path);
        env->ReleaseStringUTFChars(jProjectorPath, proj_path);
        return 2;
    }

    // Init multimodal context (only if projector path is provided)
    if (proj_path && strlen(proj_path) > 0) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.n_threads = threads;
        mtmd_params.use_gpu = true;
        mtmd_params.warmup = true;
        mtmd_params.image_max_tokens = 512;

        g_mtmd = mtmd_init_from_file(proj_path, g_model, mtmd_params);
        if (!g_mtmd) {
            LOGe("Failed to init mtmd from %s", proj_path);
            llama_free(g_context);
            g_context = nullptr;
            llama_model_free(g_model);
            g_model = nullptr;
            env->ReleaseStringUTFChars(jModelPath, model_path);
            env->ReleaseStringUTFChars(jProjectorPath, proj_path);
            return 3;
        }
        LOGi("Multimodal projector loaded");
    } else {
        g_mtmd = nullptr;
        LOGi("No projector — text-only mode");
    }

    env->ReleaseStringUTFChars(jModelPath, model_path);
    env->ReleaseStringUTFChars(jProjectorPath, proj_path);
    LOGi("Model loaded successfully (threads=%d, ctx=%d)", threads, ctx_size);
    return 0;
}

// ---- Chat Inference ----

static void report_progress(JNIEnv *env, jobject thiz, jmethodID mid, const char *phase, int tokens, const char *text = "") {
    jstring jPhase = env->NewStringUTF(phase);
    jstring jText = env->NewStringUTF(text);
    env->CallVoidMethod(thiz, mid, jPhase, (jint)tokens, jText);
    env->DeleteLocalRef(jPhase);
    env->DeleteLocalRef(jText);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeInferChat(
    JNIEnv *env, jobject thiz,
    jstring jMessagesJson, jint maxTokens,
    jfloat temperature, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty
) {
    if (g_poisoned.load() || !g_model || !g_context) {
        return env->NewStringUTF("ERROR: model not loaded");
    }

    std::lock_guard<std::mutex> lock(g_engine_mutex);

    // Cache progress callback method
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID progressMid = env->GetMethodID(clazz, "onNativeProgress", "(Ljava/lang/String;ILjava/lang/String;)V");
    env->DeleteLocalRef(clazz);

    // ---- Begin guarded section ----
    g_in_guarded_section = true;
    int caught_signal = sigsetjmp(g_jmp_buf, 1);
    if (caught_signal != 0) {
        g_in_guarded_section = false;
        const char *sig_name = (caught_signal == SIGSEGV) ? "SIGSEGV" : "SIGBUS";
        LOGe("Caught %s during nativeInferChat — poisoning native state", sig_name);
        poison_native_state();
        jclass exc = env->FindClass("java/lang/RuntimeException");
        char msg[128];
        snprintf(msg, sizeof(msg),
                 "Native crash (%s) during inference. Model must be reloaded.", sig_name);
        env->ThrowNew(exc, msg);
        return nullptr;
    }

    g_cancel.store(false);

    // Parse messages JSON
    const auto *messages_str = env->GetStringUTFChars(jMessagesJson, nullptr);
    nlohmann::json messages;
    try {
        messages = nlohmann::json::parse(messages_str);
    } catch (const std::exception &e) {
        env->ReleaseStringUTFChars(jMessagesJson, messages_str);
        LOGe("Failed to parse messages JSON: %s", e.what());
        return env->NewStringUTF("ERROR: invalid messages JSON");
    }
    env->ReleaseStringUTFChars(jMessagesJson, messages_str);

    // Build chat messages array for template
    std::vector<llama_chat_message> chat_msgs;
    // Keep string storage alive
    std::vector<std::string> role_storage;
    std::vector<std::string> content_storage;

    for (const auto &msg : messages) {
        role_storage.push_back(msg["role"].get<std::string>());
        content_storage.push_back(msg["content"].get<std::string>());
    }
    for (size_t i = 0; i < role_storage.size(); i++) {
        chat_msgs.push_back({role_storage[i].c_str(), content_storage[i].c_str()});
    }

    // Apply chat template
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    std::string full_prompt;

    if (tmpl) {
        int32_t tmpl_len = llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), true, nullptr, 0);
        if (tmpl_len > 0) {
            full_prompt.resize(tmpl_len);
            llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), true, full_prompt.data(), full_prompt.size() + 1);
            LOGi("Applied chat template (%d chars)", tmpl_len);
        } else {
            // Fallback: concatenate messages
            std::ostringstream oss;
            for (const auto &msg : messages) {
                oss << msg["role"].get<std::string>() << ": " << msg["content"].get<std::string>() << "\n";
            }
            oss << "assistant: ";
            full_prompt = oss.str();
            LOGw("Chat template apply failed, using fallback format");
        }
    } else {
        // No template — use simple format
        std::ostringstream oss;
        for (const auto &msg : messages) {
            oss << msg["role"].get<std::string>() << ": " << msg["content"].get<std::string>() << "\n";
        }
        oss << "assistant: ";
        full_prompt = oss.str();
        LOGw("No chat template in model, using fallback format");
    }

    // Tokenize
    const auto *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens = common_tokenize(g_context, full_prompt, true, true);
    LOGi("Chat prompt tokenized: %zu tokens", tokens.size());

    // Check context overflow
    int n_ctx = llama_n_ctx(g_context);
    if ((int)tokens.size() >= n_ctx) {
        LOGe("Prompt (%zu tokens) exceeds context size (%d)", tokens.size(), n_ctx);
        g_in_guarded_section = false;
        return env->NewStringUTF("ERROR: context length exceeded");
    }

    // Eval prompt tokens in batches
    report_progress(env, thiz, progressMid, "prompt_eval", 0);
    llama_pos n_past = 0;
    int n_tokens = (int)tokens.size();

    for (int i = 0; i < n_tokens; i += BATCH_SIZE) {
        if (g_cancel.load()) {
            LOGi("Inference cancelled during prompt eval");
            llama_memory_clear(llama_get_memory(g_context), false);
            g_in_guarded_section = false;
            return env->NewStringUTF("");
        }

        int n_eval = std::min(BATCH_SIZE, n_tokens - i);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        for (int j = 0; j < n_eval; j++) {
            common_batch_add(batch, tokens[i + j], n_past + j, {0}, (i + j == n_tokens - 1));
        }
        if (llama_decode(g_context, batch) != 0) {
            LOGe("llama_decode failed during prompt eval at pos %d", i);
            llama_batch_free(batch);
            llama_memory_clear(llama_get_memory(g_context), false);
            g_in_guarded_section = false;
            return env->NewStringUTF("ERROR: prompt evaluation failed");
        }
        llama_batch_free(batch);
        n_past += n_eval;
    }

    // Set up sampling parameters
    report_progress(env, thiz, progressMid, "generating", 0);
    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_p = topP;
    sparams.top_k = topK;
    sparams.min_p = minP;
    sparams.penalty_repeat = repeatPenalty;

    LOGi("Sampling params: temp=%.2f top_p=%.2f top_k=%d min_p=%.2f repeat=%.2f",
         sparams.temp, sparams.top_p, sparams.top_k, sparams.min_p, sparams.penalty_repeat);

    // Token generation loop
    constexpr int PROGRESS_INTERVAL = 1; // report every token for streaming
    int max_tok = maxTokens > 0 ? maxTokens : 2048;

    common_sampler *sampler = common_sampler_init(g_model, sparams);
    std::ostringstream out;
    auto t_start = std::chrono::steady_clock::now();

    llama_batch batch = llama_batch_init(1, 0, 1);
    for (int i = 0; i < max_tok; i++) {
        if (g_cancel.load()) {
            LOGi("Inference cancelled at token %d", i);
            break;
        }

        llama_token new_token = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, new_token, true);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGd("EOG at token %d", i);
            break;
        }

        std::string piece = common_token_to_piece(g_context, new_token);
        out << piece;

        // Report every token for streaming UI
        auto now = std::chrono::steady_clock::now();
        double elapsed_s = std::chrono::duration<double>(now - t_start).count();
        double tok_per_s = elapsed_s > 0 ? (i + 1) / elapsed_s : 0;
        char phase_buf[64];
        snprintf(phase_buf, sizeof(phase_buf), "generating:%.1f", tok_per_s);
        report_progress(env, thiz, progressMid, phase_buf, i + 1, piece.c_str());

        common_batch_clear(batch);
        common_batch_add(batch, new_token, n_past++, {0}, true);
        if (llama_decode(g_context, batch) != 0) {
            LOGe("llama_decode failed at token %d", i);
            break;
        }
    }
    llama_batch_free(batch);
    common_sampler_free(sampler);

    // Clear KV cache for next inference
    llama_memory_clear(llama_get_memory(g_context), false);

    // ---- End guarded section ----
    g_in_guarded_section = false;

    std::string result = out.str();
    LOGi("Chat inference generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

// ---- Cancel ----

extern "C"
JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
    LOGi("Cancel requested");
}

// ---- Unload ----

extern "C"
JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeUnload(JNIEnv *, jobject) {
    if (g_poisoned.exchange(false)) {
        LOGi("Poisoned flag cleared — ready for fresh model load");
        return;
    }

    std::lock_guard<std::mutex> lock(g_engine_mutex);

    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGi("Model unloaded");
}

// ---- Info ----

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeDeviceInfo(JNIEnv *env, jobject) {
    bool has_gpu = false;
    std::string gpu_desc;
    size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU || type == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
            has_gpu = true;
            gpu_desc = ggml_backend_dev_description(dev);
            break;
        }
    }

    std::string result = has_gpu
        ? "GPU (" + gpu_desc + ")"
        : "CPU only (no GPU backend)";
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativePerfInfo(JNIEnv *env, jobject) {
    if (!g_context) return env->NewStringUTF("");

    llama_perf_context_data perf = llama_perf_context(g_context);

    double pp_tok_s = perf.n_p_eval > 0 ? (1000.0 * perf.n_p_eval / perf.t_p_eval_ms) : 0.0;
    double tg_tok_s = perf.n_eval > 0 ? (1000.0 * perf.n_eval / perf.t_eval_ms) : 0.0;

    char buf[256];
    snprintf(buf, sizeof(buf),
        "Prompt: %d tok, %.1f tok/s | Gen: %d tok, %.1f tok/s",
        perf.n_p_eval, pp_tok_s, perf.n_eval, tg_tok_s);
    return env->NewStringUTF(buf);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeModelName(JNIEnv *env, jobject) {
    if (!g_model) return env->NewStringUTF("");
    char buf[256] = {0};
    int len = llama_model_meta_val_str(g_model, "general.name", buf, sizeof(buf));
    if (len <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(buf);
}
