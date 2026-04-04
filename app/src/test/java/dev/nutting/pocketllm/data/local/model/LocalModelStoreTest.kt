package dev.nutting.pocketllm.data.local.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalModelStoreTest {

    private lateinit var store: LocalModelStore

    private val completedModel = LocalModel(
        id = "model-1",
        name = "Llama 3.2 3B",
        parameterCount = "3B",
        quantization = "Q4_K_M",
        modelFileName = "llama-3.2-3b.gguf",
        modelSizeBytes = 2_000_000_000L,
        downloadStatus = DownloadStatus.COMPLETE,
        downloadedBytes = 2_000_000_000L,
    )

    private val downloadingModel = LocalModel(
        id = "model-2",
        name = "Phi-4",
        parameterCount = "14B",
        quantization = "Q4_K_M",
        modelFileName = "phi-4.gguf",
        modelSizeBytes = 8_000_000_000L,
        downloadStatus = DownloadStatus.DOWNLOADING,
        downloadedBytes = 1_000_000_000L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        store = LocalModelStore(context)
    }

    @After
    fun tearDown() = runBlocking {
        // DataStore is a singleton per name — clean up all test data so tests don't bleed into each other
        store.delete("model-1")
        store.delete("model-2")
        store.delete("vision-1")
        store.setActiveModelId(null)
    }

    @Test
    fun `given no models saved when models collected then returns empty list`() = runTest {
        val models = store.models.first()
        assertTrue(models.isEmpty())
    }

    @Test
    fun `given model saved when models collected then returns saved model`() = runTest {
        store.save(completedModel)

        val models = store.models.first()

        assertEquals(1, models.size)
        assertEquals("model-1", models[0].id)
        assertEquals("Llama 3.2 3B", models[0].name)
        assertEquals(DownloadStatus.COMPLETE, models[0].downloadStatus)
    }

    @Test
    fun `given multiple models saved when models collected then returns all models`() = runTest {
        store.save(completedModel)
        store.save(downloadingModel)

        val models = store.models.first()

        assertEquals(2, models.size)
    }

    @Test
    fun `given model saved when same model saved again then replaces existing entry`() = runTest {
        store.save(completedModel)
        store.save(completedModel.copy(name = "Updated Name"))

        val models = store.models.first()

        assertEquals(1, models.size)
        assertEquals("Updated Name", models[0].name)
    }

    @Test
    fun `given model saved when deleted then removed from list`() = runTest {
        store.save(completedModel)
        store.save(downloadingModel)

        store.delete("model-1")

        val models = store.models.first()
        assertEquals(1, models.size)
        assertEquals("model-2", models[0].id)
    }

    @Test
    fun `given no active model when activeModelId collected then returns null`() = runTest {
        val activeId = store.activeModelId.first()
        assertNull(activeId)
    }

    @Test
    fun `given model saved when setActiveModelId called then activeModelId returns that id`() = runTest {
        store.save(completedModel)
        store.setActiveModelId("model-1")

        val activeId = store.activeModelId.first()
        assertEquals("model-1", activeId)
    }

    @Test
    fun `given active model set when that model deleted then activeModelId becomes null`() = runTest {
        store.save(completedModel)
        store.setActiveModelId("model-1")

        store.delete("model-1")

        val activeId = store.activeModelId.first()
        assertNull(activeId)
    }

    @Test
    fun `given downloading model when updateStatus called then status reflects new value`() = runTest {
        store.save(downloadingModel)

        store.updateStatus("model-2", DownloadStatus.COMPLETE, downloadingModel.modelSizeBytes)

        val models = store.models.first()
        val updated = models.first { it.id == "model-2" }
        assertEquals(DownloadStatus.COMPLETE, updated.downloadStatus)
    }

    @Test
    fun `given model with projector when saved then projector fields persisted correctly`() = runTest {
        val visionModel = completedModel.copy(
            id = "vision-1",
            projectorFileName = "mmproj-llava.gguf",
            projectorSizeBytes = 500_000_000L,
        )
        store.save(visionModel)

        val models = store.models.first()
        val saved = models.first()
        assertEquals("mmproj-llava.gguf", saved.projectorFileName)
        assertEquals(500_000_000L, saved.projectorSizeBytes)
        assertEquals(2_500_000_000L, saved.totalSizeBytes)
    }
}
