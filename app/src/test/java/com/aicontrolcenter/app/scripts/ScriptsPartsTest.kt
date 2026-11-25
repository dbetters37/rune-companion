package com.aicontrolcenter.app.scripts

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.wiring.ScriptsWiring
import com.aicontrolcenter.framework.operator.JsLimits
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Phase 6 (WP6): the script service, the wiring, and the rules that keep the script process away from the app's data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScriptsPartsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the service hands out its runner, and outside an isolated process leaves the process's error handler alone`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val service = Robolectric.buildService(ScriptProcessService::class.java).create().get()
        val runner = IScriptRunner.Stub.asInterface(assertNotNull(service.onBind(Intent())))
        assertEquals(Process.myPid(), runner.pid())
        assertSame(before, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `in an isolated process an error nothing caught ends the process quietly`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            ShadowProcess.setIsIsolated()
            Robolectric.buildService(ScriptProcessService::class.java).create()
            val quiet = Thread.getDefaultUncaughtExceptionHandler()
            assertTrue(quiet !== before)
            quiet!!.uncaughtException(Thread.currentThread(), OutOfMemoryError())
            assertTrue(ShadowProcess.wasKilled(Process.myPid()), "the process ends itself")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun `production runs scripts in the isolated engine, and binds nothing until a script runs`() {
        val engine = ScriptsWiring.production(app).engine
        assertIs<IsolatedJsEngine>(assertNotNull(engine).invoke(JsLimits()))
        assertEquals(emptyList(), shadowOf(app).boundServiceConnections)
    }

    private fun sources(dir: String): List<File> {
        val root = listOf(File("src/main/$dir"), File("app/src/main/$dir")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile }.toList()
    }

    @Test
    fun `nothing of the scripts package names the app's graph, data or application`() {
        val files = sources("java/com/aicontrolcenter/app/scripts")
        assertTrue(files.size >= 7, "${files.map { it.name }}")
        val forbidden = Regex("""\bgraph\b|AppGraph|graphIfBuilt|AiControlCenterApp|DataLayer|com\.aicontrolcenter\.data\.|SecretVault|filesDir|getDatabasePath""", RegexOption.IGNORE_CASE)
        for (file in files) {
            val hits = file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
            assertEquals(emptyList(), hits)
        }
    }

    @Test
    fun `the binder interfaces carry only numbers, short texts, pipes and the run's own binders`() {
        val files = sources("aidl/com/aicontrolcenter/app/scripts")
        assertEquals(setOf("IScriptRunner.aidl", "IScriptHost.aidl", "IScriptResult.aidl"), files.map { it.name }.toSet())
        val allowed = setOf("int", "long", "boolean", "String", "ParcelFileDescriptor", "IScriptHost", "IScriptResult", "void")
        val method = Regex("""^\s*(?:oneway\s+)?(\w+)\s+(\w+)\s*\(([^)]*)\)\s*;""")
        var methods = 0
        for (file in files) {
            val text = file.readText()
            assertTrue("Bundle" !in text && "Parcelable" !in text.replace("ParcelFileDescriptor", "") && "Serializable" !in text, file.name)
            for (line in text.lines()) {
                val match = method.find(line) ?: continue
                methods++
                val (returns, _, params) = match.destructured
                assertTrue(returns in allowed, "${file.name}: returns $returns")
                for (param in params.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val type = param.split(Regex("\\s+")).filter { it != "in" }.first()
                    assertTrue(type in allowed, "${file.name}: $param")
                }
            }
        }
        assertEquals(7, methods)
    }
}
