package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks for the rule of [com.aicontrolcenter.core.tools.PreparedAction]: what runs is what was approved. */
object PreparedActionChecks {
    /**
     * Assesses a call as the executor does, applies [change] to the world (the wait for the
     * owner), then executes with the same assessment. The tool must have prepared its action,
     * must refuse, and [nothingDone] must still hold. Returns the refusal.
     */
    suspend fun assertRefusesAfterChange(
        tool: Tool,
        arguments: JsonObject,
        context: ToolContext,
        change: suspend () -> Unit,
        nothingDone: suspend () -> Boolean,
    ): ToolOutcome {
        val assessment = tool.assess(arguments, context)
        assertNotNull(assessment.prepared, "${tool.spec.id} must prepare its action in assess")
        change()
        val outcome = tool.execute(arguments, context, assessment)
        assertFalse(outcome.ok, "${tool.spec.id} acted although the world changed after approval: ${outcome.content}")
        assertTrue(nothingDone(), "${tool.spec.id} changed something although it refused")
        return outcome
    }

    /** The other half: with nothing changed in between, the prepared action runs. */
    suspend fun assertRunsWhenUnchanged(tool: Tool, arguments: JsonObject, context: ToolContext): ToolOutcome {
        val assessment = tool.assess(arguments, context)
        assertNotNull(assessment.prepared, "${tool.spec.id} must prepare its action in assess")
        val outcome = tool.execute(arguments, context, assessment)
        assertTrue(outcome.ok, "${tool.spec.id} refused although nothing changed: ${outcome.content}")
        return outcome
    }
}
