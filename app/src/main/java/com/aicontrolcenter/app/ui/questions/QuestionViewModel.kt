package com.aicontrolcenter.app.ui.questions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.everyHalfMinute
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.textChanges
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.QuestionRules
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What the question screen shows. */
@Immutable
data class QuestionUiState(
    val isLoading: Boolean = true,
    /** No question has this id (it may have been cleared with its agent's data), or the id is not a question's. */
    val notFound: Boolean = false,
    val question: QuestionUi? = null,
    /** What the answer typed so far looks like (a key, a code), said before it is sent. */
    val hint: AnswerHint? = null,
    /** Why the last Send did not go, in the app's words; null when it went or nothing was sent yet. */
    val refusal: String? = null,
    /** The answer looked like a code: "Send it anyway" sends exactly this text, the owner's second tap (rule 32). */
    val sendAnywayOffered: Boolean = false,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    /** The answer form is shown: the question still waits for the owner. */
    val canAnswer: Boolean get() = question?.isOpen == true
}

/**
 * The question screen's state and the owner's answer (rule 13, rule 32; WP11a). Owner-only: it holds the owner's
 * request port, the only way to answer (`OwnerRequests.answer`, with `sendAnyway` for "Send it anyway").
 *
 * The answer is the owner's own words, typed straight into [answer]; "Use this answer" copies an agent's suggestion into
 * it and sends nothing. Send hands the words to [OwnerRequests.answer], which refuses a key or a password every time
 * (and a provider key stored on this phone, which only it can check) and a code unless the owner taps "Send it anyway"
 * for that same text. A question nothing waits for any more is closed as soon as it is shown
 * ([OwnerRequests.closeIfAbandoned]), as the approval screen does.
 *
 * @param questions the owner's requests: `pending`, `answer`, `closeIfAbandoned`.
 * @param directory agent names and where their models run, read only.
 * @param ticker moves the time left; tests pass a single tick.
 * @param computation where the screen's state is built (the app's background pool; tests stay on their dispatcher).
 */
class QuestionViewModel(
    private val requestId: RequestId,
    private val questions: OwnerRequests,
    directory: RegistryDirectory,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    ticker: Flow<Unit> = everyHalfMinute(),
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    /** The owner's answer (rule 27: the field's text lives here, typed straight into it). */
    val answer: TextFieldState = TextFieldState()

    private data class Local(
        val refusal: String? = null,
        /** The text [refusal] was given for (null for "Decide yourself"): while the question waits, it shows only while the field holds it. */
        val refusedText: String? = null,
        /** The exact text the code warning was given for: "Send it anyway" is offered only while the field still holds it. */
        val codeText: String? = null,
        val isBusy: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** Bumped after an answer was refused or given, so the request is read again even if the pending list did not change. */
    private val reread = MutableStateFlow(0)

    /**
     * The request as it is now: from the pending list while a call waits for it, else from the store, closed first if
     * nothing waits for it any more.
     */
    private val request: Flow<OwnerRequest?> = combine(questions.pending, reread) { pending, _ ->
        pending.firstOrNull { it.id == requestId } ?: questions.closeIfAbandoned(requestId)
    }.distinctUntilChanged()

    val uiState: StateFlow<QuestionUiState> = combine(request, directory.observe(), local, answer.textChanges(), ticker) { request, snapshot, local, typed, _ ->
        val shown = request?.takeIf { it.kind == OwnerRequestKind.QUESTION }
        val ui = shown?.let { questionUi(it, snapshot, clock.now(), zone()) }
        QuestionUiState(
            isLoading = false,
            notFound = shown == null,
            question = ui,
            hint = if (ui?.isOpen == true) AnswerHint.of(shown.question.orEmpty(), typed) else null,
            refusal = local.refusal?.takeIf { ui?.isOpen != true || local.refusedText == null || local.refusedText == typed.trim() },
            sendAnywayOffered = ui?.isOpen == true && local.codeText != null && local.codeText == typed.trim(),
            isBusy = local.isBusy,
            message = local.message,
        )
    }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuestionUiState())

    /** "Use this answer": copies the agent's suggestion number [index] into the answer. Sends nothing. */
    fun useSuggestion(index: Int) {
        val option = currentRequestOptions().getOrNull(index) ?: return
        answer.putText(option.take(QuestionRules.MAX_ANSWER_CHARS))
    }

    /** Send: the owner's words as they are now. */
    fun send() = submit(answer.text.toString().trim(), sendAnyway = false)

    /** "Send it anyway": the owner's second tap for an answer that looks like a code, for exactly the text it was given for. */
    fun sendAnyway() {
        val text = answer.text.toString().trim()
        if (local.value.codeText != text) return
        submit(text, sendAnyway = true)
    }

    /** "Let Researcher decide": no words; the agent makes the most reasonable choice. */
    fun decideYourself() = submit(null, sendAnyway = false)

    fun messageShown() = local.update { it.copy(message = null) }

    private fun submit(text: String?, sendAnyway: Boolean) {
        if (local.value.isBusy) return
        if (text != null && text.isEmpty()) {
            local.update { it.copy(refusal = emptyAnswer(uiState.value.question?.agent?.name ?: "the agent"), refusedText = text, codeText = null) }
            return
        }
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = try {
                questions.answer(requestId, text, sendAnyway)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                OwnerResult.Refused("Your answer could not be saved: ${failure.message ?: "storage error"}. The agent is still waiting.")
            }
            when (result) {
                is OwnerResult.Done -> {
                    answer.putText("")
                    val name = uiState.value.question?.agent?.name ?: "The agent"
                    val said = if (text == null) "$name decides itself." else "Sent. $name reads your answer as text; it allows nothing."
                    local.update { it.copy(isBusy = false, refusal = null, refusedText = null, codeText = null, message = UserMessage(said, ++messageCounter)) }
                }
                is OwnerResult.Refused -> {
                    val code = result.reason == DefaultOwnerRequests.CODE_ASK_AGAIN
                    val shown = if (code) QuestionTexts.CODE_WARNING else result.reason
                    local.update { it.copy(isBusy = false, refusal = shown, refusedText = text, codeText = if (code) text else null) }
                }
            }
            // A refusal that closed the question (gone, answered, run out, nothing waiting) shows it closed; the form goes.
            reread.update { it + 1 }
        }
    }

    private fun currentRequestOptions(): List<String> = uiState.value.question?.options.orEmpty()

    companion object {
        /** Send with an empty field: "Write an answer, or choose “Let Researcher decide”." */
        fun emptyAnswer(agentName: String): String = "Write an answer, or choose “${QuestionTexts.letDecide(agentName)}”."
    }
}
