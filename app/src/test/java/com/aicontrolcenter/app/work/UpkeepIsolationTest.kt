package com.aicontrolcenter.app.work

import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.di.wiring.PickUpStepPorts
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.data.DataLayer
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upkeep job and folder pick-up run while nobody watches, so what they can reach is fixed by types, not by
 * discipline (rule 1; security review of wave 1, F11): their ports hold no data layer, no context, no application
 * object and no graph, and their sources never name the application or the graph. Receivers in app/routines only
 * signal: they never name them either.
 */
class UpkeepIsolationTest {
    /** Types through which the owner's stores or the agents' graph could be reached. */
    private val wide: List<Class<*>> = listOf(
        DataLayer::class.java, android.content.Context::class.java, AiControlCenterApp::class.java, AppGraph::class.java, Ports::class.java,
    )

    private fun assertNarrow(type: Class<*>) {
        val fields = type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(fields.isNotEmpty(), type.simpleName)
        for (field in fields) {
            for (bad in wide) assertTrue(!bad.isAssignableFrom(field.type) && field.type != Any::class.java, "${type.simpleName}.${field.name} is a ${field.type.simpleName}")
        }
        for (constructor in type.constructors) {
            for (parameter in constructor.parameterTypes) assertTrue(wide.none { it.isAssignableFrom(parameter) }, "${type.simpleName} takes a ${parameter.simpleName}")
        }
    }

    @Test
    fun `the upkeep job's ports and the pick-up step's ports are narrow`() {
        assertNarrow(UpkeepPorts::class.java)
        assertNarrow(PickUpStepPorts::class.java)
        for (constructor in UpkeepGraph::class.java.constructors) {
            assertTrue(constructor.parameterTypes.none { p -> wide.any { it.isAssignableFrom(p) } }, "UpkeepGraph takes only its ports and steps")
        }
        val upkeepSteps = RoutinesWiring::class.java.methods.single { it.name == "upkeepSteps" }
        assertEquals(listOf(PickUpStepPorts::class.java), upkeepSteps.parameterTypes.toList(), "pick-up steps are built from their ports alone")
    }

    private val sources: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** The job, its graph and its steps, and everything in app/routines (WP17's receivers and runners). */
    private fun watchedFiles(): List<File> {
        val app = File(sources, "com/aicontrolcenter/app")
        val upkeep = File(app, "work").listFiles().orEmpty().filter { it.name.startsWith("Upkeep") && it.name.endsWith(".kt") }
        val routines = File(app, "routines").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val pickUp = app.walkTopDown().filter { it.isFile && it.name.startsWith("PickUp") && it.name.endsWith(".kt") }.toList()
        return (upkeep + routines + pickUp).distinct()
    }

    /** Names that reach the application object, the graph or the data layer. Receivers may only signal. */
    private val forbidden = listOf(
        Regex("\\bAiControlCenterApp\\b"), Regex("\\bgraphIfBuilt\\b"), Regex("\\bgraph\\b"), Regex("\\bAppGraph\\b"), Regex("\\bDataLayer\\b"),
        Regex("\\bdataLayer\\b"), Regex("\\bapplicationContext\\b"),
    )

    @Test
    fun `the upkeep job, folder pick-up and the routine receivers never name the application, the graph or the data layer`() {
        val files = watchedFiles()
        assertTrue(files.any { it.name == "UpkeepGraph.kt" } && files.any { it.name == "UpkeepJobService.kt" } && files.any { it.name == "RoutineAlarmReceiver.kt" })
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .flatMap { (n, line) -> forbidden.filter { it.containsMatchIn(line) }.map { "${file.name}:${n + 1}: ${it.pattern} in \"${line.trim()}\"" } }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `the scan sees what it looks for`() {
        val line = "val g = (context.applicationContext as AiControlCenterApp).graph"
        assertTrue(forbidden.count { it.containsMatchIn(line) } >= 3)
        assertTrue(forbidden.none { it.containsMatchIn("val paragraph = upgraded") }, "only whole words")
    }
}
