package com.aicontrolcenter.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.db.mapper.EmbeddingCodec
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The upgrade from release 0.5.2 to 0.6.0 in the production configuration (Phase 6, rule 19):
 * a version 5 database encrypted by SQLCipher with a key sealed by the device keystore, holding
 * a row in every one of its eighteen tables, is opened the way the app opens it; Room runs
 * `Migration5To6` under SQLCipher; everything is still there, the new tables and columns work,
 * and the files reveal nothing. The JVM `MigrationTest` checks the same step field by field on
 * plain SQLite; this is the one place it runs on Android's own storage with real encryption.
 *
 * Runs on a device or emulator only (the wave-1 gate). Method names are plain so they can be
 * dexed for every supported API level.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "encrypted-migration-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(databaseName))
    private val open = mutableListOf<DataLayer>()

    /** The key the app will use for this database: created and sealed exactly as `DataLayer` does it. */
    private val key: ByteArray = DatabaseKeyStore(
        file = keyFile,
        cipher = AndroidKeystoreVaultCipher(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS),
        databaseName = databaseName,
    ).loadOrCreate(databaseExists = false)

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AiccDatabase::class.java, emptyList(), SqlCipher.openHelperFactory(key),
    )

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        context.deleteDatabase(databaseName)
        keyFile.delete()
    }

    private fun databaseFiles(): List<File> {
        val main = context.getDatabasePath(databaseName)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-journal"), File(main.path + "-shm")).filter { it.isFile }
    }

    private fun File.contains(text: String): Boolean = String(readBytes(), Charsets.ISO_8859_1).contains(text)

    @Test
    fun anEncryptedVersionFiveDatabaseIsUpgradedInPlaceAndKeepsEverything(): Unit = runTest(timeout = 5.minutes) {
        // What release 0.5.2 left on the phone: a row in every table of schema 5, encrypted.
        helper.createDatabase(databaseName, 5).use { V5.insertInto(it) }
        for (file in databaseFiles()) assertFalse(file.contains(MARKER), "${file.name} holds text in the clear before the upgrade")

        // The app opens it as always: the sealed key is found, SQLCipher opens it, Room migrates.
        val data = DataLayer(context, databaseName = databaseName).also(open::add)
        assertEquals(6, data.database.openHelper.readableDatabase.version)

        assertEquals(listOf(V5.agent), data.agents.getAll())
        assertEquals(V5.stats, data.agents.getStats(V5.agent.id))
        assertEquals(listOf(V5.provider), data.providers.getAll())
        assertEquals(listOf(V5.model), data.models.getAll())
        assertEquals(listOf(V5.app), data.aiApps.getAll())
        assertEquals(listOf(V5.event), data.activityLog.recent())
        assertEquals(listOf(V5.conversation), data.conversations.observeConversations().first())
        assertEquals(listOf(V5.entry), data.conversations.getEntries(V5.conversation.id))
        assertEquals(listOf(V5.space), data.memories.observeSpaces().first())
        assertEquals(V5.memories, data.memories.list())
        assertEquals(listOf(V5.link), data.memories.links())
        assertContentEquals(V5.embedding, data.memories.embeddings(V5.EMBEDDER).getValue(V5.memories.first().id))
        assertEquals(V5.tasks, data.tasks.getTree(V5.objective.id))
        assertEquals(listOf(V5.message), data.messages.list(V5.objective.id))
        assertEquals(V5.grant, data.permissions.get(V5.grant.id))
        assertEquals(V5.request, data.ownerRequests.get(V5.request.id))
        assertEquals(listOf(V5.folder), data.sharedFolders.getAll())
        assertEquals(listOf(V5.operatorApp), data.operatorApps.getAll())

        // The columns and tables of schema 6 work on the upgraded database.
        assertEquals(listOf(0, 29), data.tasks.observeSummaries(V5.objective.id).first().map { it.resultChars })
        data.usageLedger.record(
            ModelCallSample(
                at = 5_000, modelId = V5.model.id, modelLabel = "Gemma", providerId = V5.provider.id, providerLabel = "Home server",
                locality = Locality.LOCAL_NETWORK, agentId = V5.agent.id, taskId = V5.work.id, rootTaskId = V5.objective.id,
                source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK, inputTokens = 100, outputTokens = 10, answerMs = 900,
            ),
        )
        assertEquals(1, data.usageReports.totals(UsageRange.ALL).calls)
        data.messages.append(V5.message.copy(id = MessageId("msg_space"), to = Participant.Space(V5.space.id), at = 6_000))
        assertEquals(Participant.Space(V5.space.id), data.messages.list(V5.objective.id).last().to)
        data.conversations.markTurnStarted(V5.conversation.id, 7_000)
        assertEquals(7_000, data.conversations.interruptedTurns().single().startedAt)
        val question = OwnerRequest(
            id = RequestId("req_q"), kind = OwnerRequestKind.QUESTION, agentId = V5.agent.id, toolId = null,
            capability = Capability("team.ask_owner"), risk = RiskLevel.SAFE, summary = "A question from Researcher",
            createdAt = 8_000, expiresAt = 9_000_000, question = "Which city? $MARKER", options = listOf("Berlin", "Paris"),
        )
        data.ownerRequests.create(question)
        data.ownerRequests.decide(question.id, OwnerDecision.Answer("Berlin $MARKER"), 8_100, GrantId("grt_unused"))
        assertEquals("Berlin $MARKER", data.ownerRequests.get(question.id)?.answer)
        data.close()

        // Nothing of the old rows or the new ones is readable from the files.
        for (file in databaseFiles()) {
            assertFalse(file.contains(MARKER), "${file.name} holds text in the clear after the upgrade")
            assertFalse(file.contains("CREATE TABLE"), "${file.name} holds the schema in the clear")
        }

        // And after a restart everything is found again.
        val again = DataLayer(context, databaseName = databaseName).also(open::add)
        assertEquals(V5.tasks, again.tasks.getTree(V5.objective.id))
        assertEquals(1, again.usageReports.totals(UsageRange.ALL).calls)
        assertIs<OwnerRequest>(again.ownerRequests.get(question.id))
    }

    /** One row in each table of schema version 5, written with plain SQL under that version's column names. */
    private object V5 {
        const val EMBEDDER = "hash-v1"
        private fun provenance(at: Long) = Provenance(origin = ProvenanceOrigin.OWNER, recordedAt = at, source = "Typed by the owner $MARKER")

        val agent = AgentProfile(
            id = AgentId("agt_researcher"), name = "Researcher $MARKER", kind = AgentKind.SPECIALIST,
            description = "Finds sources.", modelId = ModelId("mdl_gemma"), createdAt = 100, updatedAt = 110,
        )
        val stats = AgentStats(tasksAssigned = 2, tasksCompleted = 1, modelCalls = 5, inputTokens = 1_000, outputTokens = 200)
        val provider = ModelProvider(
            id = ProviderId("prv_home"), name = "Home server", kind = ProviderKind.OLLAMA, baseUrl = "http://192.168.1.20:11434",
            locality = Locality.LOCAL_NETWORK, createdAt = 100, updatedAt = 100,
        )
        val model = ModelDescriptor(
            id = ModelId("mdl_gemma"), providerId = provider.id, modelName = "gemma3:4b", displayName = "Gemma 3 4B",
            source = ModelSource.PROVIDER_LISTING, createdAt = 100, updatedAt = 100,
        )
        val app = AiApp(
            id = AiAppId("app_notes"), packageName = "com.example.notes", label = "Notes", source = DiscoverySource.CATALOG,
            firstSeenAt = 100, lastSeenAt = 100,
        )
        val event = ActivityEvent(id = EventId("evt_1"), at = 200, type = ActivityType.TASK, title = "Objective started", detail = MARKER)
        val conversation = Conversation(ConversationId("cnv_1"), agent.id, "Chat $MARKER", createdAt = 300, updatedAt = 310)
        val entry = ConversationEntry(conversation.id, seq = 0, message = ChatMessage.user("Hello $MARKER"), at = 305)
        val space = KnowledgeSpace(SpaceId("spc_commons"), "Commons", "Shared", createdAt = 400, updatedAt = 400)
        val memories = listOf(
            MemoryRecord(
                id = MemoryId("mem_a"), scope = MemoryScope.Agent(agent.id), kind = MemoryKind.FACT, tier = MemoryTier.LONG_TERM,
                title = "Private", content = "Private note $MARKER", provenance = provenance(500), createdAt = 500, updatedAt = 520,
            ),
            MemoryRecord(
                id = MemoryId("mem_b"), scope = MemoryScope.Shared(space.id), kind = MemoryKind.FINDING, tier = MemoryTier.LONG_TERM,
                title = "Shared", content = "Shared finding $MARKER", provenance = provenance(510), createdAt = 510, updatedAt = 510,
            ),
        )
        val link = MemoryLink(
            MemoryLinkId("lnk_1"), memories[0].id, memories[1].id, LinkRelation.RELATES_TO, provenance = provenance(530), createdAt = 530,
        )
        val embedding = floatArrayOf(0.6f, -0.8f)
        val objective = TaskRecord(
            id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = "Compare note apps",
            instructions = "Compare them $MARKER", requestedBy = TaskRequester.Owner, assignee = AgentId("agt_director"),
            status = TaskStatus.RUNNING, createdAt = 1_000, updatedAt = 1_001,
        )
        val work = TaskRecord(
            id = TaskId("tsk_1"), rootId = objective.id, parentId = objective.id, kind = TaskKind.WORK, title = "Research",
            instructions = "Find three apps", requestedBy = TaskRequester.Agent(AgentId("agt_director")), assignee = agent.id,
            status = TaskStatus.COMPLETED, result = "Plan: three apps, \"ranked\" 📝🙂", createdAt = 1_100, updatedAt = 1_200,
        )
        val tasks = listOf(objective, work)
        val message = AgentMessage(
            MessageId("msg_1"), objective.id, work.id, Participant.Agent(AgentId("agt_director")), Participant.Agent(agent.id),
            MessageType.ASSIGNMENT, "Please research $MARKER", replyTo = null, hop = 1, at = 1_150,
        )
        val grant = PermissionGrant(
            id = GrantId("grt_1"), agentId = agent.id, capability = Capability("folders.read"),
            constraint = CapabilityConstraint.Folder(FolderId("fld_1")), scope = GrantScope.ALWAYS, grantedAt = 2_000,
        )
        val request = OwnerRequest(
            id = RequestId("req_1"), kind = OwnerRequestKind.APPROVAL, agentId = agent.id, toolId = ToolId("apps.compose"),
            capability = Capability("apps.compose"), risk = RiskLevel.CONSEQUENTIAL, summary = "Send an email $MARKER",
            createdAt = 2_100, expiresAt = 9_000_000,
        )
        val folder = SharedFolder(FolderId("fld_1"), "content://com.android.externalstorage.documents/tree/primary%3ANotes", "Notes", 2_200)
        val operatorApp = OperatorApp("com.example.notes", "Notes", 2_300, "ab".repeat(32))

        fun insertInto(db: SupportSQLiteDatabase) {
            agent.toEntity().let {
                db.insertRow(
                    "agents",
                    "id" to it.id, "name" to it.name, "kind" to it.kind, "description" to it.description,
                    "system_instructions" to it.systemInstructions, "personality" to it.personality,
                    "responsibilities_json" to it.responsibilitiesJson, "skills_json" to it.skillsJson,
                    "model_id" to it.modelId, "fallback_model_ids_json" to it.fallbackModelIdsJson,
                    "generation_json" to it.generationJson, "tool_ids_json" to it.toolIdsJson,
                    "memory_json" to it.memoryJson, "delegation_json" to it.delegationJson, "enabled" to it.enabled,
                    "color_index" to it.colorIndex, "template_id" to it.templateId, "created_at" to it.createdAt,
                    "updated_at" to it.updatedAt,
                )
            }
            stats.toEntity(agent.id).let {
                db.insertRow(
                    "agent_stats",
                    "agent_id" to it.agentId, "tasks_assigned" to it.tasksAssigned, "tasks_completed" to it.tasksCompleted,
                    "tasks_failed" to it.tasksFailed, "tasks_timed_out" to it.tasksTimedOut,
                    "tasks_cancelled" to it.tasksCancelled, "reviews_passed" to it.reviewsPassed,
                    "revisions_requested" to it.revisionsRequested, "reviews_rejected" to it.reviewsRejected,
                    "model_calls" to it.modelCalls, "model_errors" to it.modelErrors, "tool_calls" to it.toolCalls,
                    "tool_errors" to it.toolErrors, "input_tokens" to it.inputTokens, "output_tokens" to it.outputTokens,
                    "total_task_duration_ms" to it.totalTaskDurationMs, "last_active_at" to it.lastActiveAt,
                )
            }
            provider.toEntity().let {
                db.insertRow(
                    "providers",
                    "id" to it.id, "name" to it.name, "kind" to it.kind, "base_url" to it.baseUrl,
                    "api_key_alias" to it.apiKeyAlias, "extra_headers_json" to it.extraHeadersJson,
                    "locality" to it.locality, "enabled" to it.enabled, "status" to it.status,
                    "status_message" to it.statusMessage, "last_checked_at" to it.lastCheckedAt,
                    "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                )
            }
            model.toEntity().let {
                db.insertRow(
                    "models",
                    "id" to it.id, "provider_id" to it.providerId, "model_name" to it.modelName,
                    "display_name" to it.displayName, "capabilities_json" to it.capabilitiesJson, "source" to it.source,
                    "local_path" to it.localPath, "size_bytes" to it.sizeBytes, "enabled" to it.enabled,
                    "status" to it.status, "notes" to it.notes, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                )
            }
            app.toEntity().let {
                db.insertRow(
                    "ai_apps",
                    "id" to it.id, "package_name" to it.packageName, "label" to it.label, "vendor" to it.vendor,
                    "version_name" to it.versionName, "source" to it.source, "capabilities_json" to it.capabilitiesJson,
                    "status" to it.status, "enabled" to it.enabled,
                    "accessibility_authorized" to it.accessibilityAuthorized, "linked_provider_id" to it.linkedProviderId,
                    "prompt_deep_link" to it.promptDeepLink, "notes" to it.notes,
                    "first_install_time" to it.firstInstallTime, "signing_cert_sha256" to it.signingCertSha256,
                    "verified_by_user" to it.verifiedByUser, "hidden" to it.hidden, "first_seen_at" to it.firstSeenAt,
                    "last_seen_at" to it.lastSeenAt,
                )
            }
            event.toEntity().let {
                db.insertRow(
                    "activity_events",
                    "id" to it.id, "at" to it.at, "type" to it.type, "severity" to it.severity, "agent_id" to it.agentId,
                    "task_id" to it.taskId, "root_task_id" to it.rootTaskId, "title" to it.title, "detail" to it.detail,
                    "data_json" to it.dataJson, "egress_json" to it.egressJson, "has_egress" to it.hasEgress,
                )
            }
            conversation.toEntity().let {
                db.insertRow(
                    "conversations",
                    "id" to it.id, "agent_id" to it.agentId, "title" to it.title, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                )
            }
            entry.toEntity().let {
                db.insertRow(
                    "conversation_entries",
                    "conversation_id" to it.conversationId, "seq" to it.seq, "at" to it.at, "model_name" to it.modelName,
                    "message_json" to it.messageJson, "usage_json" to it.usageJson, "error" to it.error,
                )
            }
            space.toEntity().let {
                db.insertRow(
                    "knowledge_spaces",
                    "id" to it.id, "name" to it.name, "description" to it.description, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                )
            }
            memories.map { it.toEntity() }.forEach {
                db.insertRow(
                    "memories",
                    "id" to it.id, "scope_type" to it.scopeType, "scope_agent_id" to it.scopeAgentId, "scope_space_id" to it.scopeSpaceId,
                    "scope_task_id" to it.scopeTaskId, "kind" to it.kind, "tier" to it.tier, "title" to it.title, "content" to it.content,
                    "tags_json" to it.tagsJson, "importance" to it.importance, "confidence" to it.confidence,
                    "provenance_json" to it.provenanceJson, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                    "last_accessed_at" to it.lastAccessedAt, "access_count" to it.accessCount, "expires_at" to it.expiresAt,
                    "revision" to it.revision, "history_json" to it.historyJson, "search_text" to it.searchText,
                )
            }
            link.toEntity().let {
                db.insertRow(
                    "memory_links",
                    "id" to it.id, "from_id" to it.fromId, "to_id" to it.toId, "relation" to it.relation, "weight" to it.weight,
                    "provenance_json" to it.provenanceJson, "created_at" to it.createdAt,
                )
            }
            db.insertRow(
                "memory_embeddings",
                "memory_id" to memories.first().id.value, "embedder_id" to EMBEDDER, "dimensions" to embedding.size,
                "vector" to EmbeddingCodec.encode(embedding),
            )
            tasks.map { it.toEntity() }.forEach {
                db.insertRow(
                    "tasks",
                    "id" to it.id, "root_id" to it.rootId, "parent_id" to it.parentId, "kind" to it.kind, "status" to it.status,
                    "assignee_id" to it.assigneeId, "sort_order" to it.sortOrder, "attempt" to it.attempt,
                    "revision_round" to it.revisionRound, "needs_review" to it.needsReview, "conversation_id" to it.conversationId,
                    "title" to it.title, "instructions" to it.instructions, "status_detail" to it.statusDetail, "result" to it.result,
                    "error" to it.error, "criteria_json" to it.criteriaJson, "depends_on_json" to it.dependsOnJson,
                    "delegation_chain_json" to it.delegationChainJson, "reviews_json" to it.reviewsJson, "usage_json" to it.usageJson,
                    "options_json" to it.optionsJson, "requested_by_json" to it.requestedByJson, "created_at" to it.createdAt,
                    "updated_at" to it.updatedAt, "started_at" to it.startedAt, "finished_at" to it.finishedAt, "deadline_at" to it.deadlineAt,
                )
            }
            message.toEntity().let {
                db.insertRow(
                    "agent_messages",
                    "id" to it.id, "root_id" to it.rootId, "task_id" to it.taskId, "from_kind" to it.fromKind,
                    "from_agent_id" to it.fromAgentId, "to_kind" to it.toKind, "to_agent_id" to it.toAgentId, "type" to it.type,
                    "body" to it.body, "reply_to" to it.replyTo, "hop" to it.hop, "at" to it.at,
                )
            }
            grant.toEntity().let {
                db.insertRow(
                    "permission_grants",
                    "id" to it.id, "agent_id" to it.agentId, "capability" to it.capability, "constraint_key" to it.constraintKey,
                    "scope" to it.scope, "task_id" to it.taskId, "request_id" to it.requestId, "granted_at" to it.grantedAt,
                    "expires_at" to it.expiresAt, "revoked_at" to it.revokedAt, "consumed_at" to it.consumedAt,
                )
            }
            request.toEntity().let {
                db.insertRow(
                    "owner_requests",
                    "id" to it.id, "kind" to it.kind, "status" to it.status, "agent_id" to it.agentId, "tool_id" to it.toolId,
                    "capability" to it.capability, "constraint_key" to it.constraintKey, "risk" to it.risk, "summary" to it.summary,
                    "details_json" to it.detailsJson, "data_goes_to" to it.dataGoesTo, "reason" to it.reason, "task_id" to it.taskId,
                    "root_id" to it.rootId, "conversation_id" to it.conversationId, "created_at" to it.createdAt,
                    "expires_at" to it.expiresAt, "decided_at" to it.decidedAt, "decision_scope" to it.decisionScope,
                    "decision_note" to it.decisionNote, "agent_name" to it.agentName, "tool_title" to it.toolTitle,
                    "target_label" to it.targetLabel, "delegation_chain_json" to it.delegationChainJson,
                    "asked_again" to it.askedAgain, "ask_again_at" to it.askAgainAt,
                )
            }
            folder.toEntity().let {
                db.insertRow(
                    "shared_folders",
                    "id" to it.id, "tree_uri" to it.treeUri, "display_name" to it.displayName, "added_at" to it.addedAt, "read_only" to it.readOnly,
                )
            }
            operatorApp.toEntity().let {
                db.insertRow(
                    "operator_apps",
                    "package_name" to it.packageName, "label" to it.label, "authorised_at" to it.authorisedAt,
                    "signing_cert_sha256" to it.signingCertSha256, "action_policy" to it.actionPolicy,
                )
            }
        }

        private fun SupportSQLiteDatabase.insertRow(table: String, vararg columns: Pair<String, Any?>) {
            val values = ContentValues()
            for ((name, value) in columns) {
                when (value) {
                    null -> values.putNull(name)
                    is String -> values.put(name, value)
                    is Int -> values.put(name, value)
                    is Long -> values.put(name, value)
                    is Double -> values.put(name, value)
                    is Boolean -> values.put(name, if (value) 1 else 0)
                    is ByteArray -> values.put(name, value)
                    else -> error("no SQLite type for column $table.$name")
                }
            }
            check(insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "could not insert into $table" }
        }
    }

    private companion object {
        const val MARKER = "ZZ-MIGRATION-MARKER-5f2e"
    }
}
