package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.data.remote.model.ContentPart
import dev.nutting.pocketllm.data.remote.model.ImageUrl
import dev.nutting.pocketllm.llm.LlmEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LocalLlmClientPromptTest {

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
    private val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes)

    private fun imageMessage() = ChatMessage(
        role = "user",
        content = ChatContent.Parts(listOf(
            ContentPart.TextPart("describe"),
            ContentPart.ImagePart(ImageUrl(url = dataUrl)),
        )),
    )

    private fun contents(json: String) =
        Json.parseToJsonElement(json).jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }

    @Test
    fun `given vision model when image part present then marker is inserted and bytes collected`() {
        val prompt = LocalLlmClient.buildPrompt(listOf(imageMessage()), visionEnabled = true)

        assertEquals(listOf("describe\n${LlmEngine.MEDIA_MARKER}"), contents(prompt.messagesJson))
        assertEquals(1, prompt.images.size)
        assertArrayEquals(jpegBytes, prompt.images[0])
    }

    @Test
    fun `given text only model when image part present then placeholder text and no images`() {
        val prompt = LocalLlmClient.buildPrompt(listOf(imageMessage()), visionEnabled = false)

        assertEquals(listOf("describe\n[image]"), contents(prompt.messagesJson))
        assertTrue(prompt.images.isEmpty())
    }

    @Test
    fun `only the latest user message images are sent, earlier ones become placeholders`() {
        val second = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        val messages = listOf(
            imageMessage(),
            ChatMessage(role = "assistant", content = ChatContent.Text("a photo")),
            ChatMessage(role = "user", content = ChatContent.Parts(listOf(
                ContentPart.ImagePart(ImageUrl(url = second)),
                ContentPart.ImagePart(ImageUrl(url = dataUrl)),
            ))),
        )

        val prompt = LocalLlmClient.buildPrompt(messages, visionEnabled = true)

        assertEquals(2, prompt.images.size)
        assertArrayEquals(byteArrayOf(9, 9), prompt.images[0])
        assertArrayEquals(jpegBytes, prompt.images[1])
        val contents = contents(prompt.messagesJson)
        assertEquals("describe\n[image]", contents[0])
        assertEquals("${LlmEngine.MEDIA_MARKER}\n${LlmEngine.MEDIA_MARKER}", contents[2])
    }

    @Test
    fun `images in an earlier turn are not sent when the latest user message is text only`() {
        val messages = listOf(
            imageMessage(),
            ChatMessage(role = "assistant", content = ChatContent.Text("a photo")),
            ChatMessage(role = "user", content = ChatContent.Text("what color is it?")),
        )

        val prompt = LocalLlmClient.buildPrompt(messages, visionEnabled = true)

        assertTrue(prompt.images.isEmpty())
        assertEquals("describe\n[image]", contents(prompt.messagesJson)[0])
    }

    @Test
    fun `non data urls are not decoded`() {
        assertNull(LocalLlmClient.decodeDataUrl("https://example.com/cat.jpg"))
        assertNull(LocalLlmClient.decodeDataUrl("data:image/jpeg,notbase64"))
        assertNull(LocalLlmClient.decodeDataUrl("data:image/jpeg;base64,***"))
    }
}
