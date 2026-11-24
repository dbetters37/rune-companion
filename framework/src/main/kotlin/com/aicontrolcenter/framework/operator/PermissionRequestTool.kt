package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `permissions.request`: an agent asks the owner for a capability, giving its reason. Its only
 * dependency is [CapabilityRequester]: it raises a request and returns at once, never waits for
 * the answer and can grant nothing. The owner sees the reason as the agent's quoted words. Its
 * spec is built from its [OperatorTools] entry (SAFE, basic information, nothing leaves the
 * device).
 */
class PermissionRequestTool(private val requester: CapabilityRequester) : Tool {
    private val info = checkNotNull(OperatorTools.info(ID)) { "permissions.request is missing from OperatorTools." }

    override val spec = ToolSpec(
        id = ID,
        functionName = "permissions_request",
        title = "Ask the owner for a permission",
        description = "Asks the owner to let you use a permission you do not have yet, with your reason. The owner answers later in the app; " +
            "this does not wait and you are not told the answer. Ask only when your task needs it, then go on without it or end your " +
            "turn saying what you are waiting for. After the owner refuses a permission, asking for it again within 10 minutes is " +
            "refused without asking the owner. Folder permissions name one shared folder (folder_id) and screen permissions one " +
            "app (app, its package name). Permissions: " +
            OperatorCapabilities.all.joinToString("; ") { "${it.capability.id} (${it.title})" } + ".",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("capability") {
                    put("type", "string")
                    put("description", "The permission's id, e.g. \"folders.read\".")
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Why you need it, in one or two sentences for the owner (at most ${CapabilityRequester.MAX_REASON_CHARS} characters).")
                }
                putJsonObject("folder_id") {
                    put("type", "string")
                    put("description", "For a folder permission: the shared folder's id.")
                }
                putJsonObject("app") {
                    put("type", "string")
                    put("description", "For a screen permission: the app's package name, e.g. \"com.example.notes\".")
                }
            }
            putJsonArray("required") {
                add("capability")
                add("reason")
            }
            put("additionalProperties", false)
        },
        category = info.category,
        capability = info.capability,
        risk = info.risk,
        sendsDataOffDevice = info.sendsDataOffDevice,
    )

    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val capability = text(arguments, "capability") ?: "a permission"
        return ActionAssessment(spec.risk, "Ask the owner for \"${capability.take(MAX_ECHO)}\"")
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val capability = text(arguments, "capability")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolOutcome.failure("Name the permission to ask for, e.g. \"folders.read\".")
        val reason = text(arguments, "reason").orEmpty()
        val folder = text(arguments, "folder_id")?.trim()?.takeIf { it.isNotEmpty() }
        val app = text(arguments, "app")?.trim()?.takeIf { it.isNotEmpty() }
        val constraint = try {
            when {
                folder != null && app != null -> return ToolOutcome.failure("Name either a folder or an app, not both.")
                folder != null -> CapabilityConstraint.Folder(FolderId(folder))
                app != null -> CapabilityConstraint.App(app)
                else -> CapabilityConstraint.None
            }
        } catch (e: IllegalArgumentException) {
            return ToolOutcome.failure("\"${(folder ?: app).orEmpty().take(MAX_ECHO)}\" is not a ${if (folder != null) "folder id" else "package name"}.")
        }
        return when (val result = requester.ask(context, Capability(capability.take(MAX_CAPABILITY)), constraint, reason)) {
            is AskResult.Raised -> ToolOutcome.success(
                "Asked the owner for \"$capability\". The owner answers in the app when they can; you are not told when. " +
                    "Do not ask again: go on without it, or end your turn saying what you are waiting for.",
            )
            is AskResult.AlreadyPending -> ToolOutcome.success("You already asked the owner for \"$capability\"; the question is still waiting for an answer. Do not ask again.")
            AskResult.AlreadyGranted -> ToolOutcome.success("You already have \"$capability\" for this; no need to ask.")
            is AskResult.Refused -> ToolOutcome.failure("Not asked: ${result.reason}")
        }
    }

    private fun text(arguments: JsonObject, name: String): String? = (arguments[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The id and registration. */
    companion object {
        val ID = ToolId("permissions.request")
        private const val MAX_ECHO = 80
        private const val MAX_CAPABILITY = 120

        /** Registers the tool; call once at start-up. @throws IllegalArgumentException if it is already registered. */
        fun register(registry: ToolRegistry, requester: CapabilityRequester) = registry.register(PermissionRequestTool(requester))
    }
}
