package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorToolSpecsTest {
    private val fixture = OperatorFixture()
    private val tools = OperatorToolset.create(fixture.dependencies)

    @Test
    fun `every catalogue tool but permissions request is built, once`() {
        val expected = OperatorTools.all.map { it.id.value }.filter { it != "permissions.request" }
        assertEquals(expected.sorted(), tools.map { it.spec.id.value }.sorted())
        assertEquals(tools.size, tools.map { it.spec.id }.toSet().size)
    }

    @Test
    fun `each spec equals its catalogue entry`() {
        for (tool in tools) {
            val info = OperatorTools.info(tool.spec.id)!!
            assertEquals(info.capability, tool.spec.capability, tool.spec.id.value)
            assertEquals(info.risk, tool.spec.risk, tool.spec.id.value)
            assertEquals(info.category, tool.spec.category, tool.spec.id.value)
            assertEquals(info.sendsDataOffDevice, tool.spec.sendsDataOffDevice, tool.spec.id.value)
        }
    }

    @Test
    fun `function names are valid and unique, schemas are closed objects whose required arguments exist`() {
        val names = tools.map { it.spec.functionName }
        assertEquals(names.size, names.toSet().size)
        for (tool in tools) {
            val spec = tool.spec
            assertTrue(Regex("^[A-Za-z0-9_]{1,64}$").matches(spec.functionName), spec.functionName)
            assertEquals(spec.id.value.replace('.', '_'), spec.functionName)
            assertEquals("object", spec.parameters.getValue("type").jsonPrimitive.content, spec.functionName)
            assertFalse(spec.parameters.getValue("additionalProperties").jsonPrimitive.boolean, spec.functionName)
            val properties = spec.parameters.getValue("properties") as JsonObject
            val required = (spec.parameters["required"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
            assertTrue(properties.keys.containsAll(required), "${spec.functionName}: $required not in ${properties.keys}")
            assertTrue(spec.description.length > 40, spec.functionName)
            assertTrue(spec.title.isNotBlank())
        }
    }

    @Test
    fun `no operator tool runs without the assessment it was checked with`() = runTest {
        for (tool in tools) {
            val outcome = tool.execute(JsonObject(emptyMap()), fixture.context)
            assertEquals(ToolOutcome.failure(OperatorTool.NOT_ASSESSED), outcome, tool.spec.id.value)
        }
    }

    @Test
    fun `a prepared call of another tool is refused`() = runTest {
        fixture.put("a.txt", "a")
        val readAssessment = fixture.assess("workspace.read", "path" to "a.txt")
        val outcome = fixture.tool("workspace.trash").execute(OperatorFixture.json("paths" to listOf("a.txt")), fixture.context, readAssessment)
        assertFalse(outcome.ok)
        assertTrue(fixture.file("a.txt").exists())
    }

    @Test
    fun `modifying and consequential tools all declare a risk the catalogue allows`() {
        val raised = tools.filter { it.spec.risk != RiskLevel.SAFE }.map { it.spec.id.value }.toSet()
        val catalogue = OperatorTools.all.filter { it.risk != RiskLevel.SAFE && it.id.value != "permissions.request" }.map { it.id.value }.toSet()
        assertEquals(catalogue, raised)
    }
}
