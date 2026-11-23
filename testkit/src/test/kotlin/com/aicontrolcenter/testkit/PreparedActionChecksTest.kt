package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.PreparedAction
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** A minimal "empty a folder" tool written the way operator tools must be, checked with [PreparedActionChecks]. */
class PreparedActionChecksTest {
    private class EmptyFolder(val folder: File, val names: List<String>) : PreparedAction {
        override suspend fun changedSince(): String? {
            val now = folder.list()?.sorted().orEmpty()
            return if (now == names) null else "the folder now holds ${now.size} files instead of ${names.size}"
        }
    }

    private class EmptyFolderTool(private val root: File) : Tool {
        override val spec = ToolSpec(
            ToolId("test.empty_folder"), "empty_folder", "Empty a folder", "Deletes every file in a folder.",
            buildJsonObject { put("type", "object") }, ToolCategory.WORKSPACE, Capability("workspace.write"), RiskLevel.CONSEQUENTIAL,
        )

        private fun folderOf(arguments: JsonObject): File {
            val path = RelativePath.of(arguments["path"]!!.jsonPrimitive.content)
            return (WorkspaceFile.resolve(root, path) as DocResult.Ok).value.file
        }

        override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
            val folder = folderOf(arguments)
            val names = folder.list()?.sorted().orEmpty()
            return ActionAssessment(RiskLevel.CONSEQUENTIAL, "Delete ${names.size} files in ${folder.name}", names, prepared = EmptyFolder(folder, names))
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome =
            ToolOutcome.failure("This tool runs only with the assessment it was approved with.")

        override suspend fun execute(arguments: JsonObject, context: ToolContext, assessment: ActionAssessment): ToolOutcome {
            val prepared = assessment.prepared as? EmptyFolder ?: return execute(arguments, context)
            prepared.changedSince()?.let { return ToolOutcome.changedSinceApproved(it) }
            prepared.names.forEach { File(prepared.folder, it).delete() }
            return ToolOutcome.success("Deleted ${prepared.names.size} files.")
        }
    }

    @Test
    fun `a tool whose world changed after approval refuses, and runs when nothing changed`() = runTest {
        val root = FakeWorkspaceRoot().directory()
        val old = File(root, "Old").apply { mkdirs() }
        repeat(3) { File(old, "f$it.txt").writeText("x") }
        val tool = EmptyFolderTool(root)
        val args = buildJsonObject { put("path", "Old") }
        val context = ToolContext(Samples.agent())
        val refusal = PreparedActionChecks.assertRefusesAfterChange(
            tool, args, context,
            change = { repeat(300) { File(old, "new$it.txt").writeText("y") } },
            nothingDone = { old.list()!!.size == 303 },
        )
        assertTrue("now holds 303 files" in refusal.content && "Nothing was done" in refusal.content, refusal.content)
        old.listFiles()!!.filter { it.name.startsWith("new") }.forEach { it.delete() }
        PreparedActionChecks.assertRunsWhenUnchanged(tool, args, context)
        assertTrue(old.list()!!.isEmpty())
    }
}
