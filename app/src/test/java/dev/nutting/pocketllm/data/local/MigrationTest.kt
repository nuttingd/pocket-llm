package dev.nutting.pocketllm.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PocketLlmDatabase::class.java)

    @Test
    fun `given v6 database with a conversation when migrated to v7 then data is intact and image tables accept rows`() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO server_profiles (id, name, baseUrl, hasApiKey, requestTimeoutSeconds, createdAt, updatedAt) VALUES ('s1', 'Server', 'http://h', 0, 120, 1, 1)")
            db.execSQL("INSERT INTO conversations (id, title, lastServerProfileId, createdAt, updatedAt) VALUES ('c1', 'Chat', 's1', 1, 1)")
            db.execSQL("INSERT INTO messages (id, conversationId, role, content, depth, childCount, createdAt) VALUES ('m1', 'c1', 'user', 'hello', 0, 0, 1)")
        }

        val db = helper.runMigrationsAndValidate(DB, 7, true, PocketLlmDatabase.MIGRATION_6_7)

        db.query("SELECT imageTimeoutSeconds FROM server_profiles WHERE id = 's1'").use { c ->
            c.moveToFirst()
            assertEquals(1800, c.getInt(0))
        }
        db.query("SELECT title, imageParamsJson FROM conversations WHERE id = 'c1'").use { c ->
            c.moveToFirst()
            assertEquals("Chat", c.getString(0))
            assertNull(c.getString(1))
        }
        db.query("SELECT content FROM messages WHERE id = 'm1'").use { c ->
            c.moveToFirst()
            assertEquals("hello", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO image_jobs (id, conversationId, messageId, serverId, modelId, dialect, kind, prompt, paramsJson, status, createdAt) " +
                "VALUES ('j1', 'c1', 'm1', 's1', 'sd-cpp-local', 'SD_CPP', 'GENERATE', 'a fox', '{}', 'QUEUED_LOCAL', 1)"
        )
        db.execSQL(
            "INSERT INTO generated_images (id, messageId, idx, filePath, mimeType, width, height, prompt, paramsJson, modelId, createdAt) " +
                "VALUES ('g1', 'm1', 0, 'images/c1/m1_0.png', 'image/png', 1024, 1024, 'a fox', '{}', 'sd-cpp-local', 1)"
        )
        db.execSQL("INSERT INTO model_classifications (serverId, modelId, isImageModel) VALUES ('s1', 'flux', 1)")
        db.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
