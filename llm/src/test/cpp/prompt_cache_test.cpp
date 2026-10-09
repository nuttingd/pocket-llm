// Host test for prompt_cache.h. Not part of the Gradle/CI build (it needs model files).
//
// Runs a multi-turn chat twice with greedy sampling: once reusing the prompt cache across turns and once
// resetting it every turn. Each turn must reuse cached units and agree with the uncached run on the first
// token's top-1 (and report top-5 overlap). Exact text equality isn't asserted: batch shape alone shifts
// logits on small quantized models (decoding the same tokens in one batch vs. one at a time differs by ~1.0).
//
// Build (from the repo root, after `git submodule update --init external/llama.cpp`):
//   cmake -S external/llama.cpp -B /tmp/llama-host -G Ninja -DCMAKE_BUILD_TYPE=Release \
//         -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF
//   cmake --build /tmp/llama-host --target llama common mtmd
//   L=external/llama.cpp; B=/tmp/llama-host
//   g++ -std=c++17 -O2 -Illm/src/main/cpp -I$L/include -I$L/common -I$L/ggml/include -I$L/tools/mtmd \
//       -I$L/vendor llm/src/test/cpp/prompt_cache_test.cpp -o /tmp/prompt_cache_test $B/common/libcommon.a \
//       -L$B/bin -lmtmd -lllama -lggml -lggml-base -lggml-cpu -Wl,-rpath,$B/bin -lpthread
//
// Run (e.g. SmolLM2-135M-Instruct-Q8_0, SmolVLM-256M-Instruct-Q8_0 + its mmproj, two JPEGs):
//   /tmp/prompt_cache_test text.gguf vlm.gguf mmproj.gguf img1.jpg img2.jpg
#include "prompt_cache.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>

struct Msg { std::string role, content; };
struct Turn { std::string user; std::vector<std::string> images; std::string replace_first_user; };

static std::vector<unsigned char> read_file(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
}

static std::string apply_template(llama_model *model, const std::vector<Msg> &msgs) {
    std::vector<llama_chat_message> cm;
    for (auto &m : msgs) cm.push_back({m.role.c_str(), m.content.c_str()});
    const char *tmpl = llama_model_chat_template(model, nullptr);
    int32_t n = llama_chat_apply_template(tmpl, cm.data(), cm.size(), true, nullptr, 0);
    std::string out(n, '\0');
    llama_chat_apply_template(tmpl, cm.data(), cm.size(), true, out.data(), out.size() + 1);
    return out;
}

struct TurnResult { std::string text; EvalStats stats; std::vector<float> first_logits; };

static std::vector<TurnResult> run_chat(llama_model *model, llama_context *ctx, mtmd_context *mtmd,
                                        const std::vector<Turn> &turns, bool use_cache, int max_new) {
    PromptCache cache;
    cache.reset(ctx);
    std::atomic<bool> cancel(false);
    const auto *vocab = llama_model_get_vocab(model);
    const int n_vocab = llama_vocab_n_tokens(vocab);

    std::vector<Msg> msgs;
    std::vector<std::vector<unsigned char>> all_images;
    std::vector<TurnResult> results;
    for (const auto &t : turns) {
        std::string content = t.user;
        for (auto &img : t.images) {
            content += std::string("\n") + mtmd_default_marker();
            all_images.push_back(read_file(img));
        }
        msgs.push_back({"user", content});
        if (!t.replace_first_user.empty()) msgs[0].content = t.replace_first_user;  // edit history

        if (!use_cache) cache.reset(ctx);
        PreparedPrompt prompt;
        if (const char *err = prepare_prompt(ctx, mtmd, apply_template(model, msgs), all_images, prompt)) {
            std::cerr << "prepare failed: " << err << "\n";
            exit(1);
        }
        TurnResult r;
        llama_pos n_past = 0;
        EvalProgress last;
        int images_seen = 0;
        auto on_progress = [&](const EvalProgress &p) {
            if (p.image_index > images_seen) {
                if (p.image_index != images_seen + 1) { std::cerr << "image index skipped\n"; exit(1); }
                images_seen = p.image_index;
            }
            last = p;
        };
        if (eval_prompt_cached(ctx, mtmd, cache, prompt, 512, cancel, n_past, r.stats, on_progress) != EvalResult::Ok) {
            std::cerr << "eval failed\n";
            exit(1);
        }
        if (last.done_tokens != last.total_tokens || images_seen != last.image_count) {
            std::cerr << "progress incomplete: " << last.done_tokens << "/" << last.total_tokens << " tokens, "
                      << images_seen << "/" << last.image_count << " images\n";
            exit(1);
        }
        const float *logits = llama_get_logits_ith(ctx, -1);
        r.first_logits.assign(logits, logits + n_vocab);

        llama_sampler *smpl = llama_sampler_init_greedy();
        llama_batch batch = llama_batch_init(1, 0, 1);
        for (int i = 0; i < max_new; i++) {
            llama_token tok = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            r.text += common_token_to_piece(ctx, tok);
            common_batch_clear(batch);
            common_batch_add(batch, tok, n_past++, {0}, true);
            if (llama_decode(ctx, batch) != 0) { std::cerr << "decode failed\n"; exit(1); }
            cache_generated_token(cache, tok);
        }
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        msgs.push_back({"assistant", r.text});
        results.push_back(r);
    }
    return results;
}

