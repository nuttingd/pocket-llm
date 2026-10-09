// Prompt (prefix) caching for chat inference.
//
// The KV cache for sequence 0 is kept between requests. `PromptCache` records what it holds, one unit per
// text token or media chunk, in order. A new request evaluates only what follows the longest common prefix
// with the previous one, so earlier turns (and their images) aren't re-processed.
//
// Kept free of JNI/Android dependencies so it can be exercised by a host test.
#pragma once

#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

struct CacheUnit {
    llama_token token = -1;     // text token; -1 for a media chunk
    std::string media_id;       // media chunk id (empty for text)
    llama_pos   n_pos = 1;      // positions the unit advances (M-RoPE images: not equal to n_tokens)
    size_t      n_tokens = 1;   // KV cells the unit occupies

    bool operator==(const CacheUnit &o) const {
        return token == o.token && media_id == o.media_id && n_pos == o.n_pos && n_tokens == o.n_tokens;
    }
};

struct PromptCache {
    std::vector<CacheUnit> units;

    void reset(llama_context *ctx) {
        units.clear();
        if (ctx) llama_memory_clear(llama_get_memory(ctx), false);
    }
};

// FNV-1a over the encoded image, used as the media chunk id (same idea as llama-server)
inline std::string media_hash(const unsigned char *data, size_t len) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < len; i++) { h ^= data[i]; h *= 0x100000001b3ULL; }
    char buf[17];
    snprintf(buf, sizeof(buf), "%016llx", (unsigned long long) h);
    return buf;
}

// A tokenized prompt split into cache units. Owns the mtmd chunks its media units point into.
struct PreparedPrompt {
    std::vector<CacheUnit> units;
    std::vector<const mtmd_input_chunk *> unit_chunk;  // media chunk for media units, nullptr for text
    mtmd_input_chunks *chunks = nullptr;

    PreparedPrompt() = default;
    PreparedPrompt(const PreparedPrompt &) = delete;
    PreparedPrompt &operator=(const PreparedPrompt &) = delete;
    ~PreparedPrompt() { if (chunks) mtmd_input_chunks_free(chunks); }

    size_t n_tokens() const {
        size_t n = 0;
        for (const auto &u : units) n += u.n_tokens;
        return n;
    }

    void add_text(llama_token t) {
        units.push_back({t, "", 1, 1});
        unit_chunk.push_back(nullptr);
    }
};

// Tokenizes `prompt`. With images, each media marker in the prompt is replaced by one image (in order) via
// mtmd; `mtmd` must then be non-null. Returns nullptr on success, otherwise an error message.
inline const char *prepare_prompt(llama_context *ctx, mtmd_context *mtmd, const std::string &prompt,
                                  const std::vector<std::vector<unsigned char>> &images, PreparedPrompt &out) {
    if (images.empty()) {
        for (llama_token t : common_tokenize(ctx, prompt, true, true)) out.add_text(t);
        return nullptr;
    }
    if (!mtmd) return "images require a vision model with a projector";

    std::vector<mtmd_bitmap *> bitmaps;
    auto free_bitmaps = [&]() { for (auto *b : bitmaps) mtmd_bitmap_free(b); };
    for (const auto &buf : images) {
        mtmd_bitmap *bmp = mtmd_helper_bitmap_init_from_buf(mtmd, buf.data(), buf.size());
        if (!bmp) {
            free_bitmaps();
            return "failed to decode image";
        }
        mtmd_bitmap_set_id(bmp, media_hash(buf.data(), buf.size()).c_str());
        bitmaps.push_back(bmp);
    }

    mtmd_input_text text{prompt.c_str(), /* add_special */ true, /* parse_special */ true};
    out.chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap *> bitmap_ptrs(bitmaps.begin(), bitmaps.end());
    int32_t rc = mtmd_tokenize(mtmd, out.chunks, &text, bitmap_ptrs.data(), bitmap_ptrs.size());
    free_bitmaps();
    if (rc != 0) {
        return rc == 1 ? "image count does not match image markers in prompt" : "failed to preprocess image";
    }

    for (size_t c = 0; c < mtmd_input_chunks_size(out.chunks); c++) {
        const mtmd_input_chunk *chunk = mtmd_input_chunks_get(out.chunks, c);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n = 0;
            const llama_token *toks = mtmd_input_chunk_get_tokens_text(chunk, &n);
            for (size_t t = 0; t < n; t++) out.add_text(toks[t]);
        } else {
            out.units.push_back({-1, mtmd_input_chunk_get_id(chunk),
                                 mtmd_input_chunk_get_n_pos(chunk), mtmd_input_chunk_get_n_tokens(chunk)});
            out.unit_chunk.push_back(chunk);
        }
    }
    return nullptr;
}

