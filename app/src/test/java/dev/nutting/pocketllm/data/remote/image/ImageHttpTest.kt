package dev.nutting.pocketllm.data.remote.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageHttpTest {

    @Test
    fun `given the error shapes image servers send then the readable message and code are extracted`() {
        assertEquals(ErrorDetail("prompt required", null), errorDetail("""{"error":"prompt required"}"""))
        assertEquals(ErrorDetail("bad json", null), errorDetail("""{"error":"server_error","message":"bad json"}"""))
        assertEquals(ErrorDetail("Invalid size", "invalid_size"), errorDetail("""{"error":{"message":"Invalid size","code":"invalid_size"}}"""))
        assertEquals(ErrorDetail("model is loading", "model_loading"), errorDetail("""{"error":"model is loading","code":"model_loading"}"""))
        assertEquals(ErrorDetail("no edits", null), errorDetail("""{"detail":"no edits"}"""))
        assertEquals(ErrorDetail("field required", null), errorDetail("""{"detail":[{"loc":["body","prompt"],"msg":"field required"}]}"""))
        assertEquals(ErrorDetail("upstream timeout", null), errorDetail("upstream timeout"))
        assertEquals(ErrorDetail(null, null), errorDetail(""))
        assertEquals(ErrorDetail(null, null), errorDetail("x".repeat(600)))
    }

    @Test
    fun `given relative and absolute image urls then they resolve against the server`() {
        assertEquals("http://h:1/img/a.png", OpenAiImagesClient.resolve("http://h:1/v1", "/img/a.png"))
        assertEquals("http://h:1/v1/img/a.png", OpenAiImagesClient.resolve("http://h:1/v1/", "img/a.png"))
        assertEquals("https://cdn/x.png", OpenAiImagesClient.resolve("http://h:1", "https://cdn/x.png"))
    }
}
