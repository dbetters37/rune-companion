package com.aicontrolcenter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.data.db.dao.ActivityEventDao
import com.aicontrolcenter.data.db.dao.AgentDao
import com.aicontrolcenter.data.db.dao.AiAppDao
import com.aicontrolcenter.data.db.dao.ConversationDao
import com.aicontrolcenter.data.db.dao.MemoryDao
import com.aicontrolcenter.data.db.dao.ModelDao
import com.aicontrolcenter.data.db.dao.ProviderDao
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import com.aicontrolcenter.data.db.entity.AgentEntity
import com.aicontrolcenter.data.db.entity.AgentStatsEntity
import com.aicontrolcenter.data.db.entity.AiAppEntity
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.ModelEntity
import com.aicontrolcenter.data.db.entity.ProviderEntity
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The app's single SQLite database: the registry (agents and their stats, providers, models,
 * AI applications), the activity log, the conversations with agents, and memory (knowledge
 * spaces, memories, the links between them and their embedding vectors).
 *
 * The schema is exported to `data/schemas` and versioned with the code. There is no destructive
 * fallback: opening a database whose version has no migration path fails loudly instead of
 * silently discarding the user's data. See [AiccMigrations].
 *
 * Tables and DAOs are internal to this module; other modules use the repositories.
 */
@Database(
    entities = [
        AgentEntity::class,
        AgentStatsEntity::class,
        ProviderEntity::class,
        ModelEntity::class,
        AiAppEntity::class,
        ActivityEventEntity::class,
        ConversationEntity::class,
        ConversationEntryEntity::class,
        KnowledgeSpaceEntity::class,
        MemoryEntity::class,
        MemoryLinkEntity::class,
        MemoryEmbeddingEntity::class,
    ],
    version = AiccDatabase.VERSION,
    exportSchema = true,
)
abstract class AiccDatabase : RoomDatabase() {
    internal abstract fun agentDao(): AgentDao
    internal abstract fun providerDao(): ProviderDao
    internal abstract fun modelDao(): ModelDao
    internal abstract fun aiAppDao(): AiAppDao
    internal abstract fun activityEventDao(): ActivityEventDao
    internal abstract fun conversationDao(): ConversationDao
    internal abstract fun memoryDao(): MemoryDao

    companion object {
        /** Version 1 was the first release; 2 added conversations, 3 memory. See [AiccMigrations]. */
        const val VERSION = 3
        const val DEFAULT_NAME = "aicc.db"

        /**
         * Opens (creating if needed) the file-backed database [name] in app-private storage.
         *
         * @param openHelperFactory replaces the platform SQLite open helper. This is the hook
         *   for full-database encryption: pass an encrypting factory and nothing else changes.
         * @param queryDispatcher runs every query and flow emission; Room's own I/O executor
         *   is used when null.
         */
        fun create(
            context: Context,
            name: String = DEFAULT_NAME,
            openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
            queryDispatcher: CoroutineDispatcher? = null,
        ): AiccDatabase =
            Room.databaseBuilder(context.applicationContext, AiccDatabase::class.java, name)
                .configure(openHelperFactory, queryDispatcher)

        /** A database that lives only as long as the returned object. For tests. */
        fun createInMemory(
            context: Context,
            queryDispatcher: CoroutineDispatcher? = null,
        ): AiccDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AiccDatabase::class.java)
                .configure(openHelperFactory = null, queryDispatcher = queryDispatcher)

        private fun Builder<AiccDatabase>.configure(
            openHelperFactory: SupportSQLiteOpenHelper.Factory?,
            queryDispatcher: CoroutineDispatcher?,
        ): AiccDatabase {
            addMigrations(*AiccMigrations.all)
            openHelperFactory?.let(::openHelperFactory)
            queryDispatcher?.let(::setQueryCoroutineContext)
            return build()
        }
    }
}