enum class EvalResult { Ok, Cancelled, Failed };

struct EvalStats {
    size_t reused = 0;        // units taken from the cache
    size_t evaluated = 0;     // units evaluated this call
    bool   full_reset = false; // partial KV removal unsupported; cache was cleared
};

// Evaluates `prompt` into the KV cache, reusing its longest common prefix with `cache`. At least the last
// unit is always evaluated so its logits are available for sampling. On success `n_past` is the position
// after the prompt. On cancel or failure the cache is reset.
inline EvalResult eval_prompt_cached(llama_context *ctx, mtmd_context *mtmd, PromptCache &cache,
                                     const PreparedPrompt &prompt, int batch_size,
                                     const std::atomic<bool> &cancel, llama_pos &n_past, EvalStats &stats) {
    const auto &units = prompt.units;
    if (units.empty()) return EvalResult::Failed;

    size_t n_keep = 0;
    while (n_keep < cache.units.size() && n_keep < units.size() && cache.units[n_keep] == units[n_keep]) n_keep++;
    if (n_keep == units.size()) n_keep--;

    n_past = 0;
    for (size_t i = 0; i < n_keep; i++) n_past += units[i].n_pos;

    auto *mem = llama_get_memory(ctx);
    if (n_keep < cache.units.size() && !llama_memory_seq_rm(mem, 0, n_past, -1)) {
        // Some memory types (e.g. recurrent/hybrid models) can't drop a partial sequence
        llama_memory_clear(mem, false);
        stats.full_reset = true;
        n_keep = 0;
        n_past = 0;
    }
    cache.units.resize(n_keep);
    stats.reused = n_keep;
    stats.evaluated = units.size() - n_keep;

    const size_t last = units.size() - 1;
    size_t ui = n_keep;
    while (ui < units.size()) {
        if (cancel.load()) {
            cache.reset(ctx);
            return EvalResult::Cancelled;
        }

        if (prompt.unit_chunk[ui]) {
            llama_pos new_n_past = n_past;
            if (mtmd_helper_eval_chunk_single(mtmd, ctx, prompt.unit_chunk[ui], n_past, 0, batch_size,
                                              /* logits_last */ ui == last, &new_n_past) != 0) {
                cache.reset(ctx);
                return EvalResult::Failed;
            }
            n_past = new_n_past;
            cache.units.push_back(units[ui]);
            ui++;
            continue;
        }

        size_t run_end = ui;
        while (run_end < units.size() && !prompt.unit_chunk[run_end] && run_end - ui < (size_t) batch_size) run_end++;
        const int n_eval = (int) (run_end - ui);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        for (int j = 0; j < n_eval; j++) {
            common_batch_add(batch, units[ui + j].token, n_past + j, {0}, ui + j == last);
        }
        const int rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            cache.reset(ctx);
            return EvalResult::Failed;
        }
        for (int j = 0; j < n_eval; j++) cache.units.push_back(units[ui + j]);
        n_past += n_eval;
        ui = run_end;
    }
    return EvalResult::Ok;
}

// Records a generated token after it has been decoded at the cache's next position.
inline void cache_generated_token(PromptCache &cache, llama_token token) {
    cache.units.push_back({token, "", 1, 1});
}
