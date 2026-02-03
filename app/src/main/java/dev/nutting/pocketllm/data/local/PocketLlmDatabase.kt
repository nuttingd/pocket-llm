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
import dev.nutting.pocketllm.data.local.dao.ParameterPresetDao
import dev.nutting.pocketllm.data.local.dao.ToolDefinitionDao
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ConversationToolEnabledEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.MessageFts
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity

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
    ],
    version = 5,
    exportSchema = true,
)
abstract class PocketLlmDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun compactionSummaryDao(): CompactionSummaryDao
    abstract fun toolDefinitionDao(): ToolDefinitionDao
    abstract fun parameterPresetDao(): ParameterPresetDao

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
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(`content`, content=`messages`, tokenizer=unicode61)"
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

        fun create(context: Context): PocketLlmDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PocketLlmDatabase::class.java,
                "pocket_llm.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        seedBuiltInTools(db)
                        seedBuiltInPresets(db)
                    }
                })
                .build()
    }
}
