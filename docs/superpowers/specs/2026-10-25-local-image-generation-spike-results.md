# M0 spike results

Status: **not yet run** — requires the Pixel 9 Pro connected over adb and an SD1.5 GGUF on device.

Device: Pixel 9 Pro (Tensor G4, 16 GB, Mali-G715)
sd.cpp pin: `0f76bf5c1ec8944b24997617f20a8eada33ad9de`
Vulkan build: **pass** (`libpocketsd.so` + `libstable-diffusion.so` link ggml-vulkan; `vkCreateInstance` present)

| Check | Result | Notes |
|---|---|---|
| Vulkan build for arm64-v8a | pass | |
| SD1.5 256x256 / 8 steps | | |
| SD1.5 512x512 / 20 steps | | |
| SDXL-Turbo 1024x1024 / 4 steps | | |
| Cancel latency | | target ~2 s |
| Kill `:sd` mid-job, next job works | | |
| Peak RSS | | |
