package com.aicontrolcenter.app.share

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 34 in the source (WP16): the share code never starts the app's work (no start-up, which could resume interrupted
 * objectives), never reaches a model, a chat, a tool or the orchestrator, never writes settings, never writes as an agent
 * and never makes a memory the owner's own words; of the graph it reads only the owner's memory service, the workspace,
 * the read-only settings and directory, the activity log and the clock.
 */
class ShareSourceScanTest {
    private val sources: List<File> = run {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        File(root, "com/aicontrolcenter/app/share").listFiles { file -> file.extension == "kt" }!!.sortedBy { it.name }
    }

    private fun code(file: File): String = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `the share code is the five files of WP16`() {
        assertEquals(
            listOf("ShareIntake.kt", "ShareReceiverActivity.kt", "ShareSaving.kt", "ShareScreen.kt", "ShareViewModel.kt"),
            sources.map { it.name },
        )
    }

    @Test
    fun `nothing in the share code starts work, reaches a model, a chat, a tool or the orchestrator, or writes settings`() {
        val forbidden = listOf(
            ".start()", "onAppForegrounded", "orchestrator", "Orchestrator", "modelGateway", "ModelGateway", "agentChat", "AgentChat",
            "toolExecutor", "ToolExecutor", "SettingsRepository", "graph.settings.", ".update {", "requestObjective", "requestApproval",
            "MemoryActor.Agent", "ProvenanceOrigin.OWNER", "Intent.FLAG_GRANT", "takePersistableUriPermission", "MainActivity::class",
        )
        val found = sources.flatMap { file ->
            val code = code(file)
            forbidden.filter { it in code.replace("local.update {", "") }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `of the graph the share screen reads only the owner's memory, the workspace, read-only views, the log and the clock`() {
        val host = code(sources.single { it.name == "ShareReceiverActivity.kt" })
        val members = Regex("""graph\.([a-zA-Z]+)""").findAll(host).map { it.groupValues[1] }.toSortedSet()
        assertEquals(sortedSetOf("activityRecorder", "clock", "memoryService", "registryDirectory", "settingsView", "workspace"), members)
        assertTrue("gate" in host && "GateHost(" in host && "RecoveryHost(" in host, "the activity passes the app's gate and shows Recovery")
    }

    @Test
    fun `every memory a share makes is written as the owner's import`() {
        val model = code(sources.single { it.name == "ShareViewModel.kt" })
        val writes = Regex("""memory\.remember\(([^,]+),""").findAll(model).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("MemoryActor.Owner"), writes)
        assertTrue("ProvenanceOrigin.IMPORT" in code(sources.single { it.name == "ShareSaving.kt" }))
    }
}
