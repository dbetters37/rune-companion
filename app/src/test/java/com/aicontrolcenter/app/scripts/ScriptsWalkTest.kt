package com.aicontrolcenter.app.scripts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.AgentFacingWalk
import com.aicontrolcenter.app.di.wiring.ScriptsWiring
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsLimits
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6; rule 3, security review of wave 1): the production script engine and a run's workspace host, which
 * the `scripts.run_js` tool reaches, hold nothing owner-only. `ProductionGraphDependencyTest` walks the engine through
 * the tool; the host is made per run, so it is walked here as a run makes it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScriptsWalkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the production engine and a run's host reach nothing owner-only`() {
        val engine = ScriptsWiring.production(app).engine!!.invoke(JsLimits())
        val workspace = object : JsHost {
            override val canWrite = true

            override fun read(path: String): String = ""

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        val host = RunHost(workspace, JsLimits(), ScriptPipes.Android, Executors.newSingleThreadExecutor(), deadlineMs = { 0L })
        val walk = AgentFacingWalk()
        for ((root, name) in listOf(engine to "engine", host to "run host")) {
            val result = walk.walk(root, name)
            assertEquals(emptyList(), result.violations, name)
            assertEquals(emptyList(), result.unreadable, name)
            assertTrue(result.objects > 3, "$name: ${result.objects} objects")
        }
    }
}
