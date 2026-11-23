package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.GrantStore
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestDecisions
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.tools.ToolExecutor
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing reachable from the tool executor, the permission broker, the agent runner, the
 * orchestrator or any tool may hold an owner-only port: only the owner's screens can answer a
 * request, grant, or change folders, apps or Stop. Checked on declared field types, following
 * the framework's own classes (and their nested classes) field by field.
 */
class OperatorDependencyTest {
    private val ownerOnly: List<Class<*>> = listOf(
        OwnerRequests::class.java, OwnerGrants::class.java, OwnerFolders::class.java, OwnerOperatorApps::class.java, ScreenControl::class.java,
        OwnerRequestDecisions::class.java, GrantStore::class.java, OwnerRequestRepository::class.java, PermissionRepository::class.java,
    )

    /** Every type held by a field reachable from [root] through the framework's classes, with the path that reaches it. */
    private fun reachable(root: Class<*>): Map<Class<*>, String> {
        val seen = LinkedHashMap<Class<*>, String>()
        val queue = ArrayDeque(listOf(root to root.simpleName))
        while (queue.isNotEmpty()) {
            val (type, path) = queue.removeFirst()
            if (type in seen) continue
            seen[type] = path
            if (!type.name.startsWith(FRAMEWORK)) continue
            var current: Class<*>? = type
            while (current != null && current.name.startsWith(FRAMEWORK)) {
                current.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { queue += it.type to "$path.${it.name}" }
                current.declaredClasses.forEach { queue += it to "$path/${it.simpleName}" }
                current = current.superclass
            }
        }
        return seen
    }

    private fun violations(root: Class<*>): List<String> =
        reachable(root).filter { (type, _) -> ownerOnly.any { it.isAssignableFrom(type) } }.map { (type, path) -> "$path: ${type.simpleName}" }

    @Test
    fun `the executor, the broker, the runner, the orchestrator and every tool reach no owner-only port`() {
        val world = TestWorld()
        val roots = listOf(
            ToolExecutor::class.java, PermissionBroker::class.java, OperatorFacts::class.java, DefaultCapabilityRequester::class.java,
            PermissionRequestTool::class.java, OperatorTaskHooks::class.java, AgentRunner::class.java, DefaultOrchestrator::class.java,
        ) + world.toolRegistry.all().map { it.javaClass }
        for (root in roots.distinct()) assertEquals(emptyList(), violations(root), "${root.simpleName} reaches an owner-only port")
        // The walk does find what it looks for: the owner's own services hold the full stores.
        assertTrue(violations(DefaultOwnerRequests::class.java).isNotEmpty())
        assertTrue(violations(DefaultOwnerGrants::class.java).isNotEmpty())
        assertTrue(reachable(PermissionBroker::class.java).keys.any { it == com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java })
    }

    private companion object {
        const val FRAMEWORK = "com.aicontrolcenter.framework"
    }
}
