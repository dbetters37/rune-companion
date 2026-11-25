package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.operator.AuthorisedAppsView
import com.aicontrolcenter.app.operator.FolderDirectoryView
import com.aicontrolcenter.app.operator.GrantLookupView
import com.aicontrolcenter.app.operator.PickUpFoldersView
import com.aicontrolcenter.app.operator.RequestQueueView
import com.aicontrolcenter.app.operator.SharedFolderFilesView
import com.aicontrolcenter.core.operator.GrantStore
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestDecisions
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.framework.llm.ProviderConnections
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * The walk of the app's architecture tests (`OperatorDependencyTest` over `TestApp`,
 * `ProductionGraphDependencyTest` over the production ports): every object reachable from an
 * agent-facing root, field by field, through the app's, the framework's and the fakes' classes and
 * through lambdas, collections and lazies, failing on any object that is an owner-only port or
 * offers an owner-only action. A full store may appear only inside the narrow views made for it.
 *
 * @param extraViews narrow views of production classes that only the production walk meets.
 * @param ownerOnly the types no reached object may be (another list for walks that are not agent-facing, such as the
 *   backup's stores).
 * @param checkActions whether an object offering an owner-only action fails (off where the owner's own ports are walked).
 * @param leafPackages packages whose classes are not walked into.
 *
 * Room's database, its open helper and its DAOs ([isDatabase]) may be held only by the Room stores
 * (`com.aicontrolcenter.data.repository.…`) and the data layer's one transaction (`DataLayer.RoomTransactions`): there
 * they are the storage engine and are not walked into; held by anything else they are a violation, since they reach
 * every table (security review of wave 1, F4/F10, second review). Other containers are read through: Kotlin's and
 * Java's collections, lazies, `AtomicReference`, `java.lang.ref.Reference`, a `StateFlow`'s value, a completed
 * `Deferred` and an `Optional` (F3, second review). A [ProviderLease] (it can tell whether a text is a provider's key)
 * may be held only inside `ProviderConnections` and the model gateway ([CONFINED]).
 */
internal class AgentFacingWalk(
    extraViews: List<Class<*>> = emptyList(),
    val ownerOnly: List<Class<*>> = OWNER_ONLY,
    private val checkActions: Boolean = true,
    private val leafPackages: List<String> = emptyList(),
) {
    class Result(val violations: MutableList<String> = ArrayList(), val unreadable: MutableList<String> = ArrayList(), var objects: Int = 0)

    /**
     * The only objects allowed to hold a full store: each exposes the agent-side interface alone.
     * The agents' screen view (`ScreenSessions`) is not one of them: it is walked, down to the
     * screen operator it passes calls to.
     */
    val views: List<Class<*>> = VIEWS + extraViews

    fun isOwnerOnly(type: Class<*>): Boolean = ownerOnly.any { it.isAssignableFrom(type) }

    /** A method taking an id (a value class) is compiled as "name-hash": compared by the name in front. */
    fun ownerActionsOf(type: Class<*>): List<String> =
        type.methods.filter { it.name.substringBefore('-') in OWNER_ONLY_ACTIONS || changesSettings(it) || answersARequest(it) }
            .map { it.name.substringBefore('-') }.distinct()

    /** One object to visit: what reached it ([holder], the class whose field or container held it) and how. */
    private class Step(val value: Any, val path: String, val holder: Class<*>?)

    /** Every object reachable from [root], checking each field's declared type and each value's class. */
    fun walk(root: Any, name: String): Result {
        val result = Result()
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque(listOf(Step(root, name, null)))
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            val value = step.value
            val path = step.path
            // Database internals are judged by their holder: a store may hold them, nothing else may. Before the seen
            // check, so a second holder of an object already met through a store is still judged.
            if (isDatabase(value)) {
                if (!mayHoldDatabase(step.holder)) result.violations += "$path: ${value.javaClass.name} held by ${step.holder?.name}"
                continue
            }
            CONFINED[value.javaClass]?.let { holders ->
                if (holders.none { step.holder?.name?.let { h -> h == it || h.startsWith("$it$") } == true }) {
                    result.violations += "$path: ${value.javaClass.simpleName} held by ${step.holder?.name}"
                }
            }
            if (seen.put(value, Unit) != null) continue
            result.objects++
            val type = value.javaClass
            // Checked on every object reached, the narrow views too: none of them may be able to do what only the owner does.
            if (checkActions) ownerActionsOf(type).forEach { result.violations += "$path: ${type.name} offers $it" }
            if (isOwnerOnly(type)) {
                result.violations += "$path: ${type.name}"
                continue
            }
            // A container passes on the holder that put the value in it.
            val holder = step.holder
            when {
                value is Map<*, *> -> value.entries.forEachIndexed { i, (k, v) ->
                    k?.let { queue += Step(it, "$path{key $i}", holder) }
                    v?.let { queue += Step(it, "$path{$i}", holder) }
                }
                value is Iterable<*> && !followFields(type) -> value.forEachIndexed { i, v -> v?.let { queue += Step(it, "$path[$i]", holder) } }
                type.isArray && !type.componentType.isPrimitive -> (value as Array<*>).forEachIndexed { i, v -> v?.let { queue += Step(it, "$path[$i]", holder) } }
                !followFields(type) -> contentOf(value)?.let { queue += Step(it, "$path.value", holder) }
                else -> {
                    if (type in views) continue
                    for (field in fieldsOf(type)) {
                        val fieldPath = "$path.${field.name}"
                        if (sealedField(type, field)) continue
                        if (isOwnerOnly(field.type)) result.violations += "$fieldPath: declared ${field.type.simpleName}"
                        val held = try {
                            field.isAccessible = true
                            field.get(value)
                        } catch (e: Exception) {
                            result.unreadable += "$fieldPath (${e.javaClass.simpleName})"
                            continue
                        } ?: continue
                        queue += Step(held, fieldPath, field.declaringClass)
                    }
                }
            }
        }
        return result
    }

    /**
     * What a platform container holds, read through its own API (F3, second review): an `AtomicReference`'s or a
     * `java.lang.ref.Reference`'s referent, a `StateFlow`'s value, a completed `Deferred`'s result and an `Optional`'s.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun contentOf(value: Any): Any? = when (value) {
        is java.util.concurrent.atomic.AtomicReference<*> -> value.get()
        is java.lang.ref.Reference<*> -> value.get()
        is kotlinx.coroutines.flow.StateFlow<*> -> value.value
        is kotlinx.coroutines.Deferred<*> -> if (value.isCompleted && !value.isCancelled) runCatching { value.getCompleted() }.getOrNull() else null
        is java.util.Optional<*> -> value.orElse(null)
        else -> null
    }

    /**
     * The app's, the framework's and the fakes' own classes, lambdas and Kotlin's holders are
     * followed; platform classes are leaves. So is the test harness (`TestApp` and its lambdas):
     * it stands in for the production ports and holds the graph itself, which a test is allowed to.
     */
    fun followFields(type: Class<*>): Boolean = !type.name.startsWith(HARNESS) && leafPackages.none { type.name.startsWith(it) } && (
        type.name.startsWith("com.aicontrolcenter.") || type.name.startsWith("kotlin.jvm.internal.") ||
            // Lambdas are classes named after where they are written, so the app's and the framework's are followed above.
            type.name == "kotlin.SynchronizedLazyImpl" || type.name == "kotlin.UnsafeLazyImpl" || type.name == "kotlin.InitializedLazyImpl"
        )

    fun fieldsOf(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java && followFields(current)) {
            current.declaredFields.filterTo(fields) { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            current = current.superclass
        }
        return fields
    }

    companion object {
        const val HARNESS = "com.aicontrolcenter.app.testing."

        val OWNER_ONLY: List<Class<*>> = listOf(
            OwnerRequests::class.java, OwnerGrants::class.java, OwnerFolders::class.java, OwnerOperatorApps::class.java, ScreenControl::class.java,
            OwnerRequestDecisions::class.java, GrantStore::class.java, OwnerRequestRepository::class.java, PermissionRepository::class.java,
            // Owner-capable mutators: adding and removing folders and apps, taking and releasing a folder's access, Resume after Stop.
            SharedFolderRepository::class.java, OperatorAppRepository::class.java, SharedFolders::class.java,
            Class.forName("com.aicontrolcenter.device.screen.StopSwitch"),
            // Phase 6 (rule 3, WP1a): notes to the team, prices, usage history, backup, recovery, routines and folder
            // pick-up, and the two stores that can create work or intake that starts while nobody watches.
            com.aicontrolcenter.core.tasks.OwnerConversation::class.java, com.aicontrolcenter.core.usage.OwnerModelPrices::class.java,
            com.aicontrolcenter.core.usage.OwnerUsage::class.java, com.aicontrolcenter.core.backup.OwnerBackup::class.java,
            com.aicontrolcenter.core.health.OwnerRecovery::class.java, com.aicontrolcenter.core.routines.OwnerRoutines::class.java,
            com.aicontrolcenter.core.routines.RoutineRepository::class.java, com.aicontrolcenter.core.pickup.OwnerPickUpRules::class.java,
            com.aicontrolcenter.core.pickup.PickUpRuleRepository::class.java,
            // Phase 6 (rule 3, WP1d): only the owner writes the settings. Agent-facing code holds the read-only view.
            SettingsRepository::class.java,
            // Security review of wave 1, F3: the owner's provider manager (adds, repoints and removes providers and replaces
            // their keys), the key vault, and the owner's check of an answer against the stored keys. F4: deleting usage.
            com.aicontrolcenter.core.llm.ProviderManager::class.java, SecretVault::class.java,
            com.aicontrolcenter.core.llm.OwnerKeyCheck::class.java, com.aicontrolcenter.core.usage.UsageHousekeeping::class.java,
            // F3, second review: the stores that add, change and remove agents, providers, models and AI apps. Agent-facing
            // code reads them through `ReadOnlyRegistry`'s views.
            com.aicontrolcenter.core.registry.AgentRepository::class.java, com.aicontrolcenter.core.registry.ProviderRepository::class.java,
            com.aicontrolcenter.core.registry.ModelRepository::class.java, com.aicontrolcenter.core.registry.AiAppRepository::class.java,
        )

        val VIEWS: List<Class<*>> = listOf(
            RequestQueueView::class.java, GrantLookupView::class.java, FolderDirectoryView::class.java, AuthorisedAppsView::class.java,
            SharedFolderFilesView::class.java, PickUpFoldersView::class.java,
            // Phase 6 (rule 3): the settings as agents read them; its private source is the owner's writer.
            ReadOnlySettings::class.java,
        ) + com.aicontrolcenter.core.registry.ReadOnlyRegistry.VIEW_CLASSES

        /**
         * Actions that are the owner's alone, by method name: an object that offers one is a
         * violation whatever its class, also inside a view. Round 2 (B2): letting an agent ask again
         * after a refusal. Phase 6: starting over, clearing the usage history, `update` of the
         * settings ([changesSettings]) and answering a request ([answersARequest]).
         */
        val OWNER_ONLY_ACTIONS: Set<String> = setOf("letAskAgain", "startOver", "clearHistory")

        /**
         * The owner-only types an agent-facing holder may keep, where it must: the provider
         * connections hold the vault, to put a provider's key on that provider's own requests, and
         * the provider store, to read the record a key belongs to and store a status a call learned
         * (they offer no way to read a key or change a record: `OperatorDependencyTest` checks it).
         * Anything else holding either fails.
         */
        val SEALED: Map<Class<*>, Set<Class<*>>> = mapOf(
            ProviderConnections::class.java to setOf(SecretVault::class.java, com.aicontrolcenter.core.registry.ProviderRepository::class.java),
        )

        fun sealedField(holder: Class<*>, field: Field): Boolean = SEALED[holder]?.let { field.type in it } == true

        /**
         * Types only some classes may hold (F3, second review): a provider lease can tell whether a text is that
         * provider's key, so only the connections that make it and the model gateway that maps a failure with it hold one.
         */
        val CONFINED: Map<Class<*>, List<String>> = mapOf(
            com.aicontrolcenter.framework.llm.ProviderLease::class.java to
                listOf(ProviderConnections::class.java.name, com.aicontrolcenter.framework.llm.DefaultModelGateway::class.java.name),
        )

        /** Room's database, its open helper and SQLite database, and the app's DAOs and other database classes (not its row classes). */
        fun isDatabase(value: Any): Boolean = value is androidx.room.RoomDatabase || value is androidx.sqlite.db.SupportSQLiteOpenHelper ||
            value is androidx.sqlite.db.SupportSQLiteDatabase ||
            value.javaClass.name.let { it.startsWith("com.aicontrolcenter.data.db.") && !it.startsWith("com.aicontrolcenter.data.db.entity.") }

        /** The Room stores and the data layer's one transaction: the only holders of database internals. */
        fun mayHoldDatabase(holder: Class<*>?): Boolean = holder != null && (
            holder.name.startsWith("com.aicontrolcenter.data.repository.") || holder.name == "com.aicontrolcenter.data.DataLayer\$RoomTransactions"
            )

        /**
         * Phase 6 (rule 3): an `update` that changes the owner's settings, told by its signature, which names
         * [FrameworkSettings]. The bare name would also match the update of a task, a memory or an agent, which the
         * orchestrator and the memory tools may do.
         */
        fun changesSettings(method: Method): Boolean =
            method.name.substringBefore('-') == "update" && method.genericParameterTypes.any { FrameworkSettings::class.java.name in it.typeName }

        /**
         * Security review of wave 1, F10: `answer` of a request ([OwnerRequests.answer]) is the owner's alone. It takes
         * a `RequestId`, a value class, so the compiled name is mangled ("answer-…") and the first parameter is its
         * string: an `answer` taking anything else (a chat's, a tool's) is not this action.
         */
        fun answersARequest(method: Method): Boolean =
            method.name.startsWith("answer-") && method.parameterTypes.firstOrNull() == String::class.java
    }
}
