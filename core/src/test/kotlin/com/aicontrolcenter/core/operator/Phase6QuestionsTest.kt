package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, K3: agents' questions to the owner (rules 13, 32, 33). */
class Phase6QuestionsTest {
    private fun question(
        text: String = "Which city should the report cover?",
        options: List<String> = listOf("Berlin", "Paris"),
        status: OwnerRequestStatus = OwnerRequestStatus.PENDING,
        answer: String? = null,
    ) = OwnerRequest(
        id = RequestId("req_q"), kind = OwnerRequestKind.QUESTION, status = status, agentId = AgentId("agt_r"),
        toolId = ToolId("team.ask_owner"), capability = Capability("team.ask_owner"), risk = RiskLevel.SAFE,
        summary = "A question from Researcher", taskId = TaskId("tsk_1"), rootId = TaskId("tsk_root"),
        createdAt = 1_000, expiresAt = 1_000 + 20 * 60_000,
        decidedAt = if (status == OwnerRequestStatus.PENDING) null else 2_000,
        question = text, options = options, answer = answer,
    )

    @Test
    fun `a question is built with its text and options, and settles nothing`() {
        val q = question()
        assertTrue(q.isQuestion)
        assertEquals(emptyList(), q.offeredScopes)
        assertFalse(q.holdsBackAt(1_500), "a question never holds an agent back like a refusal")
        assertFailsWith<IllegalArgumentException> { q.grantFor(GrantScope.ONCE, GrantId("grt_1"), 1_500) }
        // Answered, with text or "decide yourself".
        assertEquals("Berlin", question(status = OwnerRequestStatus.APPROVED, answer = "Berlin").answer)
        assertNull(question(status = OwnerRequestStatus.APPROVED, answer = null).answer)
    }

    @Test
    fun `construction refuses a question with a scope, a grant-like shape or bad text`() {
        assertFailsWith<IllegalArgumentException> { question().copy(decisionScope = GrantScope.ALWAYS) }
        assertFailsWith<IllegalArgumentException> { question(status = OwnerRequestStatus.APPROVED).copy(decisionScope = GrantScope.ONCE) }
        assertFailsWith<IllegalArgumentException> { question(text = "  ") }
        assertFailsWith<IllegalArgumentException> { question(text = "x".repeat(QuestionRules.MAX_QUESTION_CHARS + 1)) }
        question(text = "x".repeat(QuestionRules.MAX_QUESTION_CHARS))
        assertFailsWith<IllegalArgumentException> { question(options = listOf("a", "b", "c", "d", "e")) }
        assertFailsWith<IllegalArgumentException> { question(options = listOf("x".repeat(QuestionRules.MAX_OPTION_CHARS + 1))) }
        assertFailsWith<IllegalArgumentException> { question(options = listOf("")) }
        assertFailsWith<IllegalArgumentException> { question(options = listOf("Berlin", "berlin")) }
        assertFailsWith<IllegalArgumentException> { question().copy(risk = RiskLevel.CONSEQUENTIAL) }
        assertFailsWith<IllegalArgumentException> { question().copy(constraint = CapabilityConstraint.App("com.example")) }
        assertFailsWith<IllegalArgumentException> { question(answer = "Berlin") }
        assertFailsWith<IllegalArgumentException> { question(status = OwnerRequestStatus.DENIED) }
        assertFailsWith<IllegalArgumentException> { question(status = OwnerRequestStatus.APPROVED, answer = " ") }
        assertFailsWith<IllegalArgumentException> { question(status = OwnerRequestStatus.APPROVED, answer = "x".repeat(QuestionRules.MAX_ANSWER_CHARS + 1)) }
        // Only a question carries question fields.
        val permission = question().copy(kind = OwnerRequestKind.PERMISSION, question = null, options = emptyList(), risk = RiskLevel.MODIFYING)
        assertFailsWith<IllegalArgumentException> { permission.copy(question = "Why?") }
        assertFailsWith<IllegalArgumentException> { permission.copy(options = listOf("a")) }
        assertFailsWith<IllegalArgumentException> { question().copy(question = null) }
    }

