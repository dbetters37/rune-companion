package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.testkit.FakeAppActions
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeMediaLibrary
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.FakeScreenOperator
import com.aicontrolcenter.testkit.FakeSharedFolders
import com.aicontrolcenter.testkit.FakeShellRunner
import com.aicontrolcenter.testkit.FakeTermuxRunner
import com.aicontrolcenter.testkit.FakeWebReader
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every operator tool over the testkit fakes and a real [LocalWorkspace] in a temporary directory. */
internal class OperatorFixture(
    initialScreen: FakeScreen? = null,
    limits: OperatorLimits = OperatorLimits(),
    workspaceLimits: WorkspaceLimits = WorkspaceLimits(),
) {
    val clock = FakeClock()
    val root: File = Files.createTempDirectory("aicc-ws").toFile()
    val workspace = LocalWorkspace(FakeWorkspaceRoot(root), clock, workspaceLimits)
    val folderRepository = InMemorySharedFolderRepository()
    val sharedFolders = FakeSharedFolders(folderRepository)
    val media = FakeMediaLibrary()
    val apps = FakeAppActions(OWN)
    val web = FakeWebReader()
    val screen = FakeScreenOperator(screen = initialScreen, ownPackage = OWN, authorisedApps = emptyList())
    val operatorApps = InMemoryOperatorAppRepository()
    val shell = FakeShellRunner()
    val termux = FakeTermuxRunner()

    @Volatile var extraProtected: Set<String> = emptySet()

    /** What the tools logged as possibly leaving the phone: (title, destination). */
    val egress = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    val dependencies = OperatorToolDependencies(
        workspace = workspace, sharedFolders = sharedFolders, folders = folderRepository.asDirectory(), media = media, apps = apps,
        web = web, screen = screen, authorisedApps = operatorApps.asAuthorisedApps(), protectedPackages = { ProtectedPackages(OWN, extraProtected) },
        shell = shell, termux = termux, clock = clock, ownPackage = OWN, limits = limits,
        egress = OperatorEgress { _, title, _, info -> egress += title to info.destinationHost },
    )
    val tools: Map<String, Tool> = OperatorToolset.create(dependencies).associateBy { it.spec.id.value }
    val context = ToolContext(Samples.agent(id = "agt_operator", name = "Operator"))

    fun tool(id: String): Tool = tools.getValue(id).also { require(OperatorTools.info(it.spec.id) != null) }

    suspend fun assess(id: String, vararg args: Pair<String, Any?>): ActionAssessment = tool(id).assess(json(*args), context)

    /** Assesses and executes with the same assessment, as the executor does. */
    suspend fun run(id: String, vararg args: Pair<String, Any?>): Pair<ActionAssessment, ToolOutcome> {
        val arguments = json(*args)
        val assessment = tool(id).assess(arguments, context)
        return assessment to tool(id).execute(arguments, context, assessment)
    }

    suspend fun ok(id: String, vararg args: Pair<String, Any?>): ToolOutcome {
        val (_, outcome) = run(id, *args)
        assertTrue(outcome.ok, "$id failed: ${outcome.content}")
        return outcome
    }

    suspend fun fails(id: String, vararg args: Pair<String, Any?>): ToolOutcome {
        val (_, outcome) = run(id, *args)
        assertTrue(!outcome.ok, "$id should have failed: ${outcome.content}")
        return outcome
    }

    /** Asserts that [id] is rejected while assessing, before anything runs; returns the reason. */
    suspend fun rejected(id: String, vararg args: Pair<String, Any?>): String {
        val error = kotlin.runCatching { assess(id, *args) }.exceptionOrNull()
        assertIs<ToolCallRejected>(error, "expected $id to be rejected")
        return error.message!!
    }

    fun file(path: String): File = File(root, path)

    fun put(path: String, text: String): File = file(path).also { it.parentFile.mkdirs(); it.writeText(text) }

    suspend fun shareFolder(name: String = "Reports", readOnly: Boolean = false): SharedFolder {
        val tree = "content://tree/${name.lowercase()}"
        sharedFolders.grant(tree, name)
        val folder = SharedFolder(FolderId("fld_${name.lowercase()}"), tree, name, addedAt = clock.now(), readOnly = readOnly)
        folderRepository.upsert(folder)
        return folder
    }

    /** Authorises [packageName] for screen operation in both the repository the tools read and the fake gateway. */
    suspend fun authorise(packageName: String, label: String = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }) {
        operatorApps.upsert(OperatorApp(packageName, label, clock.now()))
        screen.authorised += packageName
    }

    companion object {
        const val OWN = "com.aicontrolcenter.app"

        fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

        fun element(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { element(it) })
            is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to element(value) })
            else -> error("unsupported argument $v")
        }

        fun path(p: String) = RelativePath.of(p)
    }
}

/** The assessment's details and summary as one text, for assertions on previews. */
internal val ActionAssessment.preview: String get() = (listOf(summary) + details).joinToString("\n")

internal fun assertPrepared(a: ActionAssessment) = assertTrue(a.prepared is PreparedCall, "assessment carries no prepared call: ${a.summary}")

internal fun assertRisk(expected: com.aicontrolcenter.core.tools.RiskLevel, a: ActionAssessment) = assertEquals(expected, a.risk, a.preview)
