package dev.nutting.pocketllm.ui.modelmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelManagementViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `given non-existent file when isValidGguf called then returns false`() {
        val file = File(tempFolder.root, "missing.gguf")
        assertFalse(ModelManagementViewModel.isValidGguf(file))
    }

    @Test
    fun `given empty file when isValidGguf called then returns false`() {
        val file = tempFolder.newFile("empty.gguf")
        assertFalse(ModelManagementViewModel.isValidGguf(file))
    }

    @Test
    fun `given file with only 3 bytes when isValidGguf called then returns false`() {
        val file = tempFolder.newFile("short.gguf")
        // 3 bytes — below the 4-byte minimum for a valid magic number
        file.writeBytes(byteArrayOf(0x47, 0x47, 0x55))
        assertFalse(ModelManagementViewModel.isValidGguf(file))
    }

    @Test
    fun `given valid GGUF magic bytes when isValidGguf called then returns true`() {
        // GGUF files start with bytes [0x47, 0x47, 0x55, 0x46] = "GGUF" in ASCII (little-endian magic 0x46554747)
        val file = tempFolder.newFile("valid.gguf")
        file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x01, 0x00, 0x00, 0x00))
        assertTrue(ModelManagementViewModel.isValidGguf(file))
    }

    @Test
    fun `given wrong magic bytes when isValidGguf called then returns false`() {
        val file = tempFolder.newFile("not_gguf.bin")
        // ZIP file magic: PK\x03\x04
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        assertFalse(ModelManagementViewModel.isValidGguf(file))
    }

    @Test
    fun `given file with exactly 4 bytes of valid magic when isValidGguf called then returns true`() {
        val file = tempFolder.newFile("minimal.gguf")
        file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        assertTrue(ModelManagementViewModel.isValidGguf(file))
    }
}
