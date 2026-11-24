package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.operator.AuthorisedAppsView
import com.aicontrolcenter.app.operator.FolderDirectoryView
import com.aicontrolcenter.app.operator.GrantLookupView
import com.aicontrolcenter.app.operator.RequestQueueView
import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.operator.SharedFolderFilesView
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.GrantStore
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestDecisions
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.framework.operator.OperatorToolDependencies
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dependency rule of the phone operator, checked on the graph as the app wires it: nothing
 * reachable from the tool executor, the permission broker or any registered tool can answer a
 * request, give a grant, add or remove a folder or an app, take or release Android's access to a
 * folder, or press Resume (review 5, R2: the stores of folders and apps, the folder gateway's
 * owner side and the device's Stop switch are forbidden too, and the screen operator behind the
 * agents' screen view is walked).
 *
 * The framework's own test walks declared field types. This one walks the objects themselves,
 * field by field from the executor, the broker and every registered tool, through the app's,
 * the framework's and the test fakes' classes and through lambdas, collections and lazies, so a
 * lambda that captures the composition root (which holds the owner's services) or a store
 * handed over whole behind a narrow type is caught too. A full store may appear only inside
 * the narrow views made for that purpose, which expose the agent-side interface alone.
 */
class OperatorDependencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)

    @After
    fun stop() = scope.cancel()

    private val ownerOnly: List<Class<*>> = listOf(
        OwnerRequests::class.java, OwnerGrants::class.java, OwnerFolders::class.java, OwnerOperatorApps::class.java, ScreenControl::class.java,
        OwnerRequestDecisions::class.java, GrantStore::class.java, OwnerRequestRepository::class.java, PermissionRepository::class.java,
        // Owner-capable mutators: adding and removing folders and apps, taking and releasing a folder's access, Resume after Stop.
        SharedFolderRepository::class.java, OperatorAppRepository::class.java, SharedFolders::class.java,
        Class.forName("com.aicontrolcenter.device.screen.StopSwitch"),
    )

    /**
     * The only objects allowed to hold a full store: each exposes the agent-side interface alone.
     * The agents' screen view ([ScreenSessions]) is not one of them: it is walked, down to the
     * screen operator it passes calls to.
     */
    private val views: List<Class<*>> = listOf(
        RequestQueueView::class.java, GrantLookupView::class.java, FolderDirectoryView::class.java, AuthorisedAppsView::class.java,
        SharedFolderFilesView::class.java,
    )

    private fun isOwnerOnly(type: Class<*>): Boolean = ownerOnly.any { it.isAssignableFrom(type) }

    /**
     * Actions that are the owner's alone, by method name: an object that offers one is a
     * violation whatever its class, also inside a view. Round 2 (B2): letting an agent ask again
     * after a refusal ([OwnerRequests.letAskAgain], written by [OwnerRequestDecisions.letAskAgain]).
     */
    private val ownerOnlyActions = setOf("letAskAgain")

    /** A method taking an id (a value class) is compiled as "name-hash": compared by the name in front. */
    private fun ownerActionsOf(type: Class<*>): List<String> = type.methods.map { it.name.substringBefore('-') }.filter { it in ownerOnlyActions }.distinct()

    private class Walk(val violations: MutableList<String> = ArrayList(), val unreadable: MutableList<String> = ArrayList(), var objects: Int = 0)

    /** Every object reachable from [root], checking each field's declared type and each value's class. */
    private fun walk(root: Any, name: String): Walk {
        val result = Walk()
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque(listOf(root to name))
        while (queue.isNotEmpty()) {
            val (value, path) = queue.removeFirst()
            if (seen.put(value, Unit) != null) continue
            result.objects++
            val type = value.javaClass
            // Checked on every object reached, the narrow views too: none of them may be able to do what only the owner does.
            ownerActionsOf(type).forEach { result.violations += "$path: ${type.name} offers $it" }
            if (isOwnerOnly(type)) {
                result.violations += "$path: ${type.name}"
                continue
            }
            when {
                value is Map<*, *> -> value.entries.forEachIndexed { i, (k, v) ->
                    k?.let { queue += it to "$path{key $i}" }
                    v?.let { queue += it to "$path{$i}" }
                }
                value is Iterable<*> && !followFields(type) -> value.forEachIndexed { i, v -> v?.let { queue += it to "$path[$i]" } }
                type.isArray && !type.componentType.isPrimitive -> (value as Array<*>).forEachIndexed { i, v -> v?.let { queue += it to "$path[$i]" } }
                followFields(type) -> {
                    if (type in views) continue
                    for (field in fieldsOf(type)) {
                        val fieldPath = "$path.${field.name}"
                        if (isOwnerOnly(field.type)) result.violations += "$fieldPath: declared ${field.type.simpleName}"
                        val held = try {
                            field.isAccessible = true
                            field.get(value)
                        } catch (e: Exception) {
                            result.unreadable += "$fieldPath (${e.javaClass.simpleName})"
                            continue
                        } ?: continue
                        queue += held to fieldPath
                    }
                }
            }
        }
        return result
    }

    /**
     * The app's, the framework's and the fakes' own classes, lambdas and Kotlin's holders are
     * followed; platform classes are leaves. So is this test harness ([TestApp] and its lambdas):
     * it stands in for the production ports and holds the graph itself, which a test is allowed to.
     */
    private fun followFields(type: Class<*>): Boolean = !type.name.startsWith(HARNESS) && (
        type.name.startsWith("com.aicontrolcenter.") || type.name.startsWith("kotlin.jvm.internal.") ||
            // Lambdas are classes named after where they are written, so the app's and the framework's are followed above.
            type.name == "kotlin.SynchronizedLazyImpl" || type.name == "kotlin.UnsafeLazyImpl" || type.name == "kotlin.InitializedLazyImpl"
        )

    private fun fieldsOf(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java && followFields(current)) {
            current.declaredFields.filterTo(fields) { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            current = current.superclass
        }
        return fields
    }

    private companion object {
        const val HARNESS = "com.aicontrolcenter.app.testing."
    }

    private fun roots(): List<Pair<Any, String>> =
        listOf(app.graph.toolExecutor to "executor", app.graph.permissionBroker to "broker") +
            app.graph.toolRegistry.all().map { it to "tool ${it.spec.id.value}" }

    @Test
    fun `nothing reachable from the executor, the broker or any registered tool reaches an owner-only port`() {
        for ((root, name) in roots()) {
            val walk = walk(root, name)
            assertEquals(emptyList(), walk.violations, "$name reaches an owner-only port")
            assertEquals(emptyList(), walk.unreadable, "$name has fields the walk could not read, so it proves nothing about them")
        }
        // The walk goes deep: the executor reaches the registry, every tool and the broker's stores.
        assertTrue(walk(app.graph.toolExecutor, "executor").objects > 100)
    }

    @Test
    fun `letting an agent ask again after a refusal cannot be reached from the executor, the broker or any tool`() {
        // Round 2 (B2). The owner's screens can: the port offers it, and the store's owner side writes it.
        assertEquals(listOf("letAskAgain"), ownerActionsOf(app.graph.ownerRequests.javaClass))
        assertEquals(listOf("letAskAgain"), ownerActionsOf(app.ownerRequestStore.javaClass))
        assertTrue(walk(app.graph.ownerRequests, "ownerRequests").violations.any { it.endsWith("offers letAskAgain") })
        // What the broker, the requester and the hooks are handed cannot, by type and as wired.
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java))
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.GrantLookup::class.java))
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.CapabilityRequester::class.java))
        assertEquals(emptyList(), ownerActionsOf(RequestQueueView::class.java), "the view over the full request store exposes the queue alone")
        assertEquals(emptyList(), ownerActionsOf(GrantLookupView::class.java))
        for ((root, name) in roots()) {
            val offering = walk(root, name).violations.filter { " offers " in it }
            assertEquals(emptyList(), offering, "$name reaches an action that is the owner's alone")
        }
        // A view that handed the action through would be caught, although views are not walked into.
        val leakyView = object : com.aicontrolcenter.core.operator.OwnerRequestQueue by app.ownerRequestStore {
            @Suppress("unused")
            suspend fun letAskAgain(id: com.aicontrolcenter.core.RequestId) = app.ownerRequestStore.letAskAgain(id, 0)
        }
        assertTrue(walk(object { val queue: Any = leakyView }, "holder").violations.any { it.endsWith("offers letAskAgain") })
    }

    @Test
    fun `the walk finds what it looks for`() {
        // The owner's services hold the full stores, and the composition root holds the owner's services.
        assertTrue(walk(app.graph.ownerRequests, "ownerRequests").violations.isNotEmpty())
        assertTrue(walk(app.graph.ownerGrants, "ownerGrants").violations.isNotEmpty())
        // A lambda that captures the composition root is caught, though its type says nothing.
        val graph = app.graph
        val leaky: () -> ProtectedPackages = { graph.ownerRequests.let { ProtectedPackages(null) } }
        assertTrue(walk(leaky, "leaky lambda").violations.isNotEmpty())
        // A full store behind a narrow type is caught outside the views.
        val holder = object { val queue: com.aicontrolcenter.core.operator.OwnerRequestQueue = app.ownerRequestStore }
        assertTrue(walk(holder, "holder").violations.isNotEmpty())
        // The views themselves are what the broker and the tools hold.
        assertTrue(walk(app.graph.permissionBroker, "broker").let { it.violations.isEmpty() && it.unreadable.isEmpty() })
        // R2: a folder or app store, the folder gateway's owner side or a screen that can resume, held by anything else, is caught.
        val stores = object {
            val folders: Any = app.sharedFolders
            val apps: Any = app.operatorApps
            val gateway: Any = app.folderAccess
        }
        assertEquals(3, walk(stores, "stores").violations.size, walk(stores, "stores").violations.toString())
        val resumable = object : com.aicontrolcenter.core.operator.ScreenOperator by app.screen, ScreenControl by app.screen.asControl() {
            override val stopped get() = app.screen.stopped
        }
        val screenView = ScreenSessions(resumable, AuthorisedAppsView(app.operatorApps), scope)
        assertTrue(walk(screenView, "screen view").violations.isNotEmpty(), "the walk goes behind the agents' screen view")
    }

    @Test
    fun `every operator tool is registered with the spec of its catalogue entry, and every capability is known`() {
        val registered = app.graph.toolRegistry.all().associateBy { it.spec.id }
        for (info in OperatorTools.all) {
            val spec = registered[info.id]?.spec ?: error("${info.id.value} is not registered")
            assertEquals(info.capability, spec.capability, info.id.value)
            assertEquals(info.risk, spec.risk, info.id.value)
            assertEquals(info.category, spec.category, info.id.value)
            assertEquals(info.sendsDataOffDevice, spec.sendsDataOffDevice, info.id.value)
            assertTrue(app.graph.capabilities.info(spec.capability) != null, "${spec.capability.id} is not a known permission")
        }
        assertEquals(45, OperatorTools.all.size)
        assertTrue(registered[PermissionRequestTool.ID] is PermissionRequestTool)
        assertEquals(OperatorCapabilities.all, OperatorCapabilities.all.map { app.graph.capabilities.info(it.capability) })
        // Only reading the workspace is granted without asking.
        assertEquals(listOf(OperatorCapabilities.WORKSPACE_READ), OperatorCapabilities.all.filter { it.grantedByDefault }.map { it.capability })
        // The registry holds no second copy of any tool id.
        assertEquals(registered.size, app.graph.toolRegistry.all().size)
        assertTrue(ToolId("workspace.write") in registered)
    }

    @Test
    fun `the tools get the one workspace the owner's screens use`() {
        val write = app.graph.toolRegistry.byId(ToolId("workspace.write"))!!
        val dependencies = fieldsOf(write.javaClass).first { it.type == OperatorToolDependencies::class.java }.also { it.isAccessible = true }.get(write) as OperatorToolDependencies
        assertTrue(dependencies.workspace === app.graph.workspace)
        assertTrue(dependencies.screen === app.graph.screenSessions, "screen tools get the agent-side view, not the device operator")
    }
}
