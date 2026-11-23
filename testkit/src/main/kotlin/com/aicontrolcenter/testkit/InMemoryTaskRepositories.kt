package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Reference implementation of [TaskRepository]. */
class InMemoryTaskRepository : TaskRepository {
    private val state = MutableStateFlow<Map<TaskId, TaskRecord>>(emptyMap())

    private val creationOrder = compareBy<TaskRecord>({ it.createdAt }, { it.id.value })

    private fun Map<TaskId, TaskRecord>.objectives() =
        values.filter { it.kind == TaskKind.OBJECTIVE }.sortedWith(compareByDescending<TaskRecord> { it.createdAt }.thenBy { it.id.value })

    private fun Map<TaskId, TaskRecord>.tree(rootId: TaskId) = values.filter { it.rootId == rootId }.sortedWith(creationOrder)

    override fun observeObjectives(): Flow<List<TaskRecord>> = state.map { it.objectives() }.distinctUntilChanged()
    override fun observeTree(rootId: TaskId): Flow<List<TaskRecord>> = state.map { it.tree(rootId) }.distinctUntilChanged()
    override suspend fun get(id: TaskId): TaskRecord? = state.value[id]
    override suspend fun getTree(rootId: TaskId): List<TaskRecord> = state.value.tree(rootId)
    override suspend fun upsert(task: TaskRecord) = state.update { it + (task.id to task) }
    override suspend fun upsertAll(tasks: List<TaskRecord>) = state.update { it + tasks.associateBy { t -> t.id } }

    override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? {
        var result: TaskRecord? = null
        state.update { current ->
            val existing = current[id] ?: return@update current.also { result = null }
            val changed = transform(existing)
            require(changed.id == id) { "A task's id cannot change." }
            result = changed
            current + (id to changed)
        }
        return result
    }

    override suspend fun withStatus(statuses: Set<TaskStatus>): List<TaskRecord> =
        state.value.values.filter { it.status in statuses }.sortedWith(creationOrder)

    override suspend fun deleteTree(rootId: TaskId) = state.update { all -> all.filterValues { it.rootId != rootId } }
}

/** Reference implementation of [MessageRepository]. */
class InMemoryMessageRepository : MessageRepository {
    private val state = MutableStateFlow<List<AgentMessage>>(emptyList())

    private val sendOrder = compareBy<AgentMessage>({ it.at }, { it.id.value })

    override suspend fun append(message: AgentMessage) =
        state.update { all -> if (all.any { it.id == message.id }) all else all + message }

    override fun observe(rootId: TaskId): Flow<List<AgentMessage>> =
        state.map { all -> all.filter { it.rootId == rootId }.sortedWith(sendOrder) }.distinctUntilChanged()

    override fun observeRecent(limit: Int): Flow<List<AgentMessage>> =
        state.map { all -> all.sortedWith(sendOrder).asReversed().take(limit) }.distinctUntilChanged()

    override suspend fun list(rootId: TaskId): List<AgentMessage> = state.value.filter { it.rootId == rootId }.sortedWith(sendOrder)
    override suspend fun count(rootId: TaskId): Int = state.value.count { it.rootId == rootId }
    override suspend fun deleteFor(rootId: TaskId) = state.update { all -> all.filter { it.rootId != rootId } }
}
