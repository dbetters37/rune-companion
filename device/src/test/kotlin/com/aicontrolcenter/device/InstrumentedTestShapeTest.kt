package com.aicontrolcenter.device

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumented tests (`src/androidTest`) are compiled by every build but run only on a device,
 * which the ordinary test run does not have. A mistake that makes such a class invalid for JUnit
 * therefore goes unseen: the class compiles, and on a device none of its tests run.
 *
 * That happened: `@After fun cleanUp() = runBlocking { …; root.deleteRecursively() }` takes its
 * return type from the last expression (a Boolean). JUnit 4 requires `@Test`, `@Before`, `@After`,
 * `@BeforeClass` and `@AfterClass` methods to return nothing and rejects the whole class otherwise
 * ("Method cleanUp() should be void"), so the device test of the shell runner ran zero tests.
 *
 * The rule checked here, over the sources of every module: such a function has a block body, or
 * says `: Unit` itself. An expression body without a stated type is refused whatever it returns
 * today, because its type changes silently with its last line.
 */
class InstrumentedTestShapeTest {

    private data class Finding(val function: String, val problem: String)

    /** The repository's top folder: the nearest folder upwards that holds the settings file. */
    private val repository: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun instrumentedSources(): List<File> = repository.listFiles().orEmpty()
        .map { File(it, "src/androidTest") }.filter { it.isDirectory }
        .flatMap { folder -> folder.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .sortedBy { it.path }

    @Test
    fun `every JUnit method of an instrumented test returns nothing, visibly`() {
        val sources = instrumentedSources()
        assertTrue(
            sources.any { it.name == "ShellLeftoversDeviceTest.kt" },
            "the instrumented sources were not found from ${repository.path}: ${sources.map { it.name }}",
        )

        val findings = sources.flatMap { file ->
            findings(file.readText()).map { "${file.relativeTo(repository).path}: ${it.function}: ${it.problem}" }
        }

        assertEquals(
            emptyList(), findings,
            "JUnit runs no test of a class with such a method. Give it a block body, or write \": Unit\" before the \"=\".",
        )
    }

    @Test
    fun `the check sees the mistake it was written for, and the ways around it`() {
        // The method as it was committed in round 3, word for word.
        val asCommitted = """
            @After
            fun cleanUp() = runBlocking {
                runner.stopLeftovers()
                root.deleteRecursively()
            }
        """
        assertEquals(listOf("cleanUp"), findings(asCommitted).map { it.function })

        val alsoWrong = """
            class T {
                @Before fun a() = runBlocking { 1 }
                @org.junit.Test
                fun b() = Unit
                @Test(timeout = 5_000) @Ignore("why (and how)")
                internal fun `c d`(): Boolean = true
                @Ignore @Test fun e(): Int { return 1 }
                companion object {
                    @JvmStatic @BeforeClass fun f() = prepare()
                    @AfterClass
                    @JvmStatic
                    fun g() = runBlocking<Unit> { }
                }
                @After fun h(): kotlin.Boolean = false
            }
        """
        assertEquals(listOf("a", "b", "c d", "e", "f", "g", "h"), findings(alsoWrong).map { it.function })

        val right = """
            class T {
                @Before fun a(): Unit = runBlocking { 1 }
                @Test
                fun b() {
                    val x = runBlocking { 1 }
                }
                @Test(timeout = 5_000)
                fun c(): Unit = runTest { }
                @After fun d(): kotlin.Unit { }
                // @Test fun inAComment() = 1
                /* @Test fun inABlockComment() = 1 */
                fun notATest() = runBlocking { 1 }
                private fun helper(a: Int = (1 + 2)) = a
                @Suppress("unused") fun alsoNotATest() = 2
                @Test
                fun e(
                ) { }
            }
        """
        assertEquals(emptyList(), findings(right))
    }

    /** The JUnit-run functions in [source] that do not visibly return nothing. */
    private fun findings(source: String): List<Finding> {
        val text = source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, "")
        // These annotations stand only in front of functions, so the next "fun" after one is the function it marks.
        val functions = RUN_BY_JUNIT.findAll(text).mapNotNull { FUNCTION.find(text, it.range.last) }.distinctBy { it.range.first }
        return functions.mapNotNull { match ->
            val name = match.groupValues[1].trim('`')
            val afterParameters = closing(text, match.range.last) + 1
            val bodyAt = text.indexOfAny(charArrayOf('=', '{'), afterParameters)
            if (bodyAt < 0) return@mapNotNull Finding(name, "has no body")
            val type = text.substring(afterParameters, bodyAt).trim().removePrefix(":").trim()
            when {
                type == "Unit" || type == "kotlin.Unit" -> null
                type.isNotEmpty() -> Finding(name, "returns $type")
                text[bodyAt] == '=' -> Finding(name, "has an expression body (\"= …\") and does not say \": Unit\", so it returns whatever its last line gives")
                else -> null
            }
        }.toList()
    }

    /** The index of the parenthesis that closes the one at [open]. */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }

    private companion object {
        val RUN_BY_JUNIT = Regex("""@(?:org\.junit\.)?(?:Test|Before|After|BeforeClass|AfterClass)\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** A function's name, up to the parenthesis that opens its parameters. */
        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(`[^`]+`|\w+)\s*\(""")
    }
}
