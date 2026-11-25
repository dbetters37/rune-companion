package com.aicontrolcenter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.data.db.dao.ActivityEventDao
import com.aicontrolcenter.data.db.dao.AgentDao
import com.aicontrolcenter.data.db.dao.AgentMessageDao
import com.aicontrolcenter.data.db.dao.AiAppDao
import com.aicontrolcenter.data.db.dao.ConversationDao
import com.aicontrolcenter.data.db.dao.MemoryDao
import com.aicontrolcenter.data.db.dao.ModelDao
import com.aicontrolcenter.data.db.dao.OperatorAppDao
import com.aicontrolcenter.data.db.dao.OwnerRequestDao
import com.aicontrolcenter.data.db.dao.PermissionGrantDao
import com.aicontrolcenter.data.db.dao.PickUpDao
import com.aicontrolcenter.data.db.dao.ProviderDao
import com.aicontrolcenter.data.db.dao.RoutineDao
import com.aicontrolcenter.data.db.dao.SharedFolderDao
import com.aicontrolcenter.data.db.dao.TaskDao
import com.aicontrolcenter.data.db.dao.UsageDao
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import com.aicontrolcenter.data.db.entity.AgentEntity
import com.aicontrolcenter.data.db.entity.AgentMessageEntity
import com.aicontrolcenter.data.db.entity.AgentStatsEntity
import com.aicontrolcenter.data.db.entity.AiAppEntity
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.ModelEntity
import com.aicontrolcenter.data.db.entity.ModelPriceEntity
import com.aicontrolcenter.data.db.entity.OperatorAppEntity
import com.aicontrolcenter.data.db.entity.OwnerRequestEntity
import com.aicontrolcenter.data.db.entity.PermissionGrantEntity
import com.aicontrolcenter.data.db.entity.PickUpImportEntity
import com.aicontrolcenter.data.db.entity.PickUpRuleEntity
import com.aicontrolcenter.data.db.entity.ProviderEntity
import com.aicontrolcenter.data.db.entity.RoutineEntity
import com.aicontrolcenter.data.db.entity.RoutineRunEntity
import com.aicontrolcenter.data.db.entity.SharedFolderEntity
import com.aicontrolcenter.data.db.entity.TaskEntity
import com.aicontrolcenter.data.db.entity.UsageHourEntity
import com.aicontrolcenter.data.db.entity.WorkUsageEntity
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The app's single SQLite database: the registry (agents and their stats, providers, models,
 * AI applications), the activity log, the conversations with agents, memory (knowledge
 * spaces, memories, the links between them and their embedding vectors), and orchestration
 * (the tasks of the owner's objectives and the messages agents exchange while working on them),
 * the phone operator's permissions (the owner's grants, the questions put to the owner, the
 * shared folders the owner picked and the apps authorised for screen operation), and from
 * schema 6 the usage of models with the owner's prices, routines, and folder pick-up rules.
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
        TaskEntity::class,
        AgentMessageEntity::class,
        PermissionGrantEntity::class,
        OwnerRequestEntity::class,
        SharedFolderEntity::class,
        OperatorAppEntity::class,
        UsageHourEntity::class,
        WorkUsageEntity::class,
        ModelPriceEntity::class,
        RoutineEntity::class,
        RoutineRunEntity::class,
        PickUpRuleEntity::class,
        PickUpImportEntity::class,
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
    internal abstract fun taskDao(): TaskDao
    internal abstract fun agentMessageDao(): AgentMessageDao
    internal abstract fun permissionGrantDao(): PermissionGrantDao
    internal abstract fun ownerRequestDao(): OwnerRequestDao
    internal abstract fun sharedFolderDao(): SharedFolderDao
    internal abstract fun operatorAppDao(): OperatorAppDao
    internal abstract fun usageDao(): UsageDao
    internal abstract fun routineDao(): RoutineDao
    internal abstract fun pickUpDao(): PickUpDao

    companion object {
        /**
         * Version 1 was the first release; 2 added conversations, 3 memory, 4 tasks and agent
         * messages, 5 the operator's grants, owner requests, shared folders and operator apps,
         * 6 (release 0.6.0) usage, prices, routines, folder pick-up and the columns of Phase 6.
         * See [AiccMigrations].
         */
        const val VERSION = 6
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
