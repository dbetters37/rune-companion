package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import java.time.ZoneId

/**
 * The tools through which agents use memory: search, save and share.
 *
 * Both memory capabilities are granted by default because they do not decide what an agent
 * may see: the memory service does, on every call, from the agent's memory policy (its own
 * private memory when enabled, the spaces the owner opened to it for reading or writing, and
 * its own task memory). The capabilities only say whether an agent may use memory at all.
 */
object MemoryTools {
    val SEARCH_ID = ToolId("memory.search")
    val SAVE_ID = ToolId("memory.save")
    val SHARE_ID = ToolId("memory.share")

    val READ = Capability("memory.read")
    val WRITE = Capability("memory.write")

    /** Switched on in the built-in templates. Sharing is left for the owner to switch on per agent. */
    val defaultToolIds: Set<ToolId> = setOf(SEARCH_ID, SAVE_ID)

    val capabilities: List<CapabilityInfo> = listOf(
        CapabilityInfo(
            READ, "Read memory",
            "Search the agent's own memories and the shared knowledge spaces its memory settings let it read.",
            ToolCategory.MEMORY, grantedByDefault = true,
        ),
        CapabilityInfo(
            WRITE, "Write memory",
            "Save and share memories: into the agent's own memory, and into the shared spaces its memory settings let it write.",
            ToolCategory.MEMORY, grantedByDefault = true,
        ),
    )

    /**
     * The memory tools. [agents] is where each call reads the calling agent's current profile;
     * [zone] decides the calendar days shown for memories and is read on every call.
     */
    fun create(memory: MemoryService, agents: AgentRepository, clock: Clock, zone: () -> ZoneId = ZoneId::systemDefault): List<Tool> {
        val support = MemoryToolSupport(memory, agents)
        return listOf(MemorySearchTool(support, zone), MemorySaveTool(support, clock), MemoryShareTool(support))
    }

    /**
     * Describes the memory capabilities (unless already described) and registers the memory
     * tools. Call once at start-up, before any agent runs, next to the built-in tools.
     * @throws IllegalArgumentException if a memory tool is already registered.
     */
    fun registerAll(
        registry: ToolRegistry,
        memory: MemoryService,
        capabilityRegistry: CapabilityRegistry,
        agents: AgentRepository,
        clock: Clock,
        zone: () -> ZoneId = ZoneId::systemDefault,
    ) {
        for (info in capabilities) {
            if (capabilityRegistry.info(info.capability) == null) capabilityRegistry.register(info)
        }
        create(memory, agents, clock, zone).forEach(registry::register)
    }
}
