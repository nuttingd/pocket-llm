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
#include "prompt_cache.h"

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

// What the KV cache holds for sequence 0; reused across requests (see prompt_cache.h)
static PromptCache g_cache;

// First error llama.cpp logged since the last reset; explains why a model load failed
// (e.g. "unknown model architecture"). Log callbacks can come from any thread.
static std::mutex g_load_error_mutex;
static std::string g_load_error;

static void record_log_error(const char *text) {
    std::lock_guard<std::mutex> lock(g_load_error_mutex);
    if (!g_load_error.empty() || !text) return;
    g_load_error = text;
    while (!g_load_error.empty() && (g_load_error.back() == '\n' || g_load_error.back() == ' ')) g_load_error.pop_back();
}

static void reset_log_error() {
    std::lock_guard<std::mutex> lock(g_load_error_mutex);
    g_load_error.clear();
}

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
    g_cache.units.clear();
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
            case GGML_LOG_LEVEL_ERROR:
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
                record_log_error(text);
                break;
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
    reset_log_error();

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

    g_cache.units.clear();
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
        // Encode images on CPU: a long vision-encoder dispatch on a mobile GPU can't be preempted and
        // stalls UI rendering (the whole app stops responding) until it finishes
        mtmd_params.use_gpu = false;
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

// First llama.cpp error logged during the last model load, or "" if none
extern "C"
JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_llm_LlmEngine_nativeLastLoadError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_load_error_mutex);
    return env->NewStringUTF(g_load_error.c_str());
}

// ---- Chat Inference ----

