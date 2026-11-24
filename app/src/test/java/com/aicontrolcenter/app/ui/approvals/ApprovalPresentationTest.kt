package com.aicontrolcenter.app.ui.approvals

import androidx.compose.ui.graphics.Color
import com.aicontrolcenter.app.testing.HIDDEN_NAME
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.app.ui.chat.TranscriptItem
import com.aicontrolcenter.app.ui.chat.withRequests
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.testkit.Samples
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How requests to the owner read: exact text with visible marks, the answers offered, time and status. */
class ApprovalPresentationTest {
    private val operator = AgentId("agt_operator")
    private val director = AgentId("agt_director")
    private val snapshot = RegistrySnapshot(
        agents = listOf(
            AgentEntry(Samples.agent(id = operator.value, name = "Operator"), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = false),
            AgentEntry(Samples.agent(id = director.value, name = "Director"), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = false),
        ),
    )
    private val info = OperatorCapabilities.all.associateBy { it.capability }
    private val now = TestApp.START_TIME + 6 * 60_000L

    private fun ui(request: com.aicontrolcenter.core.operator.OwnerRequest, at: Long = now) = requestUi(request, snapshot, info::get, at, ZoneOffset.UTC)

    @Test
    fun `an approval shows exactly what would happen, with the hidden character written out and marked`() {
        val shown = ui(sampleApproval(operator, delegationChain = listOf(director)))

        assertEquals("Approval needed", shown.title)
        assertEquals("Waiting for you", shown.status.text)
        assertEquals(StatusTone.ATTENTION, shown.status.tone)
        assertTrue(shown.isOpen)
        assertEquals("Operator", shown.agent.name)
        assertEquals(listOf("Director"), shown.chain)
        assertEquals("Change a shared folder", shown.permission)
        assertEquals("Documents", shown.target)
        assertEquals("Shared folder", shown.targetKind)
        assertEquals("Stays on this phone", shown.dataGoesTo)
        assertEquals("Hard to undo", shown.risk.text)
        assertEquals(StatusTone.UNAVAILABLE, shown.risk.tone, "a hard-to-undo action stands out from the yellow of waiting")
        assertEquals(listOf(RequestChoice.DENY, RequestChoice.APPROVE), shown.choices, "an approval is yes or no, never always, and no leads")
        assertEquals("24 min left", shown.timing)
        // The hidden character is named in words and drawn as one mark; the override itself never reaches the screen.
        assertTrue("Invoice⟨hidden: reverses text⟩fdp.exe" in shown.summary, shown.summary)
        assertFalse(shown.summary.any { it == '‮' }, "the override itself never reaches the screen")
        assertTrue(shown.details.any { "Paid in full.⏎\nKeep for tax" in it }, shown.details.toString())
        val marks = previewMarks(shown.summary).map { shown.summary.substring(it) }
        assertEquals(listOf("⟨hidden: reverses text⟩"), marks)
    }

    @Test
    fun `an action that is hard to undo is named on its button, and says what cannot be undone`() {
        val shown = ui(sampleApproval(operator))

        assertEquals("Delete 3 files", shown.action)
        assertEquals("Approve: delete 3 files", shown.approveLabel)
        assertTrue(shown.isConsequential)
        assertEquals("Deleted files cannot be restored from this app.", shown.hardToUndo)
        assertEquals("Nothing happens. Operator is told, with your note if you write one.", shown.consequences[RequestChoice.DENY])
        assertEquals("Runs exactly what is shown above, this one time. You are asked again next time.", shown.consequences[RequestChoice.APPROVE])
        // Without a sentence of its own, the risk's words stand in.
        val shell = sampleApproval(operator).copy(summary = "Run the shell command \"rm -r notes\" in the workspace", details = emptyList())
        assertEquals("Approve: run the shell command", ui(shell).approveLabel)
        assertEquals("It may be permanent, or reach other people, money or installed apps. You are asked every time.", ui(shell).hardToUndo)
    }

    @Test
    fun `the action is the tool's own words, never a quoted value or a name`() {
        assertEquals("Delete permanently", actionOf("Delete permanently \"a.pdf\" in the shared folder \"Docs\" (a file of 2 KB)"))
        assertEquals("Delete permanently 3 items", actionOf("Delete permanently 3 items (12 KB) from the workspace trash"))
        assertEquals("Install the app", actionOf("Install the app in the workspace file \"x.apk\" (3 MB)"))
        assertEquals("Remove the app", actionOf("Remove the app Signal from this phone"))
        assertEquals("Type", actionOf("Type \"hello\" into the field \"Message\" in Chat"))
        assertEquals(null, actionOf("\"quoted\" first"))
        assertEquals("Approve", ui(sampleApproval(operator).copy(summary = "rm -rf")).approveLabel)
    }