static int compare(const char *name, llama_model *model, llama_context *ctx, mtmd_context *mtmd,
                   const std::vector<Turn> &turns) {
    auto fresh = run_chat(model, ctx, mtmd, turns, false, 40);
    auto cached = run_chat(model, ctx, mtmd, turns, true, 40);
    int failures = 0;
    std::cout << "== " << name << " ==\n";
    for (size_t i = 0; i < turns.size(); i++) {
        float max_diff = 0;
        for (size_t v = 0; v < fresh[i].first_logits.size(); v++)
            max_diff = std::max(max_diff, std::fabs(fresh[i].first_logits[v] - cached[i].first_logits[v]));
        auto top5 = [](const std::vector<float> &l) {
            std::vector<int> idx(l.size()); for (size_t k = 0; k < l.size(); k++) idx[k] = k;
            std::partial_sort(idx.begin(), idx.begin() + 5, idx.end(), [&](int a, int b) { return l[a] > l[b]; });
            idx.resize(5); return idx;
        };
        auto tf = top5(fresh[i].first_logits), tc = top5(cached[i].first_logits);
        int overlap = 0; for (int a : tf) for (int b : tc) overlap += a == b;
        bool top1 = tf[0] == tc[0];
        std::cout << "   top-1 " << (top1 ? "MATCH" : "MISMATCH") << ", top-5 overlap " << overlap << "/5"
                  << (cached[i].stats.reused < (i ? cached[i - 1].stats.reused + cached[i - 1].stats.evaluated : 0) ? " [partial seq_rm]" : "") << "\n";
        if (!top1) failures++;
        bool same = fresh[i].text == cached[i].text;
        std::cout << "turn " << i + 1 << ": cached reused " << cached[i].stats.reused << " units, evaluated "
                  << cached[i].stats.evaluated << " (fresh evaluated " << fresh[i].stats.evaluated << ")"
                  << " | first-token logit max diff " << max_diff << " | text " << (same ? "SAME" : "DIFFERENT")
                  << "\n   fresh:  " << fresh[i].text.substr(0, 120) << "\n";
        if (!same) std::cout << "   cached: " << cached[i].text.substr(0, 120) << "\n";
        if (i > 0 && cached[i].stats.reused == 0) { std::cout << "   FAIL: no reuse on turn " << i + 1 << "\n"; failures++; }
        // Exact text equality isn't required: batch shape alone shifts logits on small quantized models
    }
    return failures;
}

int main(int argc, char **argv) {
    if (argc < 3) { std::cerr << "usage: text_model vlm_model mmproj image1 image2\n"; return 2; }
    llama_backend_init();
    int failures = 0;

    auto make_ctx = [](llama_model *m) {
        auto p = llama_context_default_params();
        p.n_ctx = 4096; p.n_batch = 512; p.n_ubatch = 512;
        p.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        return llama_init_from_model(m, p);
    };

    {
        llama_model *model = llama_model_load_from_file(argv[1], llama_model_default_params());
        llama_context *ctx = make_ctx(model);
        failures += compare("text: SmolLM2-135M", model, ctx, nullptr, {
            {"What is the capital of France?", {}},
            {"And what river runs through it?", {}},
            {"Name one famous museum there.", {}},
            {"Summarize our chat in one sentence.", {}, "What is the capital of Italy?"},
        });
        llama_free(ctx);
        llama_model_free(model);
    }
    if (argc >= 6) {
        llama_model *model = llama_model_load_from_file(argv[2], llama_model_default_params());
        llama_context *ctx = make_ctx(model);
        auto mp = mtmd_context_params_default();
        mp.use_gpu = false;
        mp.image_max_tokens = 512;
        mtmd_context *mtmd = mtmd_init_from_file(argv[3], model, mp);
        failures += compare("vision: SmolVLM-256M", model, ctx, mtmd, {
            {"Describe this image briefly.", {argv[4]}},
            {"What colors stand out?", {}},
            {"Now describe this second image.", {argv[5]}},
        });
        mtmd_free(mtmd);
        llama_free(ctx);
        llama_model_free(model);
    }
    std::cout << (failures ? "FAILED" : "ALL PASSED") << " (" << failures << " failures)\n";
    return failures ? 1 : 0;
}
