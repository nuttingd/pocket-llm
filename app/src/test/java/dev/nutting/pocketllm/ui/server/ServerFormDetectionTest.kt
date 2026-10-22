package dev.nutting.pocketllm.ui.server

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerKind
import dev.nutting.pocketllm.domain.image.ImageServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormDetectionTest {

    private val sdCpp = Detection.Found(ImageServerInfo(ImageDialect.SD_CPP))
    private val other = Detection.Found(ImageServerInfo(ImageDialect.OPENAI_IMAGES))

    @Test
    fun `given a new server when its URL is stable-diffusion cpp then it becomes an image server`() {
        assertEquals(ServerKind.IMAGE, EditingServer().withDetection(sdCpp).kind)
    }

    @Test
    fun `given the user chose chat when the URL is stable-diffusion cpp then their choice stands`() {
        val editing = EditingServer(kind = ServerKind.CHAT, kindChosenByUser = true)
        assertEquals(ServerKind.CHAT, editing.withDetection(sdCpp).kind)
    }

    @Test
    fun `given any other server or an unreachable one then the type is left alone`() {
        assertEquals(ServerKind.CHAT, EditingServer().withDetection(other).kind)
        assertEquals(ServerKind.IMAGE, EditingServer(kind = ServerKind.IMAGE).withDetection(Detection.Unreachable("timeout")).kind)
    }

    @Test
    fun `given an image server then only a non stable-diffusion cpp one needs a model`() {
        assertFalse(EditingServer(kind = ServerKind.IMAGE).withDetection(sdCpp).needsImageModel)
        assertTrue(EditingServer(kind = ServerKind.IMAGE).withDetection(other).needsImageModel)
        assertFalse(EditingServer(kind = ServerKind.CHAT).withDetection(other).needsImageModel)
    }
}