    @Test
    fun `options that read like approvals are refused at construction`() {
        for (option in listOf(
            "Allow it", "I approve", "Approved", "Permit the change", "Grant access", "Always", "Just once", "Deny",
            "denied", "Yes, do it", "yes do it!", "You have my permission", "ALLOWED",
        )) {
            assertNotNull(QuestionRules.approvalWordIn(option), option)
            assertFailsWith<IllegalArgumentException>(option) { question(options = listOf(option)) }
        }
        for (fine in listOf("Berlin", "The shorter report", "Use the 2024 figures", "Yes", "Do both", "Shallow water", "Concession", "Grantham")) {
            assertNull(QuestionRules.approvalWordIn(fine), fine)
        }
    }

    @Test
    fun `only an answer answers a question, and an answer answers only a question`() {
        val q = question()
        assertNull(q.problemWith(OwnerDecision.Answer("Berlin")))
        assertNull(q.problemWith(OwnerDecision.Answer(null)), "decide yourself")
        assertNotNull(q.problemWith(OwnerDecision.Answer("   ")))
        assertNotNull(q.problemWith(OwnerDecision.Answer("x".repeat(QuestionRules.MAX_ANSWER_CHARS + 1))))
        assertNotNull(q.problemWith(OwnerDecision.Approve()))
        assertNotNull(q.problemWith(OwnerDecision.Deny()))
        assertNotNull(q.problemWith(OwnerDecision.Grant(GrantScope.ALWAYS)))
        val approval = q.copy(kind = OwnerRequestKind.APPROVAL, question = null, options = emptyList())
        val permission = approval.copy(kind = OwnerRequestKind.PERMISSION)
        assertNotNull(approval.problemWith(OwnerDecision.Answer("yes")))
        assertNotNull(permission.problemWith(OwnerDecision.Answer("yes")))
        assertNull(OwnerDecision.Answer("text").note, "an answer's words are its text, never a note")
    }

    @Test
    fun `near-identical questions are recognised`() {
        assertTrue(QuestionRules.nearlySame("Which city should I cover?", "which city should i cover"))
        assertTrue(QuestionRules.nearlySame("Which city should the report cover?", "Which city should the report cover, please?"))
        assertFalse(QuestionRules.nearlySame("Which city should the report cover?", "How long should the report be?"))
    }

