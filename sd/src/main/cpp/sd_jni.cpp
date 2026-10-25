#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include "stable-diffusion.h"

#define LOG_TAG "pocketsd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct State {
    sd_ctx_t* ctx = nullptr;
    std::atomic<bool> cancelled{false};
    JavaVM* vm = nullptr;
    jobject callback = nullptr;  // global ref to SdProgressListener
};
State g_state;

void progress_cb(int step, int steps, float /*time*/, void* /*data*/) {
    if (g_state.cancelled.load() || g_state.callback == nullptr) return;
    JNIEnv* env = nullptr;
    if (g_state.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    jclass cls = env->GetObjectClass(g_state.callback);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(III)V");
    if (mid != nullptr) env->CallVoidMethod(g_state.callback, mid, (jint)step, (jint)steps, (jint)0);
    env->DeleteLocalRef(cls);
}

jobjectArray to_image_array(JNIEnv* env, const sd_image_generation_result_t* result) {
    jclass byte_array_cls = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(result->count, byte_array_cls, nullptr);
    for (int i = 0; i < result->count; ++i) {
        const sd_image_t& image = result->items[i].image;
        const size_t pixels = (size_t)image.width * image.height * image.channel;
        jbyteArray bytes = env->NewByteArray((jsize)(16 + pixels));
        // Header: big-endian width, height, channels, reserved. Kotlin decodes and writes PNG.
        uint8_t header[16] = {0};
        uint32_t w = image.width, h = image.height, c = image.channel;
        for (int b = 0; b < 4; ++b) {
            header[b]     = (uint8_t)((w >> (24 - 8 * b)) & 0xFF);
            header[4 + b] = (uint8_t)((h >> (24 - 8 * b)) & 0xFF);
            header[8 + b] = (uint8_t)((c >> (24 - 8 * b)) & 0xFF);
        }
        env->SetByteArrayRegion(bytes, 0, 16, reinterpret_cast<const jbyte*>(header));
        if (image.data != nullptr && pixels > 0) {
            env->SetByteArrayRegion(bytes, 16, (jsize)pixels, reinterpret_cast<const jbyte*>(image.data));
        }
        env->SetObjectArrayElement(out, i, bytes);
        env->DeleteLocalRef(bytes);
    }
    env->DeleteLocalRef(byte_array_cls);
    return out;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_version(JNIEnv* env, jobject) {
    return env->NewStringUTF(sd_version());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_init(JNIEnv* env, jobject, jobject callback) {
    env->GetJavaVM(&g_state.vm);
    if (g_state.callback != nullptr) env->DeleteGlobalRef(g_state.callback);
    g_state.callback = callback != nullptr ? env->NewGlobalRef(callback) : nullptr;
    sd_set_progress_callback(progress_cb, nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_loadModel(JNIEnv* env, jobject, jstring path, jint threads, jstring backend) {
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    const char* backend_name = backend != nullptr ? env->GetStringUTFChars(backend, nullptr) : nullptr;

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = model_path;
    params.n_threads = threads;
    params.backend = backend_name;
    params.enable_mmap = false;
    params.flash_attn = true;

    g_state.ctx = new_sd_ctx(&params);
    if (g_state.ctx == nullptr) {
        LOGE("new_sd_ctx failed for %s (backend=%s)", model_path, backend_name ? backend_name : "auto");
    }

    if (backend_name != nullptr) env->ReleaseStringUTFChars(backend, backend_name);
    env->ReleaseStringUTFChars(path, model_path);
    return g_state.ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_unload(JNIEnv* /*env*/, jobject) {
    if (g_state.ctx != nullptr) {
        free_sd_ctx(g_state.ctx);
        g_state.ctx = nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_samplerIndex(JNIEnv* env, jobject, jstring name) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    enum sample_method_t method = str_to_sample_method(chars);
    env->ReleaseStringUTFChars(name, chars);
    return (jint)method;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_cancel(JNIEnv* /*env*/, jobject) {
    g_state.cancelled.store(true);
    if (g_state.ctx != nullptr) sd_cancel_generation(g_state.ctx, SD_CANCEL_ALL);
}

/**
 * Returns one self-describing byte[] per image: 16-byte big-endian header
 * (width, height, channels, reserved) followed by raw pixels. Kotlin encodes PNG.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_nutting_pocketllm_sd_SdNative_generate(
        JNIEnv* env, jobject, jstring prompt, jstring negative, jint width, jint height,
        jint steps, jfloat cfg, jlong seed, jint count, jint sampler_index) {
    g_state.cancelled.store(false);
    if (g_state.ctx == nullptr) return nullptr;

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    const char* negative_chars = negative != nullptr ? env->GetStringUTFChars(negative, nullptr) : nullptr;

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = prompt_chars;
    params.negative_prompt = negative_chars;
    params.width = width;
    params.height = height;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfg;
    params.seed = seed;
    params.batch_count = count;
    // The enum value order is stable enough for a pinned submodule; Kotlin maps names to indices.
    params.sample_params.sample_method = (enum sample_method_t)sampler_index;

    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(g_state.ctx, &params, &result);

    jobjectArray out = nullptr;
    if (ok && result != nullptr && result->count > 0 && !g_state.cancelled.load()) {
        out = to_image_array(env, result);
    }
    if (result != nullptr) free_sd_image_generation_result(result);

    env->ReleaseStringUTFChars(prompt, prompt_chars);
    if (negative_chars != nullptr) env->ReleaseStringUTFChars(negative, negative_chars);
    return out;
}
