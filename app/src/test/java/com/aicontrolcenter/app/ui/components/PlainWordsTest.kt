package com.aicontrolcenter.app.ui.components

import com.aicontrolcenter.app.ui.activity.WHEN_A_LIMIT_IS_REACHED
import com.aicontrolcenter.app.ui.tasks.NewObjectiveViewModel
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.llm.DailyUse
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One sentence everywhere for what a reached daily limit does to objectives (D3A-3): nothing resumes a paused objective
 * by itself, at midnight or later, so no screen may promise it.
 */
class PlainWordsTest {
    @Test
    fun `the sentence says the owner resumes each objective, after midnight or a higher limit`() {
        assertEquals(
            "When a limit is reached, running objectives pause. After midnight, or once you raise the limit, open each one and tap Resume.",
            LIMIT_PAUSES_OBJECTIVES,
        )
        assertEquals(
            "Raise the limit in Settings › Daily limits, then open the objective and tap Resume (after midnight works too).",
            RESUME_AFTER_LIMIT,
        )
    }

    @Test
    fun `the New objective form and the Daily limits screens use it`() {
        val line = assertNotNull(NewObjectiveViewModel.todayLineOf(TodayUse(DailyUse.NONE, DailyLimits.DEFAULT)))
        assertTrue(line.note.endsWith(LIMIT_PAUSES_OBJECTIVES), line.note)
        assertTrue(WHEN_A_LIMIT_IS_REACHED.endsWith(LIMIT_PAUSES_OBJECTIVES), WHEN_A_LIMIT_IS_REACHED)
        // The money limit is said before any price exists (D3A-8).
        assertEquals("Today: 0 of 200 answers · 0 of 500,000 tokens · US$1.00 (counted once you enter prices)", line.text)
    }

    @Test
    fun `no screen promises that paused work goes on by itself at midnight`() {
        val promises = listOf("pauses until midnight", "pause until midnight", "Or wait until midnight", "work pauses until midnight")
        val found = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                promises.firstOrNull { line.contains(it, ignoreCase = true) }?.let { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }.toList()
        assertEquals(emptyList(), found)
    }
}
