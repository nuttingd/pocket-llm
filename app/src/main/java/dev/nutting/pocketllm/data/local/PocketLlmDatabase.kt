package dev.nutting.pocketllm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.nutting.pocketllm.data.local.dao.ConversationDao
import dev.nutting.pocketllm.data.local.dao.MessageDao
import dev.nutting.pocketllm.data.local.dao.ServerProfileDao
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity

@Database(
    entities = [
        ServerProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PocketLlmDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): PocketLlmDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PocketLlmDatabase::class.java,
                "pocket_llm.db",
            ).build()
    }
}
