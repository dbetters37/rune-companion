package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.activity.answers
import com.aicontrolcenter.app.ui.activity.moneyWords
import com.aicontrolcenter.app.ui.activity.tokens
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.usage.TodayUsage
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** One of today's limits against today's use, in words. */
@Immutable
data class LimitLineUi(
    /** "Answers", "Tokens", "Money". */
    val title: String,
    /** "37 of 200 today", "No limit", "No model used today has a price". */
    val text: String,
    /** True when this limit is reached now. */
    val reached: Boolean,
)

/** Why a field's value cannot be saved, by field. */
@Immutable
data class DailyLimitErrors(val answers: String? = null, val tokens: String? = null, val money: String? = null) {
    val any: Boolean get() = answers != null || tokens != null || money != null
}

/** What the Daily limits section and screen show. */
@Immutable
data class DailyLimitsUiState(
    val isLoading: Boolean = true,
    val limits: DailyLimits = DailyLimits.DEFAULT,
    /** Answers, tokens and money, each against its limit. */
    val lines: List<LimitLineUi> = emptyList(),
    /** The limiter's sentence when a limit is reached now; null otherwise. */
    val reached: String? = null,
    /** Whether the money limit is on in the form (it can be switched off: no money limit). */
    val moneyLimitOn: Boolean = true,
    val errors: DailyLimitErrors = DailyLimitErrors(),
    /** What the last Save did, in words. */
    val savedMessage: String? = null,
    val isSaving: Boolean = false,
    /** True when the saved limits are the usual ones (the owner's Q2). */
    val usual: Boolean = true,
)

/**
 * Today's limits of model answers, tokens and money (rule 6, Q2: 200 answers, 500,000 tokens, US$1.00; WP10a), with
 * today's use and plain words on what happens when one is reached ("a running objective pauses"; "limits stop work, not
 * cost already spent"). Serves the Settings section and the Daily limits screen.
 *
 * The three numbers live in [answers], [tokens] and [money] (rule 27); they are filled once from the saved settings and
 * read again only when the owner taps Save. Today's use comes from [today], counted exactly as the gateway's limiter
 * counts it (`DailyUse.of`).
 *
 * @param settings the owner's writer: the limits are settings (`maxModelCallsPerDay`, `maxTokensPerDay`, `maxSpendPerDayMicros`).
 * @param today today's use, read only, counted as the limiter counts it.
 */
