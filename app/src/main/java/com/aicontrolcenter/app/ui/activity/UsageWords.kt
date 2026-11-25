package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.TokenCounts
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.framework.llm.DailyUse
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Plain words for usage, cost and limits (Phase 6, goals 1-2, rules 6, 7, 29), shared by the Usage and cost tab, the
 * Dashboard's Today tile and the Daily limits screens (WP10a). The counts follow the daily limiter exactly
 * ([DailyUse.counted]): every call that reached a model counts as one answer, even one that failed, but not one a
 * "slow down" answer ended. The chat's cost line and the model editor (WP11b) say the same things in the same words.
 */

/** Plain words for "token", said wherever a count of them is shown for the first time. */
const val TOKENS_IN_PLAIN_WORDS: String = "Tokens are the pieces of words a model reads and writes; providers count and charge by them."

/** How the screens count an answer, in one sentence. */
const val ANSWERS_IN_PLAIN_WORDS: String =
    "Every request a model received counts as one answer, even one that failed. Requests the provider asked the app to slow down for are not counted."

/** What a limit does, in one sentence (wave-2a minors: "Limits stop work, not cost already spent"). */
const val LIMITS_STOP_WORK: String = "Limits stop work, not cost already spent: the last answer let through can take the day a little past a limit."

/** What happens when a limit is reached, for the Daily limits screens. */
const val WHEN_A_LIMIT_IS_REACHED: String =
    "When one is reached, agents stop asking models that are not on this phone until midnight. A running objective pauses " +
        "instead of failing, and a chat says so. Raise the limit, then open the paused objective and tap Resume."

/** "1 answer", "37 answers". */
internal fun answers(count: Long): String = counted(count, "answer")

/** "1 token", "12,345 tokens". */
internal fun tokens(count: Long): String = counted(count, "token")

/** "1 call", "3 calls". */
internal fun calls(count: Long): String = counted(count, "call")

internal fun counted(count: Long, word: String, plural: String = word + "s"): String =
    "${formatCount(count)} ${if (count == 1L) word else plural}"

/** "once", "3 times". */
internal fun times(count: Long): String = if (count == 1L) "once" else "${formatCount(count)} times"

/** [micros] of [currency] as the owner reads it: "US$0.03"; a cost under half a cent is "less than US$0.01", never "US$0.00". */
fun moneyWords(micros: Long, currency: String): String =
    if (micros in 1 until HALF_CENT_MICROS) "less than ${DailyLimits.money(CENT_MICROS, currency)}" else DailyLimits.money(micros, currency)

/** "0.8 seconds", "3.2 seconds", "1 minute 5 seconds". */
internal fun secondsWords(ms: Long): String = when {
    ms < 60_000 -> BigDecimal.valueOf(ms, 3).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let {
        "$it ${if (it == "1") "second" else "seconds"}"
    }
    else -> {
        val total = (ms + 500) / 1_000
        val minutes = total / 60
        val rest = total % 60
        "$minutes ${if (minutes == 1L) "minute" else "minutes"}" + if (rest > 0) " $rest ${if (rest == 1L) "second" else "seconds"}" else ""
    }
}

/** "under a minute", "3 minutes", "1 hour 5 minutes": how long something waited for the owner. */
internal fun waitWords(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return when {
        ms < 60_000 -> "under a minute"
        minutes < 60 -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            "$hours ${if (hours == 1L) "hour" else "hours"}" + if (rest > 0) " $rest ${if (rest == 1L) "minute" else "minutes"}" else ""
        }
    }
}

/** Why calls failed, in words. */
internal fun failureWords(kind: ErrorClass): String = when (kind) {
    ErrorClass.AUTH -> "key rejected"
    ErrorClass.RATE_LIMITED -> "asked to slow down"
    ErrorClass.OVERLOADED -> "too busy"
    ErrorClass.TIMEOUT -> "took too long"
    ErrorClass.NETWORK -> "could not connect"
    ErrorClass.SERVER -> "trouble at the provider"
    ErrorClass.INVALID -> "request refused"
    ErrorClass.CONTEXT -> "conversation too long"
    ErrorClass.OTHER -> "other reasons"
}

/** Where a model runs, in words. */
internal fun whereWords(locality: Locality): String = when (locality) {
    Locality.ON_DEVICE -> "On this phone"
    Locality.LOCAL_NETWORK -> "On your own network"
    Locality.CLOUD -> "In the cloud"
}

/** The answers the daily limits count in [totals]: those that sent a request, but not those a "slow down" answer ended. */
internal fun countedAnswers(totals: UsageTotals): Long = DailyUse.counted(totals)

/** Calls that ended because the provider asked the app to slow down: shown as such, never as answers. */
internal fun slowDownCalls(totals: UsageTotals): Long = totals.errors[ErrorClass.RATE_LIMITED] ?: 0

