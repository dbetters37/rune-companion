package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.llm.TokenEstimator
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.tools.ClockTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lean requests (Phase 6, WP15, rule 28; the owner's cost request #43).
 *
 * Every model call carries the definitions of the tools it offers, and an agent with many tools
 * (the Operator has 49, about 7,000 estimated tokens) paid for all of them on every call. When an
 * agent's tools would take more than [THRESHOLD_TOKENS], a run offers a menu instead:
 *  - the always-on tools: the tools the framework adds for the run (the team tools inside an
 *    objective's task, [AgentRunRequest.extraToolIds]) and, when the agent has them switched on,
 *    [MemoryTools.SEARCH_ID] and [ClockTool.ID];
 *  - [FUNCTION_NAME] (`tools_open`), whose description lists the agent's other tools by family
 *    (the part of the tool id before the dot: `workspace`, `screen`, …), one line each.
 * Opening a family adds its tools for the rest of the run, after the tools already offered, in
 * the order the families were opened; the answer names them. A call to a tool of a family that is
 * not open yet runs as usual and opens its family. Agents at or under the threshold are offered
 * every tool, as before, and see no menu.
 *
 * What the menu may never do, whatever the model writes:
 *  - offer a tool the agent does not have: the families are made once, when the run starts, from
 *    the agent's own enabled tools and the run's extra tools ([of]); `tools_open` with any other
 *    name is refused and adds nothing;
 *  - authorise anything: offering a definition grants nothing. Every tool call, offered or not,
 *    goes through the tool executor and the permission broker exactly as before, against the
 *    agent as it is stored at the time of the call. `tools_open` itself is SAFE: it runs inside
 *    the runner, reaches no store, no setting, no request and no grant, and changes nothing but
 *    which definitions the next model call carries;
 *  - change the request prefix more than it must: the definitions only grow, by appending, and
 *    `tools_open`'s own definition never changes during a run, so the system prompt and every
 *    definition already offered stay byte-identical from call to call (providers' prompt caches
 *    keep matching up to the newly opened tools).
 *
 * `tools_open` is not a registered tool: it cannot be switched on for an agent, it is not in the
 * owner's tool catalogue and nothing but a runner with a menu answers it. When a registered tool
 * already uses its id or function name, no menu is made (every tool is offered, as before).
 *
 * Not thread-safe: one menu belongs to one run, which calls it from its own coroutine, in order.
 */
class ToolMenu private constructor(
    /** Offered from the first call: the always-on tools (in the run's usual order) and then [FUNCTION_NAME]. */
    private val alwaysOn: List<ToolSpec>,
    /** The agent's other tools by family, families by name, tools by function name. */
    private val families: Map<String, List<ToolSpec>>,
) {
    private val openDefinition: ToolDefinition = openDefinition(families)

    /** Families opened so far, in the order they were opened. */
    private val opened = LinkedHashSet<String>()

    private val familyOfFunction: Map<String, String> =
        families.flatMap { (family, specs) -> specs.map { it.functionName to family } }.toMap()

    /** What the next model call offers: the always-on tools, `tools_open`, then the opened families in opening order. */
    private val offered = ArrayList<ToolDefinition>().apply {
        alwaysOn.forEach { add(definition(it)) }
        add(openDefinition)
    }

    /** The definitions the next model call carries. A new list each time: what was sent is never changed afterwards. */
    val definitions: List<ToolDefinition> get() = offered.toList()

    /** The tools the first call offers by name (always-on tools and `tools_open`), for the system prompt. */
    val firstFunctionNames: List<String> get() = alwaysOn.map { it.functionName } + FUNCTION_NAME

    /** The families `tools_open` lists, by name. */
    val familyNames: List<String> get() = families.keys.toList()

    /** Families opened so far, in opening order. */
    val openedFamilies: List<String> get() = opened.toList()

    /** True for a call this menu answers itself (`tools_open`); every other call goes to the tool executor. */
    fun answers(call: ToolCallRequest): Boolean = call.name == FUNCTION_NAME

    /**
     * Answers a `tools_open` call: opens the family it names (or the family of the tool it names)
     * when that is one of this agent's families, and says which tools are now offered. Anything
     * else is refused with the families there are; nothing is added then.
     */
    fun open(call: ToolCallRequest): ToolOutcome {
        val names = familyNames.joinToString(", ")
        call.argumentsError?.let {
            return ToolOutcome.failure("$FUNCTION_NAME was not run because its arguments could not be read. Give {\"family\": \"<name>\"} with one of: $names.")
        }
        val asked = (call.arguments["family"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (asked.isEmpty()) return ToolOutcome.failure("Name the family to open, one of: $names.")
        val family = familyNamed(asked)
            ?: return ToolOutcome.failure("There is no tool family \"${asked.take(MAX_ECHO)}\" among your tools. Families you can open: $names.")
        val tools = families.getValue(family).joinToString(", ") { it.functionName }
        return if (openFamily(family)) {
            ToolOutcome.success("Opened the $family tools: $tools. You can use them from now on in this turn.")
        } else {
            ToolOutcome.success("The $family tools are already open: $tools.")
        }
    }

    /**
     * The model called [functionName] directly: when it is a tool of a family that is not open
     * yet, that family is opened (the call itself is run by the executor as usual). Returns
     * whether a family was opened. A tool the agent does not have opens nothing.
     */
    fun openFamilyOf(functionName: String): Boolean = familyOfFunction[functionName]?.let(::openFamily) ?: false

    /**
     * Opens, before the first call, the families the conversation so far already used: the
     * families `tools_open` opened in it and those of the tools it called, in the order they
     * were first used. A later turn of a chat then starts with the tools the earlier turns
     * needed, and with the same tool list the last turn ended with (which a provider's cache
     * still holds). Only this agent's families can be opened this way.
     */
    internal fun openUsedIn(messages: List<ChatMessage>) {
        for (message in messages) {
            for (call in message.toolCalls) {
                if (answers(call)) {
                    (call.arguments["family"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?.let(::familyNamed)?.let(::openFamily)
                } else {
                    openFamilyOf(call.name)
                }
            }
        }
    }

    /** [asked] as one of this agent's families: the family's name in any case, or the id or function name of one of its tools. */
    private fun familyNamed(asked: String): String? {
        val name = asked.lowercase()
        families.keys.firstOrNull { it.lowercase() == name }?.let { return it }
        return families.entries.firstOrNull { (_, specs) ->
            specs.any { it.functionName.lowercase() == name || it.id.value.lowercase() == name }
        }?.key
    }

    private fun openFamily(family: String): Boolean {
        val specs = families[family] ?: return false
        if (!opened.add(family)) return false
        specs.forEach { offered += definition(it) }
        return true
    }

    companion object {
        /** The function name the model calls to open a family. Not a registered tool. */
        const val FUNCTION_NAME = "tools_open"

        /** Id of [SPEC]; no registered tool may use it. */
        val ID = ToolId("tools.open")

        /** What the owner sees for a `tools_open` call (chat, task transcript). */
        const val TITLE = "Open more tools"

        /** Above this many estimated tokens of tool definitions a run offers a menu (rule 28). */
        const val THRESHOLD_TOKENS = 2_500

        /** Offered from the first call when the agent has them switched on, besides the run's extra tools. */
        val ALWAYS_ON: Set<ToolId> = setOf(MemoryTools.SEARCH_ID, ClockTool.ID)

        /**
         * Describes `tools_open` for listeners and screens ([AgentRunListener.onToolStarted],
         * [com.aicontrolcenter.framework.tools.ToolExecution.spec]); it is never registered. SAFE,
         * basic information: it only changes which tools the next model call lists.
         */
        val SPEC: ToolSpec = ToolSpec(
            id = ID, functionName = FUNCTION_NAME, title = TITLE,
            description = "Adds a family of the agent's own tools to the tools offered for the rest of its turn.",
            parameters = schema(emptyList()), category = ToolCategory.SYSTEM, capability = Capabilities.BASIC_INFO, risk = RiskLevel.SAFE,
        )

        /**
         * Estimated tokens of [definitions] as rule 28 counts them ([TokenEstimator]: about four
         * characters of Latin text per token). For tests and for screens that show what tools cost.
         */
        fun estimatedTokens(definitions: List<ToolDefinition>): Int = tokens(definitions)

        /** Estimated tokens of [text], as [estimatedTokens] counts them. */
        fun estimatedTokens(text: String): Int = TokenEstimator.tokens(text)

        /** The family of a tool: its id up to the first dot (the whole id when it has none). */
        fun familyOf(id: ToolId): String = id.value.substringBefore('.')

        /**
         * The menu for a run offering [tools] (the agent's enabled tools that exist and the run's
         * extra tools, in the run's usual order), or null when every tool is to be offered at once:
         * their definitions take at most [thresholdTokens], every tool is always-on, a menu would
         * not be smaller, or a registered tool uses `tools_open`'s id or function name.
         */
        internal fun of(
            tools: List<ToolSpec>,
            extraToolIds: Set<ToolId>,
            registry: ToolRegistry,
            thresholdTokens: Int = THRESHOLD_TOKENS,
        ): ToolMenu? {
            val all = tools.map(::definition)
            val allTokens = tokens(all)
            if (allTokens <= thresholdTokens) return null
            if (registry.byId(ID) != null || registry.byFunctionName(FUNCTION_NAME) != null) return null
            val (alwaysOn, others) = tools.partition { it.id in extraToolIds || it.id in ALWAYS_ON }
            if (others.isEmpty()) return null
            val families = others.groupBy { familyOf(it.id) }.toSortedMap().mapValues { (_, specs) -> specs.sortedBy { it.functionName } }
            val menu = ToolMenu(alwaysOn, LinkedHashMap(families))
            return menu.takeIf { tokens(it.offered) < allTokens }
        }

        internal fun definition(spec: ToolSpec) = ToolDefinition(spec.functionName, spec.description, spec.parameters)

        private fun tokens(definitions: List<ToolDefinition>): Int = TokenEstimator.tokens(emptyList(), definitions)

        private fun openDefinition(families: Map<String, List<ToolSpec>>) = ToolDefinition(
            name = FUNCTION_NAME,
            description = buildString {
                append("Adds more of your tools. To keep each request small, only some of your tools are listed at first; ")
                append("the others are in the families below. Call this with the family the task needs: its tools are then listed ")
                append("for the rest of this turn. Open only what you need. Families:")
                for ((family, specs) in families) {
                    append("\n- ").append(family).append(": ").append(specs.joinToString("; ") { it.title })
                }
            },
            parameters = schema(families.keys.toList()),
        )

        private fun schema(families: List<String>): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("family") {
                    put("type", "string")
                    put("description", "The family to open.")
                    if (families.isNotEmpty()) putJsonArray("enum") { families.forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("family") }
            put("additionalProperties", false)
        }

        private const val MAX_ECHO = 60
    }
}