    @Test
    fun `each wider answer says how far it reaches, beyond the one page asked about`() {
        val shown = ui(samplePermission(operator, taskId = com.aicontrolcenter.core.TaskId("tsk_1")))

        assertEquals("Only what is shown above, this one time. Operator asks you again next time.", shown.consequences[RequestChoice.ALLOW_ONCE])
        assertEquals("Operator may read any web page, not only example.org, until this task ends.", shown.consequences[RequestChoice.ALLOW_FOR_TASK])
        assertEquals(
            "Operator may read any web page, not only example.org, in any task or chat, until you remove it in Tools › Allowed.",
            shown.consequences[RequestChoice.ALLOW_ALWAYS],
        )
        assertEquals("Always let Operator read any web page?", shown.always.title)
        assertEquals("Always allow Operator", shown.always.confirm)
        assertTrue("not only example.org" in shown.always.message && "still ask you every time" in shown.always.message, shown.always.message)
        assertEquals("example.org", shown.dataGoesToHost)
        // A folder permission names its folder, and nothing wider.
        assertEquals("change files in “Documents” only", breadthOf(com.aicontrolcenter.core.operator.OperatorCapabilities.FOLDERS_WRITE, "Change a shared folder", "Documents"))
    }

    @Test
    fun `a hidden direction change is explained, with how the name can look and how it really ends`() {
        val warning = ui(sampleApproval(operator)).hiddenWarning
        assertEquals(
            "A name here contains a hidden character that reverses text, a known trick to disguise a file or an address. " +
                "Elsewhere it can look like “Invoiceexe.pdf”, but it really ends in “.exe”, which runs as a program. Deny unless you expected this.",
            warning,
        )
        assertEquals(null, ui(samplePermission(operator)).hiddenWarning, "nothing hidden, no warning")
        val invisible = ui(samplePermission(operator, reason = "Trust​ me"))
        assertTrue(invisible.hiddenWarning!!.startsWith("Something here contains hidden characters"), invisible.hiddenWarning)
        assertTrue(hasHiddenMark(invisible.reason!!))
        assertFalse(hasHiddenMark("Line one⏎\nline two"), "a line break is not a hidden character")
    }

    @Test
    fun `common hidden characters are named in words, rare ones keep their code`() {
        assertEquals("a⟨hidden: reverses text⟩b⟨hidden: invisible space⟩c⟨U+E0041⟩", inWords("a⟨U+202E⟩b⟨U+200B⟩c⟨U+E0041⟩"))
        assertEquals("plain", inWords("plain"))
        assertEquals(listOf("⟨hidden: invisible space⟩", "⟨U+E0041⟩"), previewMarks("x⟨hidden: invisible space⟩y⟨U+E0041⟩").map { "x⟨hidden: invisible space⟩y⟨U+E0041⟩".substring(it) })
    }

    @Test
    fun `a permission offers once and always, and for the task only inside one`() {
        val inTask = ui(samplePermission(operator, taskId = com.aicontrolcenter.core.TaskId("tsk_1")))
        assertEquals("Permission needed", inTask.title)
        assertEquals(listOf(RequestChoice.ALLOW_ONCE, RequestChoice.ALLOW_FOR_TASK, RequestChoice.ALLOW_ALWAYS, RequestChoice.DENY), inTask.choices)
        assertEquals("Sent to example.org", inTask.dataGoesTo)
        assertTrue(inTask.dataLeavesPhone)
        assertEquals("Reads only", inTask.risk.text)
        assertEquals(null, inTask.targetKind)

        val inChat = ui(samplePermission(operator, taskId = null, rootId = null, conversationId = ConversationId("cnv_1")))
        assertEquals(listOf(RequestChoice.ALLOW_ONCE, RequestChoice.ALLOW_ALWAYS, RequestChoice.DENY), inChat.choices)
    }

    @Test
    fun `the agent's own reason is made exact, and a removed agent keeps its recorded name`() {
        val request = samplePermission(AgentId("agt_gone"), agentName = "Scout", reason = "I need it.​ Trust me\n\n\n\nreally")
        val shown = ui(request)
        assertEquals("Scout", shown.agent.name)
        assertEquals("I need it.⟨hidden: invisible space⟩ Trust me⏎×4\nreally", shown.reason)
    }