    @Test
    fun `answers that look like keys are KEY, short codes asked for are CODE`() {
        val q = "What is the verification code from the SMS?"
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "here: sk-ant-api03-abcdefghijklmnop"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "xai-ABCDEFGHIJKLMNOP1234"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "sk-or-v1-0123456789abcdef"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "AIzaSyD-1234567890abcdefgh"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "gsk_abcdefghijklmnop12"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "ghp_abcdefghijklmnop1234"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "token 3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", "dGhpcyBpcyBhIHNlY3JldCBrZXkgb2YgMzIgYnl0ZXM="))
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify(q, "It is 482913"))
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("Enter your PIN", "1234"))
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("What is the password?", "123 456"))
        assertEquals(AnswerRisk.KEY, AnswerCheck.classify(q, "sk-abcdefghijklmnopqrstu"), "a key wins over a code")
        // Ordinary answers.
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("How many people?", "We expect 4829 visitors"), "a short number in a sentence is a code only when a code was asked for")
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify(q, "123"), "too short to be a code")
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify(q, "1234567890"), "too long to be a code")
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", "Use the sk-ip option and the risk-free plan"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", "Please cover Berlin and Paris, in under 300 words."))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", "abcdefghijklmnopqrstuvwxyzabcdefghij"), "letters only are not a key")
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Which code style?", "Kotlin"), "a code word with no number")
    }

    @Test
    fun `codes written with separators or as words, and bare codes whatever the question, are CODE`() {
        // Security review of wave 1, F8.
        val q = "Please type the verification code you received."
        for (answer in listOf("4.8.2.9.1.3", "4,8,2,9,1,3", "4_8_2_9_1_3", "4-8-2-9-1-3", "4 8 2 9 1 3", "four eight two nine one three", "Four Eight Two Nine", "the code is 4. 8. 2. 9")) {
            assertEquals(AnswerRisk.CODE, AnswerCheck.classify(q, answer), answer)
        }
        // A bare 4 to 8 digit answer is a code whatever the question says (the owner's second tap still sends it).
        for ((question, answer) in listOf(
            "What are the six numbers in the message your bank sent?" to "482913",
            "How many people?" to "482913",
            "Which one?" to " 4829 ",
            "Anything?" to "4.8.2.9",
            "Anything?" to "nine nine one one",
        )) {
            assertEquals(AnswerRisk.CODE, AnswerCheck.classify(question, answer), "$question / $answer")
        }
        // A question in other words than "code" still makes a number in the answer one.
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("What are the six numbers in the message your bank sent?", "They are 482913"))
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("What did the two-factor prompt show?", "it showed 48 29 13"))
        // Still not codes: too few or too many digits, digits apart in a sentence, or no code asked for.
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", "123"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", "1234567890"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("What is your phone number?", "+49 151 2345 6789 0"), "14 digits in one run are no code")
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("How long should it be?", "Two pages, maybe three"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Which year?", "Use the 2024 figures"), "a year in a sentence, no code asked for")
    }

    @Test
    fun `a pasted text message, digits of any script, looser separators and letter codes are CODE`() {
        // Second review of F8: the code word may be in the answer, not the question.
        for (question in listOf("What did the newest message on your phone say?", "What did it say?")) {
            for (answer in listOf("Your Google verification code is 482913", "G-482913 is your Google verification code.", "482913 is your code")) {
                assertEquals(AnswerRisk.CODE, AnswerCheck.classify(question, answer), "$question / $answer")
            }
        }
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("What did the newest message on your phone say?", "It says 4 8 2 9 1 3"))
        val q = "Please type the verification code you received."
        for (answer in listOf("4x8x2x9x1x3", "4/8/2/9/1/3", "\uFF14\uFF18\uFF12\uFF19\uFF11\uFF13", "\u0664\u0668\u0662\u0669\u0661\u0663", "K7Q2M9", "it is K7Q2M9.")) {
            assertEquals(AnswerRisk.CODE, AnswerCheck.classify(q, answer), answer)
        }
        // Bare codes in other scripts' digits, whatever the question.
        assertEquals(AnswerRisk.CODE, AnswerCheck.classify("Anything?", "\u0664\u0668\u0662\u0669\u0661\u0663"))
        // Still ordinary: no code word anywhere, or no code-like token.
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("What did it say?", "Meet me at 5 tomorrow by the station entrance please"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify("What did it say?", "We expect 4829 visitors"))
        assertEquals(AnswerRisk.NONE, AnswerCheck.classify(q, "Kotlin"))
    }

    @Test
    fun `a code standing on its own is CODE whatever the agent asked, and a question about a notification or a screen asks for one`() {
        // Security review of wave 2a, SEC-W2A-3: the agent words the question, so it can leave out every code word.
        val noCodeWord = "Which six characters does your latest notification show?"
        val codeWord = "What is the verification code you received?"
        for (question in listOf(noCodeWord, codeWord, "How many people?", "Anything?")) {
            for (answer in listOf(
                "482913", "G-482913", "It shows G-482913", "it says 482913", "482913 from Google", "FB-48291 is the code",
                "We expect 482913 visitors", "it says 482 913", "4829-1300 on the screen", "Your number: 12345678.",
            )) {
                assertEquals(AnswerRisk.CODE, AnswerCheck.classify(question, answer), "$question / $answer")
            }
        }
        // The probe's answers to a question without a code word, which went through at once before.
        for (answer in listOf("K7Q2M9", "it shows 4829", "the screen says 48 29 13", "Shown: four eight two nine")) {
            assertEquals(AnswerRisk.CODE, AnswerCheck.classify(noCodeWord, answer), answer)
        }
        for (question in listOf("What does the screen display?", "Which characters appear?", "What did you receive?")) {
            assertEquals(AnswerRisk.CODE, AnswerCheck.classify(question, "It is 4829"), question)
        }
        // Years, small numbers, longer numbers and phone numbers in groups stay ordinary when no code was asked for.
        for (answer in listOf(
            "Use the 2024 figures", "Show the 2024 figures, and the screen sizes", "about 300 words", "We expect 1234567890 visitors",
            "Call +49 151 2345 6789", "Call 0151 234 5678 after 5", "ISO-9001 suppliers only", "Version 12.4.1", "COVID-19 rules",
            "between 1990 and 2024", "Order 2024-11-25",
        )) {
            assertEquals(AnswerRisk.NONE, AnswerCheck.classify("Anything?", answer), answer)
        }
    }

    @Test
    fun `a key typed in groups with spaces is still a key, a sentence after a prefix is not`() {
        // Second review of F8.
        for (answer in listOf(
            "sk-proj-abcd efgh ijkl mnop qrst uvwx", "sk-p roj- abcd efgh ijkl mnop", "my key: sk-proj-ab12 cd34 ef56 gh78", "XAI-ABCD EFGH IJKL MNOP",
            "AKIA IOSF ODNN 7EXA MPLE",
        )) {
            assertTrue(AnswerCheck.looksLikeKey(answer), answer)
            assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", answer), answer)
        }
        for (answer in listOf("Use the sk-ip option and the risk-free plan", "sk- is how those keys start, I think", "Use the sk-ip option plan")) {
            assertFalse(AnswerCheck.looksLikeKey(answer), answer)
        }
    }

    @Test
    fun `key prefixes match whatever their case, and common key shapes are known`() {
        // Security review of wave 1, F8.
        for (answer in listOf(
            "my key is XAI-ABCDEFGHIJKLMNOP", "sk_live_abcdefghijklmnop12345", "AKIAIOSFODNN7EXAMPLE", "glpat-abcdefghij1234567890",
            "SK-ANT-API03-ABCDEFGHIJKL", "Sk-proj-abcdefghijklmnop", "rk_live_abcdefghijklmnop", "ASIAIOSFODNN7EXAMPLE", "xoxb-1234567890-abcdefghij",
            "xoxp-1234567890-abcdefghij", "hf_abcdefghijklmnopqrst", "rk_test_abcdefghijklmno", "sk_test_abcdefghijklmno", "ya29.a0AfH6SMBabcdefghij",
        )) {
            assertTrue(AnswerCheck.looksLikeKey(answer), answer)
            assertEquals(AnswerRisk.KEY, AnswerCheck.classify("Anything?", answer), answer)
        }
        // Words that only start like a key are not keys.
        for (answer in listOf("Asia-Pacific-Region", "Akiak in Alaska", "hf_x", "the risk-free plan", "XAI-short", "ask_live_events")) {
            assertFalse(AnswerCheck.looksLikeKey(answer), answer)
        }
    }

    @Test
    fun `suggested answers that read like a go-ahead are refused`() {
        // Security review of wave 1, F9.
        for (option in listOf(
            "Yes, go ahead", "Sure, proceed and pay", "OK", "okay", "Confirm the purchase", "Confirmed", "Go ahead", "Just do it", "I accept",
            "Agreed", "Authorise it", "authorized", "You have my consent", "Pay now", "Paid", "Buy it", "bought", "Purchase the plan",
            "Install the app", "Uninstall it", "Send it", "Sent", "Delete the files", "deletion", "Erase everything",
        )) {
            assertNotNull(QuestionRules.approvalWordIn(option), option)
            assertFailsWith<IllegalArgumentException>(option) { question(options = listOf(option)) }
        }
        for (fine in listOf("Berlin", "The shorter report", "Use the 2024 figures", "Yes", "No", "Do both", "Shallow water", "Payton Street", "Sender unknown", "Okra soup")) {
            assertNull(QuestionRules.approvalWordIn(fine), fine)
        }
    }

    @Test
    fun `look-alike letters and the go-ahead for other consequential actions are refused too`() {
        // Second review of F9: Cyrillic "о", "а", "у" read as Latin letters by the owner.
        for (option in listOf("\u043Ek", "C\u043Enfirm", "Appr\u043Eve", "Yes, g\u043E ahead", "Yes, d\u043E it", "\u0430llow", "Pa\u0443", "\u041E\u041A", "\u00D4K")) {
            assertNotNull(QuestionRules.approvalWordIn(option), option)
            assertFailsWith<IllegalArgumentException>(option) { question(options = listOf(option)) }
        }
        assertTrue(QuestionRules.mixesAlphabets("C\u043Enfirm"))
        assertFalse(QuestionRules.mixesAlphabets("M\u00fcnchen"), "accented Latin is one alphabet")
        assertNotNull(QuestionRules.questionProblem("Which?", listOf("Bl\u0430ue")), "a word mixing alphabets is refused whatever it says")
        for (option in listOf(
            "Yes, place the order", "Yes, order it", "Yes, transfer the money", "Yes, wire the funds", "Yes, remove the files", "Yes, wipe it",
            "Yes, move it to the bin", "Yes, overwrite it", "Yes, submit it", "Yes, post it", "Yes, publish", "Yes, share it", "Yes, email them",
            "Yes, reply to them", "Yes, text them", "Yes, call them", "Yes, subscribe", "Yes, book it", "Yes, check out", "Yes, sign it",
            "Yes, renew", "Yes, top up", "Go for it", "Continue", "Carry on", "Go on", "Let's go", "Checkout",
        )) {
            assertNotNull(QuestionRules.approvalWordIn(option), option)
            assertFailsWith<IllegalArgumentException>(option) { question(options = listOf(option)) }
        }
        for (fine in listOf("Berlin", "M\u00fcnchen", "\u041C\u043E\u0441\u043A\u0432\u0430", "The shorter report", "Yes", "No", "Go online later", "Topics", "Stop")) {
            assertNull(QuestionRules.questionProblem("Which?", listOf(fine)), fine)
        }
    }

    @Test
    fun `attention limits and who raised a request`() {
        assertEquals(5, OwnerAttention.MAX_OPEN_AGENT_REQUESTS)
        assertEquals(3, OwnerAttention.MAX_OPEN_AGENT_REQUESTS_PER_OBJECTIVE)
        assertEquals(12, OwnerAttention.MAX_PENDING_CALL_REQUESTS)
        assertEquals(6, OwnerAttention.MAX_PENDING_CALL_REQUESTS_PER_OBJECTIVE)
        assertEquals(listOf(1, 2, 4), listOf(OwnerAttention.MAX_OPEN_QUESTIONS_PER_TASK, OwnerAttention.MAX_QUESTIONS_PER_TASK, OwnerAttention.MAX_QUESTIONS_PER_OBJECTIVE))
        assertTrue(OwnerAttention.isAgentInitiated(question()))
        val call = question().copy(kind = OwnerRequestKind.APPROVAL, question = null, options = emptyList(), toolId = ToolId("files.delete"))
        assertFalse(OwnerAttention.isAgentInitiated(call))
        assertTrue(OwnerAttention.isAgentInitiated(call.copy(kind = OwnerRequestKind.PERMISSION, toolId = null)))
    }

    @Test
    fun `answering is refused by default`() = runTest {
        val requests = object : OwnerRequests {
            override val pending get() = kotlinx.coroutines.flow.flowOf(emptyList<OwnerRequest>())
            override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<OwnerRequest>())
            override fun observeForConversation(conversationId: com.aicontrolcenter.core.ConversationId) = kotlinx.coroutines.flow.flowOf(emptyList<OwnerRequest>())
            override suspend fun get(id: RequestId): OwnerRequest? = null
            override suspend fun grant(id: RequestId, scope: GrantScope, note: String?) = DecideResult.NotFound
            override suspend fun approve(id: RequestId, note: String?) = DecideResult.NotFound
            override suspend fun deny(id: RequestId, note: String?) = DecideResult.NotFound
            override suspend fun letAskAgain(id: RequestId): OwnerResult<OwnerRequest> = OwnerResult.Refused("no")
        }
        assertIs<OwnerResult.Refused>(requests.answer(RequestId("req_q"), "Berlin"))
    }
}
