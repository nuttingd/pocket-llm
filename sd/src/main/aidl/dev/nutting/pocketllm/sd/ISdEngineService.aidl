package dev.nutting.pocketllm.sd;

import dev.nutting.pocketllm.sd.ISdEngineCallback;

interface ISdEngineService {
    boolean load(String manifestJson);
    void unload();
    void submit(String jobId, String requestJson, ISdEngineCallback callback);
    void cancel(String jobId);
    String engineCapabilities();
}
