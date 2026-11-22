package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolRegistry

/** The tools the framework itself provides. Both are read-only and need only the basic-information capability. */
object BuiltInTools {
    /** Safe to switch on for every agent: these are what new agents from the built-in templates start with. */
    val defaultToolIds: Set<ToolId> = setOf(ClockTool.ID, TeamListTool.ID)

    fun create(clock: Clock, directory: RegistryDirectory): List<Tool> = listOf(ClockTool(clock), TeamListTool(directory))

    /** Registers the built-in tools; call once at start-up, before any agent runs. */
    fun registerAll(registry: ToolRegistry, clock: Clock, directory: RegistryDirectory) {
        create(clock, directory).forEach(registry::register)
    }
}
