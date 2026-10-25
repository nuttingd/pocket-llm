package dev.nutting.pocketllm.sd;

oneway interface ISdEngineCallback {
    void onLoaded(boolean ok, String error);
    void onProgress(int step, int totalSteps, int elapsedMs);
    void onPreview(String path);
    void onFinished(in String[] imagePaths, String error);
}
