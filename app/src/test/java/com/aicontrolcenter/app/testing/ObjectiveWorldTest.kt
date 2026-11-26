package com.aicontrolcenter.app.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.tasks.AgentFaces
import com.aicontrolcenter.app.ui.tasks.planRowsOf
import com.aicontrolcenter.core.tasks.TaskKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Wave 3 hand-off (WP8; handoff-wp11a.md item 2): the sample objectives' review tasks carry the status line the framework
 * writes when a review ends ("Verdict: revise."), so their plans, built from task summaries (P1), show the verdict badges
 * in the screenshots and journeys as a real objective does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ObjectiveWorldTest {
    @Test
    fun `the sample plans show the reviewers' verdicts, read from the review tasks' status lines`() = runTest {
        val app = TestApp(backgroundScope)
        app.populateWithObjectives()
        val faces = AgentFaces(app.graph.registryDirectory.snapshot())

        val notes = planRowsOf(app.tasks.observeSummaries(SampleObjectives.NOTES).first(), faces)
        val compare = notes.single { it.id == SampleObjectives.COMPARE }
        assertEquals("Revision asked", compare.verdict?.status?.text)
        assertEquals(1, compare.verdict?.rounds)

        val lisbon = planRowsOf(app.tasks.observeSummaries(SampleObjectives.LISBON).first(), faces)
        val reviewed = lisbon.filter { it.depth == 0 && it.verdict != null }
        assertEquals(listOf("Pick what to see each day", "Price the trip"), reviewed.map { it.title })
        assertEquals(listOf("Approved", "Approved"), reviewed.map { it.verdict?.status?.text })
        assertEquals(2, lisbon.count { it.kind == TaskKind.REVIEW }, "each review hangs under the step it checked")
    }
}