/** The tokens of one model's row. */
internal fun ModelUsage.tokenCounts(): TokenCounts = TokenCounts(totals.inputTokens, totals.outputTokens, totals.cachedInputTokens)

/**
 * The estimated cost of [rows] at [prices], per currency ("US$0.03", or "US$0.03 and €0.01" when the owner priced models
 * in more than one currency); null when none of the rows' models has a price. Models without a price add nothing.
 */
internal fun costWords(rows: List<ModelUsage>, prices: Map<ModelId, ModelPrice>): String? {
    val byCurrency = LinkedHashMap<String, Long>()
    rows.forEach { row ->
        val price = prices[row.modelId] ?: return@forEach
        val cost = CostEstimate.of(row.tokenCounts(), price) ?: return@forEach
        byCurrency[price.currencyLabel] = (byCurrency[price.currencyLabel] ?: 0L).let { if (Long.MAX_VALUE - it < cost) Long.MAX_VALUE else it + cost }
    }
    if (byCurrency.isEmpty()) return null
    return byCurrency.entries.joinToString(" and ") { (currency, micros) -> moneyWords(micros, currency) }
}

/**
 * The currency the money limit is read in: the one the owner's prices are in when they all share one, else US$ (the
 * limiter adds the estimated cost of every priced model, whatever its currency).
 */
internal fun limitCurrency(prices: Map<ModelId, ModelPrice>): String =
    prices.values.map { it.currencyLabel }.distinct().singleOrNull() ?: ModelPrice.DEFAULT_CURRENCY

/** "about US$0.12" or "less than US$0.01" (never "about less than …"). */
internal fun aboutMoney(money: String): String = if (money.startsWith("less than")) money else "about $money"

/** "Scripted model", "Scripted model and Grok 4", "A, B and C". */
internal fun listWords(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.single()
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * How reliable [totals] were, in plain sentences: answered, failed (by reason), stopped, refused before sending, and
 * the provider's "slow down" answers, which are not counted as answers.
 */
internal fun reliabilityLines(totals: UsageTotals, providerName: String): List<String> {
    if (totals.calls == 0L) return emptyList()
    val lines = mutableListOf<String>()
    val slowDown = slowDownCalls(totals)
    val otherFailures = totals.failed - slowDown
    if (otherFailures <= 0 && totals.cancelled == 0L && totals.blocked == 0L && slowDown == 0L) {
        lines += if (totals.ok == 1L) "Reliable: the one request was answered." else "Reliable: all ${formatCount(totals.ok)} requests were answered."
    } else {
        lines += buildString {
            append(formatCount(totals.ok)).append(" answered")
            if (otherFailures > 0) {
                append(", ").append(formatCount(otherFailures)).append(" failed")
                val reasons = ErrorClass.entries.filter { it != ErrorClass.RATE_LIMITED }
                    .mapNotNull { kind -> totals.errors[kind]?.let { "${formatCount(it)} ${failureWords(kind)}" } }
                if (reasons.isNotEmpty()) append(" (").append(reasons.joinToString(", ")).append(")")
            }
            if (totals.cancelled > 0) append(", ").append(formatCount(totals.cancelled)).append(" stopped")
            if (totals.blocked > 0) append(", ").append(formatCount(totals.blocked)).append(" not sent")
            append('.')
        }
    }
    if (slowDown > 0) {
        lines += "${calls(slowDown).replaceFirstChar { it.uppercase() }} ended because $providerName asked the app to slow down. " +
            "They are not counted as answers."
    }
    if (totals.rateLimitedWaits > 0 && slowDown == 0L) {
        lines += "$providerName asked the app to slow down ${times(totals.rateLimitedWaits)}; the app waited and went on."
    }
    if (totals.retries > 0) lines += "The app had to try again ${times(totals.retries)}."
    if (totals.truncated > 0) lines += "${answers(totals.truncated).replaceFirstChar { it.uppercase() }} stopped at the longest reply the model may write."
    if (totals.substituted > 0) lines += "Another model than the one asked for answered ${times(totals.substituted)}."
    if (totals.noUsage > 0) lines += "The provider did not say how many tokens ${answers(totals.noUsage)} used."
    return lines
}

/** Answers needed before average times are shown: fewer say little. */
const val SPEED_MIN_ANSWERS: Long = 5

/** How fast [totals] were, or null with fewer than [SPEED_MIN_ANSWERS] answers. */
internal fun speedLine(totals: UsageTotals): String? {
    if (totals.ok < SPEED_MIN_ANSWERS) return null
    val whole = totals.averageAnswerMs ?: return null
    val first = totals.averageTtftMs
    return if (first != null) {
        "On average the first words came after ${secondsWords(first)}, the whole answer after ${secondsWords(whole)}."
    } else {
        "On average a whole answer took ${secondsWords(whole)}."
    }
}

private const val CENT_MICROS = 10_000L
private const val HALF_CENT_MICROS = 5_000L
