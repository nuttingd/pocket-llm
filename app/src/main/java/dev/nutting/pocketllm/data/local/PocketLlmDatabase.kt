package dev.nutting.pocketllm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.dao.ConversationDao
import dev.nutting.pocketllm.data.local.dao.MessageDao
import dev.nutting.pocketllm.data.local.dao.ServerProfileDao
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity

@Database(
    entities = [
        ServerProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        CompactionSummaryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PocketLlmDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun compactionSummaryDao(): CompactionSummaryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS compaction_summaries (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationId TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        compactedMessageCount INTEGER NOT NULL,
                        insertedBeforeMessageId TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compaction_summaries_conversationId ON compaction_summaries(conversationId)")
            }
        }

        fun create(context: Context): PocketLlmDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PocketLlmDatabase::class.java,
                "pocket_llm.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
