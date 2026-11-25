package com.aicontrolcenter.app.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * docs/LIMITATIONS.md tells the owner what the safeguards do not cover. The approval after an answer (rule 32) does not
 * follow the answer into memory, and the New objective option "Save what was learned to memory" stores the final
 * result, which may hold an answer the owner gave an agent, in a shared space (wave-2a verification, SEC-W2A-1): the
 * page says so, under the option's name as the screen shows it.
 */
class LimitationsDocTest {
    private fun read(vararg candidates: String): String = candidates.map(::File).first { it.isFile }.readText()

    private val label = "Save what was learned to memory"

    @Test
    fun `the limits page says that saving what was learned keeps the final result, an answer in it included, in shared memory`() {
        val screen = read("src/main/java/com/aicontrolcenter/app/ui/tasks/NewObjectiveScreen.kt", "app/src/main/java/com/aicontrolcenter/app/ui/tasks/NewObjectiveScreen.kt")
        assertTrue("\"$label\"" in screen, "the New objective screen still calls the option \"$label\"")

        val limitations = read("../docs/LIMITATIONS.md", "docs/LIMITATIONS.md")
        val start = limitations.indexOf("- **After you answer an agent's question**")
        assertTrue(start >= 0, "the paragraph about answers is there")
        val paragraph = limitations.substring(start, limitations.indexOf("\n- **", start + 1).takeIf { it > 0 } ?: limitations.length).replace(Regex("\\s+"), " ")
        assertTrue("\"$label\"" in paragraph, paragraph)
        assertTrue("final result" in paragraph && "shared" in paragraph && "answer you gave" in paragraph, paragraph)
    }
}
