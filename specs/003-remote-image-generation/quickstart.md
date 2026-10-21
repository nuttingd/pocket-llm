# Quickstart: sd-server with Qwen-Image-2.1 for Pocket LLM

Commands are taken from stable-diffusion.cpp `docs/qwen_image_2.1.md` and `examples/server/README.md` at `a77d362`. File names follow the Unsloth and Qwen Hugging Face repos.

## 1. Files

**Diffusion model.** Use one of:

- `unsloth/Qwen-Image-2.1-GGUF` → `qwen_image_2.1-Q4_K_M.gguf` (about 4.2 GB)
- the official Turbo weights, for 8-step generation

**VAE.** Only the Qwen-Image 2.1 VAE works: `qwen_image_2.1_vae_bf16.safetensors`.

**Text encoder.** `unsloth/Qwen3-VL-8B-Instruct-GGUF` → `Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf`.

**For editing** (Story 5), also get the matching `mmproj-…F16.gguf`.

## 2. Run (Turbo, 8 steps)

```sh
sd-server --listen-ip 0.0.0.0 --listen-port 8080 \
  --diffusion-model qwen_image_2.1-Q4_K_M.gguf \
  --vae qwen_image_2.1_vae_bf16.safetensors \
  --llm Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf \
  --llm_vision mmproj-Qwen3-VL-8B-Instruct-F16.gguf \
  --sigmas qwen-image-2.1-turbo --steps 8 --cfg-scale 1.0 --sampling-method euler \
  --diffusion-fa --offload-to-cpu -W 1024 -H 1024 -s -1
```

- **`-s -1`:** use a random seed by default. The app always sends a seed anyway.
- **`--llm_vision`:** enables editing.
- **`--offload-to-cpu`:** add it on GPUs with 16 GB or less.
- **Base model (not Turbo):** drop `--sigmas`, and use `--steps 20 --cfg-scale 6.0`.

## 3. Security

sd-server has **no authentication**. Keep it on your LAN or a VPN such as Tailscale or WireGuard. If you expose it more widely, put a reverse proxy that enforces a bearer token in front of it, and enter that token as the server's API key in the app.

## 4. Pocket LLM

1. Add a server with base URL `http://<host>:8080`. The app detects stable-diffusion.cpp and labels the server "Image server".
2. In a chat, pick `sd-cpp-local` (shown with an image badge) and type a prompt.
3. Optional: in Server settings, raise the image timeout if you use CPU offload or slow hardware. The default is 30 min.
