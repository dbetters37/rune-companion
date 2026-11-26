package com.aicontrolcenter.app

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stub gate of wave 3 (Phase 6, rule 18, F25; WP8): lists every `@Phase6Stub` left in the app's code and fails on a
 * stub whose package is done.
 *
 * Which packages may still have stubs is [STILL_BUILDING], changed by the integrator at each merge point:
 *  - wave 3, part 1 being built: every wave-3 package;
 *  - after part 1 was merged: part 2's packages and WP8 only (WP9, WP10b, WP10c, WP16, WP8);
 *  - the final gate (now, after part 2 was merged): none, so not one stub is left (the plan's `NoPhase6StubsTest`), and
 *    the stand-in screen is gone: no route, slot or control leads to a screen that is not built.
 *
 * `-Paicc.stubGate=final` (app/build.gradle.kts passes it on as a system property) asks for the same at any time.
 */
class Phase6StubGateTest {
    /** One stub: where, and the package that fills it. */
    private data class Stub(val file: String, val line: Int, val owner: String)

    private val sources: List<File> by lazy {
        val app = listOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
        listOf("src/main", "src/debug").map { File(app, it) }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private val stubs: List<Stub> by lazy {
        sources.filter { it.name != "Phase6Stub.kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                USE.find(line.substringBefore("//"))?.let { Stub(file.path.substringAfter("src/"), index + 1, it.groupValues[1]) }
            }
        }
    }

    private val finalGate: Boolean get() = System.getProperty("aicc.stubGate") == "final"

    @Test
    fun `the stubs left are listed, each owned by a wave-3 package`() {
        assertTrue(sources.size > 100, "the scan found only ${sources.size} files; is it reading the app's source?")
        // The list, for the test report and the integrator.
        println("Phase 6 stubs left: ${stubs.size}")
        stubs.groupBy { it.owner }.toSortedMap().forEach { (owner, mine) ->
            println("  $owner (${mine.size}): " + mine.joinToString(", ") { "${it.file}:${it.line}" })
        }
        val unknown = stubs.filter { it.owner !in PACKAGES }
        assertEquals(emptyList(), unknown, "a stub names a package that is not one of wave 3's")
    }

    @Test
    fun `no stub is left of a package that is done`() {
        val allowed = if (finalGate) emptySet() else STILL_BUILDING
        val left = stubs.filter { it.owner !in allowed }
        assertTrue(left.isEmpty(), "Stubs left of packages that are done:\n" + left.joinToString("\n") { "${it.owner}: ${it.file}:${it.line}" })
    }

    @Test
    fun `the final gate - every wave-3 package is done, not one stub is left, and the stand-in screen is gone`() {
        assertEquals(emptySet(), STILL_BUILDING, "wave 3 is merged: the final gate allows no stub")
        assertEquals(emptyList(), stubs, "not one @Phase6Stub is left in the app's code")
        val standIn = sources.filter { file -> file.name == "StubScreen.kt" || file.readText().contains("StubScreen(") }
        assertEquals(emptyList(), standIn.map { it.path }, "nothing shows the stand-in screen")
    }

    @Test
    fun `the scan finds a stub in any of its forms`() {
        val found = listOf(
            "@file:Phase6Stub(\"WP9\")",
            "@Phase6Stub(\"WP10a\")",
            "@Phase6Stub(owner = \"WP16\")",
            "  @Phase6Stub( \"WP8\" )",
            "// @Phase6Stub(\"WP11a\") is only a comment",
        ).mapNotNull { USE.find(it.substringBefore("//"))?.groupValues?.get(1) }
        assertEquals(listOf("WP9", "WP10a", "WP16", "WP8"), found)
    }

    private companion object {
        /** A use of the annotation, on a file or a declaration, with its owner. */
        val USE = Regex("""@(?:file:)?Phase6Stub\(\s*(?:owner\s*=\s*)?"([^"]+)"\s*\)""")

        /** Wave 3's packages (scratchpad/phase6/wave3-ownership.md). */
        val PACKAGES = setOf("WP8", "WP9", "WP10a", "WP10b", "WP10c", "WP11a", "WP11b", "WP16")

        /** The packages that may still have stubs now: none (wave 3 is merged; the final gate). */
        val STILL_BUILDING: Set<String> = emptySet()
    }
}