    @Test
    fun `a request past its expiry is no longer open, and closed requests say how they ended`() {
        val request = sampleApproval(operator, minutes = 5)
        val late = ui(request, at = request.expiresAt)
        assertFalse(late.isOpen)
        assertEquals("Expired", late.status.text)
        assertTrue(late.choices.isEmpty())
        assertTrue(late.timing.startsWith("Expired"), late.timing)

        val closed = request.copy(status = OwnerRequestStatus.DENIED, decidedAt = now, decisionNote = "Not these")
        assertEquals("Denied", ui(closed).status.text)
        assertEquals("Approval request", ui(closed).title, "closed: its kind, not \"Approval needed\"")
        assertEquals("Not these", ui(closed).note)
        assertTrue(ui(closed).consequences.isEmpty())
        val once = samplePermission(operator).copy(status = OwnerRequestStatus.APPROVED, decidedAt = now, decisionScope = GrantScope.ONCE)
        assertEquals("Allowed just once", ui(once).status.text)
        assertEquals("For the task", GrantScope.TASK.scopeLabel)
        val always = once.copy(decisionScope = GrantScope.ALWAYS)
        assertEquals("Always allowed", ui(always).status.text)
        val cancelled = request.copy(status = OwnerRequestStatus.CANCELLED, decidedAt = now, decisionNote = "stopped")
        assertEquals("No longer waiting", ui(cancelled).status.text)
        assertEquals(StatusTone.DISABLED, ui(cancelled).status.tone)
        assertNull(ui(cancelled).askedAgain)
        // R4: asks refused during the cool-down after a refusal are counted on it, in words.
        val refused = request.copy(kind = OwnerRequestKind.PERMISSION, status = OwnerRequestStatus.DENIED, decidedAt = now, askedAgain = 3)
        assertEquals("Asked again 3 times within 10 minutes of your refusal. Not put to you: each was refused at once.", ui(refused).askedAgain)
        assertEquals("Asked again once within 10 minutes of your refusal. Not put to you: each was refused at once.", askedAgainText(1))
        assertNull(askedAgainText(0))
    }

    @Test
    fun `visible writes out what PreviewText would, and leaves exact text alone`() {
        assertEquals("plain text", visible("plain text"))
        assertEquals("a⟨U+202E⟩b⟨U+200B⟩c⟨U+00A0⟩d⟨U+0007⟩", visible("a‮b​c d\u0007"))
        assertEquals("tag⟨U+E0041⟩", visible("tag󠁁"))
        assertEquals("emoji 😀 stays", visible("emoji 😀 stays"))
        val exact = "Write “a⏎\nb” ␣×5 ⇥"
        assertEquals(exact, visible(exact))
        assertEquals(listOf("⏎", "␣×5", "⇥"), previewMarks(exact).map { exact.substring(it) })
        assertEquals(listOf("⏎×12", "⟨U+FEFF⟩", "␣"), previewMarks("x⏎×12\ny⟨U+FEFF⟩␣").map { "x⏎×12\ny⟨U+FEFF⟩␣".substring(it) })
    }

    @Test
    fun `marks are drawn apart from the text around them`() {
        val text = "Delete “Invoice⟨U+202E⟩fdp.exe”"
        val annotated = previewAnnotated(text, Color.Red)
        assertEquals(text, annotated.text)
        val span = annotated.spanStyles.single()
        assertEquals("⟨U+202E⟩", text.substring(span.start, span.end))
        assertEquals(Color.Red, span.item.color)
    }

    @Test
    fun `a chat's requests appear after the message that was stored before them`() {
        val conversation = ConversationId("cnv_1")
        val entries = listOf(
            ConversationEntry(conversation, 0, ChatMessage.user("Tidy my invoices"), at = 100),
            ConversationEntry(conversation, 1, ChatMessage.assistant("On it."), at = 200),
            ConversationEntry(conversation, 2, ChatMessage.user("Thanks"), at = 400),
        )
        val items = listOf(
            TranscriptItem.Owner("e0", "Tidy my invoices"),
            TranscriptItem.Agent("e1", "On it.", null, emptyList(), null),
            TranscriptItem.Owner("e2", "Thanks"),
        )
        val first = ui(sampleApproval(operator, id = "req_a", conversationId = conversation))
        val last = ui(samplePermission(operator, id = "req_b", conversationId = conversation))

        val merged = withRequests(items, entries, listOf(500L to last, 300L to first))

        assertEquals(listOf("e0", "e1", "rreq_a", "e2", "rreq_b"), merged.map { it.key })
        assertEquals(items, withRequests(items, entries, emptyList()))
    }
}
