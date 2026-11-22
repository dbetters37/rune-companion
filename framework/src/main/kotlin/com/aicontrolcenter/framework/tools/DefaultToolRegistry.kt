package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolRegistry

/** The tools known to the framework, by id and by the function name models call them with. Safe for concurrent use. */
class DefaultToolRegistry : ToolRegistry {
    private val lock = Any()
    private val byId = LinkedHashMap<ToolId, Tool>()
    private val byFunction = HashMap<String, Tool>()

    override fun all(): List<Tool> = synchronized(lock) { byId.values.toList() }

    override fun byId(id: ToolId): Tool? = synchronized(lock) { byId[id] }

    override fun byFunctionName(name: String): Tool? = synchronized(lock) { byFunction[name] }

    override fun register(tool: Tool) {
        val spec = tool.spec
        require(FUNCTION_NAME.matches(spec.functionName)) {
            "Function name \"${spec.functionName}\" of tool ${spec.id} must be 1 to 64 letters, digits or underscores."
        }
        synchronized(lock) {
            require(spec.id !in byId) { "A tool with id ${spec.id} is already registered." }
            require(spec.functionName !in byFunction) { "A tool with function name \"${spec.functionName}\" is already registered." }
            byId[spec.id] = tool
            byFunction[spec.functionName] = tool
        }
    }

    private companion object {
        /** What every provider accepts as a function name. */
        val FUNCTION_NAME = Regex("[A-Za-z0-9_]{1,64}")
    }
}
