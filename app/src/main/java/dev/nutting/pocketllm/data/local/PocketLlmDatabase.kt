package dev.nutting.pocketllm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Fts4
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.dao.ConversationDao
import dev.nutting.pocketllm.data.local.dao.MessageDao
import dev.nutting.pocketllm.data.local.dao.ServerProfileDao
import dev.nutting.pocketllm.data.local.dao.ParameterPresetDao
import dev.nutting.pocketllm.data.local.dao.ToolDefinitionDao
import dev.nutting.pocketllm.data.local.dao.DocumentDao
import dev.nutting.pocketllm.data.local.dao.DocumentContentDao
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ConversationToolEnabledEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.MessageFts
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import dev.nutting.pocketllm.data.local.entity.DocumentEntity
import dev.nutting.pocketllm.data.local.entity.DocumentContentEntity
import dev.nutting.pocketllm.data.local.entity.DocumentFts

@Database(
    entities = [
        ServerProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        CompactionSummaryEntity::class,
        MessageFts::class,
        ToolDefinitionEntity::class,
        ConversationToolEnabledEntity::class,
        ParameterPresetEntity::class,
        DocumentEntity::class,
        DocumentContentEntity::class,
        DocumentFts::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class PocketLlmDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun compactionSummaryDao(): CompactionSummaryDao
    abstract fun toolDefinitionDao(): ToolDefinitionDao
    abstract fun parameterPresetDao(): ParameterPresetDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentContentDao(): DocumentContentDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(`content`, content=`messages`)"
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END"""
                )
                db.execSQL("INSERT INTO `message_fts`(`message_fts`) VALUES ('rebuild')")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `tool_definitions` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `parametersSchemaJson` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL DEFAULT 0,
                        `isEnabledByDefault` INTEGER NOT NULL DEFAULT 1
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `conversation_tool_enabled` (
                        `conversationId` TEXT NOT NULL,
                        `toolDefinitionId` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(`conversationId`, `toolDefinitionId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`toolDefinitionId`) REFERENCES `tool_definitions`(`id`) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_tool_enabled_conversationId` ON `conversation_tool_enabled`(`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_tool_enabled_toolDefinitionId` ON `conversation_tool_enabled`(`toolDefinitionId`)")
                seedBuiltInTools(db)
            }
        }

        private fun seedBuiltInTools(db: SupportSQLiteDatabase) {
            db.execSQL(
                """INSERT OR IGNORE INTO tool_definitions (id, name, description, parametersSchemaJson, isBuiltIn, isEnabledByDefault)
                VALUES ('builtin-calculator', 'calculator', 'Evaluate a mathematical expression',
                '{"type":"object","properties":{"expression":{"type":"string","description":"The math expression to evaluate"}},"required":["expression"]}',
                1, 1)"""
            )
            db.execSQL(
                """INSERT OR IGNORE INTO tool_definitions (id, name, description, parametersSchemaJson, isBuiltIn, isEnabledByDefault)
                VALUES ('builtin-web-fetch', 'web_fetch', 'Fetch the content of a URL',
                '{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch"}},"required":["url"]}',
                1, 0)"""
            )
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `parameter_presets` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL DEFAULT 0,
                        `temperature` REAL,
                        `maxTokens` INTEGER,
                        `topP` REAL,
                        `frequencyPenalty` REAL,
                        `presencePenalty` REAL
                    )"""
                )
                seedBuiltInPresets(db)
            }
        }

        private fun seedBuiltInPresets(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO parameter_presets (id, name, isBuiltIn, temperature, maxTokens, topP, frequencyPenalty, presencePenalty) VALUES ('preset-creative', 'Creative', 1, 1.2, 2048, 0.95, 0.3, 0.3)")
            db.execSQL("INSERT OR IGNORE INTO parameter_presets (id, name, isBuiltIn, temperature, maxTokens, topP, frequencyPenalty, presencePenalty) VALUES ('preset-precise', 'Precise', 1, 0.2, 2048, 0.5, 0.0, 0.0)")
            db.execSQL("INSERT OR IGNORE INTO parameter_presets (id, name, isBuiltIn, temperature, maxTokens, topP, frequencyPenalty, presencePenalty) VALUES ('preset-code', 'Code', 1, 0.1, 4096, 0.9, 0.0, 0.0)")
            db.execSQL("INSERT OR IGNORE INTO parameter_presets (id, name, isBuiltIn, temperature, maxTokens, topP, frequencyPenalty, presencePenalty) VALUES ('preset-balanced', 'Balanced', 1, 0.7, 2048, 1.0, 0.0, 0.0)")
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate FTS table with simple tokenizer (unicode61 not available on all devices)
                db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_message_fts_BEFORE_UPDATE")
                db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_message_fts_BEFORE_DELETE")
                db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_message_fts_AFTER_UPDATE")
                db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_message_fts_AFTER_INSERT")
                db.execSQL("DROP TABLE IF EXISTS `message_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(`content`, content=`messages`)"
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `message_fts` WHERE `docid`=OLD.`rowid`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `message_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END"""
                )
                db.execSQL("INSERT INTO `message_fts`(`message_fts`) VALUES ('rebuild')")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create documents table (metadata)
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `documents` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `source_filename` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `word_count` INTEGER NOT NULL DEFAULT 0,
                        `size_bytes` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )"""
                )

                // Create document_content table
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `document_content` (
                        `document_id` TEXT NOT NULL PRIMARY KEY,
                        `content` TEXT NOT NULL,
                        FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON DELETE CASCADE
                    )"""
                )

                // Create FTS4 virtual table for search indexing
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `document_fts` USING FTS4(`content`, content=`document_content`)"
                )

                // Create triggers to keep FTS in sync with document_content
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS pkb_doc_fts_sync_BEFORE_UPDATE BEFORE UPDATE ON `document_content` BEGIN DELETE FROM `document_fts` WHERE `docid`=OLD.`document_id`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS pkb_doc_fts_sync_BEFORE_DELETE BEFORE DELETE ON `document_content` BEGIN DELETE FROM `document_fts` WHERE `docid`=OLD.`document_id`; END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS pkb_doc_fts_sync_AFTER_UPDATE AFTER UPDATE ON `document_content` BEGIN INSERT INTO `document_fts`(`docid`, `content`) VALUES (NEW.`document_id`, NEW.`content`); END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS pkb_doc_fts_sync_AFTER_INSERT AFTER INSERT ON `document_content` BEGIN INSERT INTO `document_fts`(`docid`, `content`) VALUES (NEW.`document_id`, NEW.`content`); END"""
                )

                // Create conversation_links table for linking documents to conversations
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `conversation_links` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                        `conversationId` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `linked_at` INTEGER NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON DELETE CASCADE,
                        UNIQUE(`conversationId`, `documentId`)
                    )"""
                )

                // Create indexes for common query patterns
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_title` ON `documents`(`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_source_filename` ON `documents`(`source_filename`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_created_at` ON `documents`(`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_links_conversationId` ON `conversation_links`(`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_links_documentId` ON `conversation_links`(`documentId`)")

                // Rebuild FTS index
                db.execSQL("INSERT INTO `document_fts`(`document_fts`) VALUES ('rebuild')")
            }
        }

        fun create(context: Context): PocketLlmDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PocketLlmDatabase::class.java,
                "pocket_llm.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        seedBuiltInTools(db)
                        seedBuiltInPresets(db)
                    }
                })
                .build()
    }
}

/**
 * Extension function to get database instance.
 */
fun PocketLlmDatabase.getInstance(context: Context): PocketLlmDatabase {
    return PocketLlmDatabase.create(context)
}