// Return the number of leading bytes that form complete UTF-8 characters.
// Any trailing incomplete multi-byte sequence is excluded.
static size_t utf8_complete_length(const char *s, size_t len) {
    if (len == 0) return 0;
    // Walk backwards past continuation bytes (10xxxxxx)
    size_t i = len;
    while (i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) {
        --i;
    }
    if (i == 0) return 0; // all continuation bytes — incomplete
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    int expected;
    if ((lead & 0x80) == 0)        expected = 1;
    else if ((lead & 0xE0) == 0xC0) expected = 2;
    else if ((lead & 0xF0) == 0xE0) expected = 3;
    else if ((lead & 0xF8) == 0xF0) expected = 4;
    else return i - 1; // invalid lead byte — skip it
    int actual = static_cast<int>(len - (i - 1));
    // If all expected bytes are present the buffer ends on a complete boundary; return full length.
    // Otherwise truncate before the incomplete lead byte (position i-1) so callers never split a character.
    return actual >= expected ? len : i - 1;
}

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
    jstring jMessagesJson, jobjectArray jImages, jint maxTokens,
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

    // Copy encoded images (one per media marker in the message contents, in order)
    std::vector<std::vector<unsigned char>> image_bufs;
    const jsize n_images = jImages ? env->GetArrayLength(jImages) : 0;
    for (jsize i = 0; i < n_images; i++) {
        auto jImg = (jbyteArray) env->GetObjectArrayElement(jImages, i);
        const jsize len = env->GetArrayLength(jImg);
        std::vector<unsigned char> buf(len);
        env->GetByteArrayRegion(jImg, 0, len, reinterpret_cast<jbyte *>(buf.data()));
        env->DeleteLocalRef(jImg);
        image_bufs.push_back(std::move(buf));
    }

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

    const auto *vocab = llama_model_get_vocab(g_model);
    int n_ctx = llama_n_ctx(g_context);

    // Tokenize (text and media chunks), then evaluate only what isn't already in the KV cache
    PreparedPrompt prompt;
    if (const char *err = prepare_prompt(g_context, g_mtmd, full_prompt, image_bufs, prompt)) {
        LOGe("Failed to prepare prompt: %s", err);
        g_in_guarded_section = false;
        return env->NewStringUTF((std::string("ERROR: ") + err).c_str());
    }

    size_t n_prompt_tokens = prompt.n_tokens();
    LOGi("Chat prompt: %zu tokens in %zu units, %zu images", n_prompt_tokens, prompt.units.size(), image_bufs.size());
    if (prompt.units.empty() || (int)n_prompt_tokens >= n_ctx) {
        LOGe("Prompt (%zu tokens) is empty or exceeds context size (%d)", n_prompt_tokens, n_ctx);
        g_in_guarded_section = false;
        return env->NewStringUTF("ERROR: context length exceeded");
    }

    // Progress phases for the UI: "image:<index>:<count>" before an image is encoded, then
    // "prompt:<done>:<total>:<cached>" (KV tokens) as batches and images finish
    using clock = std::chrono::steady_clock;
    const auto t_eval_start = clock::now();
    auto t_image_start = t_eval_start;
    auto ms_since = [](clock::time_point t) {
        return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(clock::now() - t).count();
    };
    int last_image_started = 0;
    char phase_msg[96];
    auto on_progress = [&](const EvalProgress &p) {
        if (p.image_index > last_image_started) {
            last_image_started = p.image_index;
            t_image_start = clock::now();
            snprintf(phase_msg, sizeof(phase_msg), "image:%d:%d", p.image_index, p.image_count);
            report_progress(env, thiz, progressMid, phase_msg, 0);
            return;
        }
        if (p.image_index > 0 && p.image_index == last_image_started && t_image_start != t_eval_start) {
            LOGi("Image %d/%d encoded and evaluated in %lld ms", p.image_index, p.image_count, ms_since(t_image_start));
            t_image_start = t_eval_start;  // log each image once
        }
        snprintf(phase_msg, sizeof(phase_msg), "prompt:%zu:%zu:%zu", p.done_tokens, p.total_tokens, p.reused_tokens);
        report_progress(env, thiz, progressMid, phase_msg, (int) p.done_tokens);
    };

    llama_pos n_past = 0;
    EvalStats stats;
    EvalResult eval = eval_prompt_cached(g_context, g_mtmd, g_cache, prompt, BATCH_SIZE, g_cancel, n_past, stats,
                                         on_progress);
    if (stats.full_reset) LOGw("Partial KV removal unsupported; re-evaluated the full prompt");
    const long long eval_ms = ms_since(t_eval_start);
    LOGi("Prompt cache: reused %zu units, evaluated %zu (n_past %d) in %lld ms",
         stats.reused, stats.evaluated, (int)n_past, eval_ms);
    if (eval == EvalResult::Cancelled) {
        LOGi("Inference cancelled during prompt eval");
        g_in_guarded_section = false;
        return env->NewStringUTF("");
    }
    if (eval == EvalResult::Failed) {
        LOGe("Prompt evaluation failed");
        g_in_guarded_section = false;
        return env->NewStringUTF("ERROR: prompt evaluation failed");
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
    int max_tok = maxTokens > 0 ? maxTokens : 2048;

    common_sampler *sampler = common_sampler_init(g_model, sparams);
    std::ostringstream out;
    std::string utf8_buf; // Buffer for incomplete multi-byte UTF-8 sequences
    auto t_start = std::chrono::steady_clock::now();
    int n_generated = 0;

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

        // Buffer token bytes and only send complete UTF-8 to JNI
        utf8_buf += piece;
        size_t complete = utf8_complete_length(utf8_buf.c_str(), utf8_buf.size());

        auto now = std::chrono::steady_clock::now();
        double elapsed_s = std::chrono::duration<double>(now - t_start).count();
        double tok_per_s = elapsed_s > 0 ? (i + 1) / elapsed_s : 0;
        char phase_buf[64];
        snprintf(phase_buf, sizeof(phase_buf), "generating:%.1f", tok_per_s);

        if (complete > 0) {
            std::string to_send = utf8_buf.substr(0, complete);
            utf8_buf.erase(0, complete);
            report_progress(env, thiz, progressMid, phase_buf, i + 1, to_send.c_str());
        }

        common_batch_clear(batch);
        common_batch_add(batch, new_token, n_past++, {0}, true);
        if (llama_decode(g_context, batch) != 0) {
            LOGe("llama_decode failed at token %d", i);
            g_cache.reset(g_context);
            break;
        }
        cache_generated_token(g_cache, new_token);
        n_generated++;
    }
    llama_batch_free(batch);
    common_sampler_free(sampler);
    {
        double gen_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t_start).count();
        LOGi("Generated %d tokens in %.1f s (%.1f tok/s); prompt eval took %lld ms",
             n_generated, gen_s, gen_s > 0 ? n_generated / gen_s : 0.0, eval_ms);
    }

    // The KV cache is kept: the next request reuses its common prefix (see g_cache)

    // ---- End guarded section ----
    g_in_guarded_section = false;

    std::string result = out.str();
    // Trim any trailing incomplete UTF-8 sequence before passing to JNI
    size_t safe_len = utf8_complete_length(result.c_str(), result.size());
    if (safe_len < result.size()) {
        LOGw("Trimming %zu trailing incomplete UTF-8 bytes from result", result.size() - safe_len);
        result.resize(safe_len);
    }
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

    g_cache.units.clear();
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