class DailyLimitsViewModel(
    private val settings: SettingsRepository,
    private val today: TodayUsage,
) : ViewModel() {
    /** The three numbers the owner types (rule 27: each field's text lives here). */
    val answers: TextFieldState = TextFieldState()
    val tokens: TextFieldState = TextFieldState()
    val money: TextFieldState = TextFieldState()

    private val form = MutableStateFlow(Form())

    /** What the owner changed on the form that is not text: the money switch, errors, the last result. */
    private data class Form(
        val filled: Boolean = false,
        val moneyLimitOn: Boolean = true,
        val errors: DailyLimitErrors = DailyLimitErrors(),
        val savedMessage: String? = null,
        val isSaving: Boolean = false,
    )

    init {
        viewModelScope.launch {
            try {
                fill(settings.get().dailyLimits)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fill(DailyLimits.DEFAULT)
            }
        }
    }

    val uiState: StateFlow<DailyLimitsUiState> = combine(today.observe(), form) { use, current ->
        DailyLimitsUiState(
            isLoading = !current.filled,
            limits = use.limits,
            lines = linesOf(use),
            reached = reachedSentence(use),
            moneyLimitOn = current.moneyLimitOn,
            errors = current.errors,
            savedMessage = current.savedMessage,
            isSaving = current.isSaving,
            usual = use.limits == DailyLimits.DEFAULT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyLimitsUiState())

    /** Switches the money limit on or off in the form; Save stores it. */
    fun setMoneyLimitOn(on: Boolean) = form.update { it.copy(moneyLimitOn = on, errors = it.errors.copy(money = null), savedMessage = null) }

    /** Saves the three fields as they are now; a value that cannot be a limit is refused with its reason, nothing saved. */
    fun save() {
        if (form.value.isSaving) return
        val moneyOn = form.value.moneyLimitOn
        val calls = parseCalls(answers.text.toString())
        val tokenLimit = parseTokens(tokens.text.toString())
        val spend = if (moneyOn) parseMoney(money.text.toString()) else Parsed(null, null)
        val errors = DailyLimitErrors(calls.problem, tokenLimit.problem, spend.problem)
        if (errors.any) {
            form.update { it.copy(errors = errors, savedMessage = null) }
            return
        }
        store(DailyLimits(calls.value!!.toInt(), tokenLimit.value!!, if (moneyOn) spend.value else null))
    }

    /** Puts back the usual limits (200 answers, 500,000 tokens, US$1.00) and saves them. */
    fun restoreUsual() = store(DailyLimits.DEFAULT, refill = true)

    private fun store(limits: DailyLimits, refill: Boolean = true) {
        form.update { it.copy(isSaving = true, errors = DailyLimitErrors(), savedMessage = null) }
        viewModelScope.launch {
            val before = try { today.now() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val result = try {
                settings.update { it.withLimits(limits) }
                if (refill) fill(limits)
                val now = TodayUse(before?.use ?: com.aicontrolcenter.framework.llm.DailyUse.NONE, limits, before?.currency ?: ModelPrice.DEFAULT_CURRENCY)
                when {
                    before?.reached == true && !now.reached ->
                        "Saved. Paused objectives do not go on by themselves: open each one and tap Resume."
                    now.reached -> "Saved. Today's use is already at a limit, so agents still cannot ask models off this phone until midnight or a higher limit."
                    else -> "Saved."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "The limits could not be saved. Try again."
            }
            form.update { it.copy(isSaving = false, savedMessage = result) }
        }
    }

    private fun fill(limits: DailyLimits) {
        answers.putText(limits.maxCalls?.toString().orEmpty())
        tokens.putText(limits.maxTokens?.toString().orEmpty())
        money.putText(limits.maxSpendMicros?.let(::moneyText) ?: moneyText(DailyLimits.DEFAULT_MAX_SPEND_MICROS))
        form.update { it.copy(filled = true, moneyLimitOn = limits.maxSpendMicros != null) }
    }

    private data class Parsed(val value: Long?, val problem: String?)

    private fun parseCalls(text: String): Parsed {
        val value = text.trim().replace(",", "").toLongOrNull()
        return when {
            text.isBlank() -> Parsed(null, "Say how many answers a day, for example 200.")
            value == null -> Parsed(null, "Write a whole number, for example 200.")
            value !in DailyLimits.CALLS_RANGE.first..DailyLimits.CALLS_RANGE.last ->
                Parsed(null, "Choose between ${formatCount(DailyLimits.CALLS_RANGE.first.toLong())} and ${formatCount(DailyLimits.CALLS_RANGE.last.toLong())} answers.")
            else -> Parsed(value, null)
        }
    }

    private fun parseTokens(text: String): Parsed {
        val value = text.trim().replace(",", "").toLongOrNull()
        return when {
            text.isBlank() -> Parsed(null, "Say how many tokens a day, for example 500000.")
            value == null -> Parsed(null, "Write a whole number, for example 500000.")
            value !in DailyLimits.TOKENS_RANGE ->
                Parsed(null, "Choose between ${formatCount(DailyLimits.TOKENS_RANGE.first)} and ${formatCount(DailyLimits.TOKENS_RANGE.last)} tokens.")
            else -> Parsed(value, null)
        }
    }

    private fun parseMoney(text: String): Parsed {
        val micros = moneyMicros(text)
        val low = DailyLimits.SPEND_RANGE_MICROS.first
        val high = DailyLimits.SPEND_RANGE_MICROS.last
        return when {
            text.isBlank() -> Parsed(null, "Say how much a day, for example 1.00, or switch the money limit off.")
            micros == null -> Parsed(null, "Write an amount such as 1.00 or 0.50.")
            micros !in DailyLimits.SPEND_RANGE_MICROS ->
                Parsed(null, "Choose between ${moneyText(low)} and ${moneyText(high)} a day.")
            else -> Parsed(micros, null)
        }
    }

    companion object {
        /** Keeps digits and one decimal point (a comma counts as one), at most 12 characters: for the money field. */
        val MoneyInput: InputTransformation = InputTransformation.byValue { _, proposed ->
            val kept = StringBuilder()
            var point = false
            proposed.forEach { c ->
                when {
                    c.isDigit() -> kept.append(c)
                    (c == '.' || c == ',') && !point -> {
                        point = true
                        kept.append('.')
                    }
                }
            }
            kept.take(12)
        }

        /** "1.00" for 1,000,000 micros: how the money field shows an amount (no currency, two decimals). */
        fun moneyText(micros: Long): String = BigDecimal.valueOf(micros, 6).setScale(2, RoundingMode.HALF_UP).toPlainString()

        /** An amount as typed ("1", "1.5", "0,25", " 2.00 ") in millionths, or null when it is not one (more than two decimals included). */
        fun moneyMicros(text: String): Long? {
            val clean = text.trim().replace(',', '.')
            if (clean.isEmpty() || !Regex("""\d{0,9}(\.\d{0,2})?""").matches(clean) || clean == ".") return null
            return BigDecimal(if (clean.startsWith(".")) "0$clean" else clean).movePointRight(6).toLong()
        }

        /** The three limits in [limits] in words against today's use. */
        fun linesOf(use: TodayUse): List<LimitLineUi> {
            val counts = use.use
            // The limiter's currency (D3A-8), as the Today tile and Usage and cost say it.
            val currency = use.currency
            val maxCalls = use.limits.maxCalls
            val maxTokens = use.limits.maxTokens
            val maxSpend = use.limits.maxSpendMicros
            return listOf(
                LimitLineUi(
                    "Answers",
                    maxCalls?.let { "${formatCount(counts.calls)} of ${formatCount(it.toLong())} today" } ?: "${answers(counts.calls)} today, no limit",
                    maxCalls != null && counts.calls >= maxCalls,
                ),
                LimitLineUi(
                    "Tokens",
                    maxTokens?.let { "${formatCount(counts.tokens)} of ${formatCount(it)} today" } ?: "${tokens(counts.tokens)} today, no limit",
                    maxTokens != null && counts.tokens >= maxTokens,
                ),
                LimitLineUi(
                    "Money",
                    when {
                        maxSpend == null -> "No money limit"
                        !counts.priced -> "No model used today has a price, so nothing is counted yet. Limit: ${moneyWords(maxSpend, currency)}"
                        else -> "${moneyWords(counts.spendMicros, currency)} of ${moneyWords(maxSpend, currency)} today (estimated)"
                    },
                    maxSpend != null && counts.spendMicros >= maxSpend,
                ),
            )
        }

        /** The limiter's own sentence when a limit is reached, else null. */
        fun reachedSentence(use: TodayUse): String? =
            use.limits.reachedBy(use.use.calls, use.use.tokens, use.use.spendMicros)?.let { use.limits.sentence(it, use.currency) }
    }
}

/** [this] settings with [limits] as today's limits. */
internal fun FrameworkSettings.withLimits(limits: DailyLimits): FrameworkSettings = copy(
    maxModelCallsPerDay = limits.maxCalls ?: maxModelCallsPerDay,
    maxTokensPerDay = limits.maxTokens ?: maxTokensPerDay,
    maxSpendPerDayMicros = limits.maxSpendMicros,
)
