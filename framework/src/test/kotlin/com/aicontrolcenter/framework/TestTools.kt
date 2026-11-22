package com.aicontrolcenter.framework

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.util.concurrent.CopyOnWriteArrayList

/** A tool whose behaviour is a lambda. It takes one optional string argument, "text". */
class LambdaTool(
    name: String,
    capability: Capability = Capabilities.BASIC_INFO,
    risk: RiskLevel = RiskLevel.SAFE,
    sendsDataOffDevice: Boolean = false,
    required: List<String> = emptyList(),
    private val assessed: RiskLevel = risk,
    private val body: suspend (JsonObject) -> ToolOutcome,
) : Tool {
    val calls = CopyOnWriteArrayList<JsonObject>()

    override val spec = ToolSpec(
        id = ToolId("test.$name"), functionName = "test_$name", title = "Test $name", description = "A test tool.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("count") { put("type", "integer") }
            }
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        },
        category = ToolCategory.SYSTEM, capability = capability, risk = risk, sendsDataOffDevice = sendsDataOffDevice,
    )

    override suspend fun assess(arguments: JsonObject, context: ToolContext) = ActionAssessment(assessed, "Run ${spec.title}")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        calls += arguments
        return body(arguments)
    }
}

/** A model whose streamed output is whatever flow the test builds; every request is recorded. */
class StreamingClient(private val stream: (request: ChatRequest, callIndex: Int) -> Flow<ChatChunk>) : LlmClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse = throw UnsupportedOperationException("this test client only streams")

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> {
        requests += request
        return stream(request, requests.size - 1)
    }
}
