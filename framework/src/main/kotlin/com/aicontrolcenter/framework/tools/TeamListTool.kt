package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tells an agent who else is on the team: name, role, what each is for, its skills and
 * whether it can run right now. It reveals nothing an agent could not be told anyway: no
 * instructions, models or keys of other agents.
 */
class TeamListTool(private val directory: RegistryDirectory) : Tool {
    override val spec = ToolSpec(
        id = ID,
        functionName = "team_list_agents",
        title = "Team members",
        description = "Lists the agents on the owner's team with each one's role, purpose, skills and whether it can work right now. " +
            "Use it when asked who is on the team or which agent suits a job.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        },
        category = ToolCategory.SYSTEM,
        capability = Capabilities.BASIC_INFO,
        risk = RiskLevel.SAFE,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val team = directory.snapshot().agents
        if (team.isEmpty()) return ToolOutcome.success("There are no agents on the team.")
        val text = buildString {
            appendLine(if (team.size == 1) "The team has 1 agent:" else "The team has ${team.size} agents:")
            for (entry in team) appendLine(describe(entry, isCaller = entry.profile.id == context.agent.id))
        }.trimEnd()
        val data = buildJsonArray {
            for (entry in team) {
                add(
                    buildJsonObject {
                        put("name", entry.profile.name)
                        put("role", entry.profile.kind.label)
                        put("description", entry.profile.description)
                        putJsonArray("skills") { entry.profile.skills.sorted().forEach { add(it) } }
                        put("canRun", entry.availability.isReady)
                    },
                )
            }
        }
        return ToolOutcome.success(text, data)
    }

    private fun describe(entry: AgentEntry, isCaller: Boolean): String = buildString {
        val profile = entry.profile
        append("- ").append(profile.name).append(" (").append(profile.kind.label)
        if (entry.isDesignatedDirector) append(", leads the team")
        if (isCaller) append(", this is you")
        append(")")
        if (profile.description.isNotBlank()) append(": ").append(profile.description.trim())
        if (profile.skills.isNotEmpty()) append(" Skills: ").append(profile.skills.sorted().joinToString(", ")).append('.')
        append(" Can run now: ")
        append(
            when (val availability = entry.availability) {
                is AgentAvailability.Ready -> "yes."
                is AgentAvailability.Disabled -> "no, it is switched off."
                is AgentAvailability.Unavailable -> "no. ${availability.detail}"
            },
        )
    }

    companion object {
        val ID = ToolId("team.list_agents")
    }
}
