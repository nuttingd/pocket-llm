# API Contracts: Image Generation Servers

**Branch**: `003-remote-image-generation` | **Date**: 2026-10-21

The app consumes these APIs; it doesn't serve them. Only the fields the app sends or reads are listed. `Authorization: Bearer {apiKey}` is sent when the server profile has a key. sd-server ignores it.

Sources: stable-diffusion.cpp `a77d362` `examples/server/api.md`, and openai-openapi `main`. See research.md.

---

## A. sd-server native dialect (`SD_CPP`)

### A1. Capability probe

```
GET {baseUrl}/sdcpp/v1/capabilities          timeout: 10 s
```

**200 response.** The app reads these fields:

```json
{
  "model": { "name": "qwen_image_2.1-Q4_K", "stem": "…", "path": "…" },
  "supported_modes": ["img_gen"],
  "defaults_by_mode": { "img_gen": { "width": 1024, "height": 1024, "seed": -1, "batch_count": 1,
     "negative_prompt": "", "sample_params": { "sample_method": "euler", "scheduler": "discrete",
     "sample_steps": 8, "guidance": { "txt_cfg": 1.0 } } } },
  "output_formats_by_mode": { "img_gen": ["png", "jpeg"] },
  "features_by_mode": { "img_gen": { "ref_images": true, "cancel_queued": true, "cancel_generating": false, "preview": true } },
  "samplers": ["euler", "euler_a", "…"],
  "schedulers": ["discrete", "…"],
  "preview_modes": ["none", "proj", "tae", "vae"],
  "limits": { "min_width": 64, "max_width": 4096, "min_height": 64, "max_height": 4096, "max_batch_count": 8, "max_queue_size": 64 }
}
```

**Decision rule.** The server is `SD_CPP` when the probe returns 200 and `supported_modes` contains `"img_gen"`. Any other status, a parse failure or a connection failure leaves the dialect unchanged. A 404 or 405 means `OPENAI_IMAGES`.

### A2. Submit a job

```
POST {baseUrl}/sdcpp/v1/img_gen              Content-Type: application/json   timeout: 30 s
```

```json
{
  "prompt": "a red fox in snow, watercolor",
  "negative_prompt": "blurry",          // omitted if unset
  "width": 1024, "height": 1024,        // always sent; multiples of 32, clamped to limits
  "seed": 1739201173,                   // always sent (server default is 42)
  "batch_count": 1,                     // omitted if 1
  "sample_params": {                    // object omitted when every child is unset
    "sample_method": "euler", "scheduler": "discrete", "sample_steps": 8,
    "guidance": { "txt_cfg": 1.0 }
  },
  "ref_images": ["data:image/jpeg;base64,…"],  // EDIT only (Qwen-Image edit uses reference images)
  "output_format": "png",
  "preview": "proj", "preview_interval": 1     // only when live preview is on and "proj" ∈ preview_modes
}
```

| Response | Meaning | App action |
|---|---|---|
| **202** `{"id":"job_…","kind":"img_gen","status":"queued","created":…,"poll_url":"/sdcpp/v1/jobs/job_…"}` | Accepted | Persist `remoteJobId`, then poll |
| 400 `{"error":"…"}` | Invalid parameters | FAILED. Show the server message |
| 429 | Queue full | FAILED: "Server queue is full". Retry offered |
| 503 `{"code":"model_loading"\|"model_not_loaded"}` | Server not ready | FAILED with a specific message |
| 500 | Server error | FAILED |

### A3. Poll a job

```
GET {baseUrl}/sdcpp/v1/jobs/{id}             timeout: 30 s
```

Poll every 1 s while `generating` and every 3 s while `queued`. On a transient error (IOException, 5xx), back off as 1, 2, 4, 8, 16 s, capped at 30 s. Give up after 2 minutes of consecutive failures. The overall deadline is `imageTimeoutSeconds`, counted from `createdAt`.

```json
{
  "id": "job_…", "kind": "img_gen",
  "status": "queued|generating|completed|failed|cancelled",
  "created": 1775401200000, "started": 1775401203000, "completed": null,   // epoch ms
  "queue_position": 0,
  "preview": { "pass": 1, "step": 5, "total_steps": 8, "b64_json": "iVBOR…" },
  "result": { "output_format": "png", "images": [ { "index": 0, "b64_json": "iVBOR…" } ] },
  "error": { "code": "generation_failed", "message": "…" }
}
```

| Response | App action |
|---|---|
| 200 `queued` | Status SUBMITTED. Record `queuePosition` |
| 200 `generating` | Status GENERATING. Record pass, step and total; write the preview when `step` changes |
| 200 `completed` | Status SAVING. Decode `result.images[*]` and write files plus rows, then SUCCEEDED |
| 200 `failed` | FAILED(server). Show `error.message` |
| 200 `cancelled` | CANCELLED |
| 404 / 410 | FAILED(interrupted): "The server no longer has this job (restarted, or the result expired)" |

### A4. Cancel a job

```
POST {baseUrl}/sdcpp/v1/jobs/{id}/cancel     timeout: 10 s
```

| Response | App action |
|---|---|
| 200 | CANCELLED |
| 409 | The job is already generating and can't be interrupted. CANCELLED locally with `errorCode=cancelled_server_busy`; the UI notes that the server will finish the current image |
| 404 / 410 | CANCELLED locally |

Any failure is logged at WARN; the local state is still CANCELLED.

### A5. Models

```
GET {baseUrl}/v1/models  →  {"data":[{"id":"sd-cpp-local","object":"model","owned_by":"local"}]}   or  {"data":[]} when no model is loaded
```

The `object` field is missing at the top level, so the existing `ModelsResponse` parser must not require it. The current parser already ignores unknown keys and has no `object` field, so this is OK.

---

## B. Generic OpenAI images dialect (`OPENAI_IMAGES`)

### B1. Generate

```
POST {baseUrl}/v1/images/generations         Content-Type: application/json   timeout: imageTimeoutSeconds, no automatic retry
```

```json
{ "model": "qwen-image", "prompt": "…", "n": 1, "size": "1024x1024", "response_format": "b64_json", "output_format": "png" }
```

**200 response:**

```json
{ "created": 1775401215, "output_format": "png", "data": [ { "b64_json": "iVBOR…" } ] }
```

If an item has `url` instead of `b64_json`, GET that URL (resolved against `baseUrl`) with the same auth header. If `output_format` is missing, sniff the magic bytes.

### B2. Edit

```
POST {baseUrl}/v1/images/edits               Content-Type: multipart/form-data   timeout: imageTimeoutSeconds
  model=<id>  prompt=<text>  n=<int>  size=<WxH>  output_format=png
  image[]=<file part, image/jpeg or image/png>   (repeated for each input image)
```

The response has the same shape as B1.

### B3. Errors

Every dialect normalises errors into `ImageApiException(status, message)`. The message is taken from the first of these that is present:

1. `error` as a string
2. `error.message`
3. `message`
4. `detail` as a string
5. `detail[0].msg`
6. The raw body, if it's under 500 characters

Status mapping:

- 401 / 403 → Unauthorized.
- 429 → RateLimited, without the chat path's automatic backoff.
- 503 with `model_loading` or `model_not_loaded` → ModelNotReady.
