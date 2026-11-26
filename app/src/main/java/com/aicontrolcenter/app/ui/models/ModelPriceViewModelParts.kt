package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.chat.moneyText
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.TokenCounts
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.framework.llm.DailyUse
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * The model editor's cost parts (Phase 6, goal 1, rule 29): the price the owner types in from the
 * provider's price page (never fetched), how fast and reliable the model has been, and the cheaper
 * model for helper agents (Tier B). Pure functions and the price fields, so each is tested without a screen.
 */

/** The price fields. The keyboard writes them directly; the view model reads them at Save. */
class ModelPriceFields {
    val input = TextFieldState()
    val output = TextFieldState()

    /** Empty means cached input costs the input price. */
    val cachedInput = TextFieldState()
    val currency = TextFieldState(ModelPrice.DEFAULT_CURRENCY)

    internal fun read() = PriceTexts(input.text.toString(), output.text.toString(), cachedInput.text.toString(), currency.text.toString())

    /** Shows [price] (or empty fields and the usual currency without one). */
    internal fun fill(price: ModelPrice?) {
        input.putText(price?.inputPerMTokMicros?.let(::perMillionText).orEmpty())
        output.putText(price?.outputPerMTokMicros?.let(::perMillionText).orEmpty())
        cachedInput.putText(price?.cachedInputPerMTokMicros?.let(::perMillionText).orEmpty())
        currency.putText(price?.currencyLabel ?: ModelPrice.DEFAULT_CURRENCY)
    }
}

/** What the price fields hold, as typed. */
internal data class PriceTexts(val input: String, val output: String, val cachedInput: String, val currency: String) {
    val isEmpty: Boolean get() = input.isBlank() && output.isBlank() && cachedInput.isBlank()

    /** The texts [price] is shown as, to tell whether the owner changed anything. */
    companion object {
        fun of(price: ModelPrice?) = PriceTexts(
            price?.inputPerMTokMicros?.let(::perMillionText).orEmpty(),
            price?.outputPerMTokMicros?.let(::perMillionText).orEmpty(),
            price?.cachedInputPerMTokMicros?.let(::perMillionText).orEmpty(),
            price?.currencyLabel ?: ModelPrice.DEFAULT_CURRENCY,
        )
    }

    /** Same values, whatever spaces or trailing zeros were typed. */
    fun sameAs(other: PriceTexts): Boolean =
        amountOrText(input) == amountOrText(other.input) && amountOrText(output) == amountOrText(other.output) &&
            amountOrText(cachedInput) == amountOrText(other.cachedInput) && currency.trim() == other.currency.trim()

    private fun amountOrText(text: String): Any = parseAmountMicros(text) ?: text.trim()
}

/** A price read from the fields, or the sentence that says what to fix. */
internal sealed interface PriceParse {
    data class Valid(val price: ModelPrice) : PriceParse

    data class Invalid(val inputError: String? = null, val outputError: String? = null, val cachedError: String? = null, val general: String? = null) : PriceParse
}

internal const val AMOUNT_HINT = "Write an amount like 3 or 0.15."

/** Reads the fields into a price for [modelId]: input and output are needed, cached input is optional. */
internal fun PriceTexts.toPrice(modelId: ModelId): PriceParse {
    val inputMicros = parseAmountMicros(input)
    val outputMicros = parseAmountMicros(output)
    val cachedMicros = if (cachedInput.isBlank()) null else parseAmountMicros(cachedInput)
    val inputError = when {
        input.isBlank() -> "Enter the input price."
        inputMicros == null -> AMOUNT_HINT
        else -> null
    }
    val outputError = when {
        output.isBlank() -> "Enter the output price."
        outputMicros == null -> AMOUNT_HINT
        else -> null
    }
    val cachedError = if (cachedInput.isNotBlank() && cachedMicros == null) AMOUNT_HINT else null
    if (inputError != null || outputError != null || cachedError != null) return PriceParse.Invalid(inputError, outputError, cachedError)
    val label = currency.trim()
    ModelPrice.check(inputMicros!!, outputMicros!!, cachedMicros, label)?.let { return PriceParse.Invalid(general = it) }
    return PriceParse.Valid(ModelPrice(modelId, inputMicros, outputMicros, cachedMicros, label))
}

/**
 * An amount per million tokens as typed ("3", "0.15", "2,50", " 1.5 "), in millionths; null when it is not a
 * plain amount of at most six decimals. A comma is read as the decimal point, as many phones type it.
 */
internal fun parseAmountMicros(text: String): Long? {
    val cleaned = text.trim().replace(',', '.')
    if (cleaned.isEmpty() || !AMOUNT.matches(cleaned)) return null
    return try {
        val value = BigDecimal(cleaned)
        if (value.scale() > 6 && value.stripTrailingZeros().scale() > 6) return null
        value.movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (e: ArithmeticException) {
        null
    } catch (e: NumberFormatException) {
        null
    }
}

private val AMOUNT = Regex("""\d{1,7}(\.\d{0,8})?|\.\d{1,8}""")

/** Millionths per million tokens as the owner types them: 3000000 → "3", 150000 → "0.15". */
internal fun perMillionText(micros: Long): String = BigDecimal.valueOf(micros, 6).stripTrailingZeros().toPlainString()

/** Keeps what can be an amount: digits and one decimal point or comma, at most [max] characters. */
internal fun amountOnly(max: Int = 14): InputTransformation = InputTransformation.byValue { _, proposed ->
    val kept = StringBuilder()
    var separator = false
    for (c in proposed) {
        when {
            c.isDigit() -> kept.append(c)
            (c == '.' || c == ',') && !separator -> {
                separator = true
                kept.append(c)
            }
        }
        if (kept.length >= max) break
    }
    kept
}

/** "US$3.00 in, US$15.00 out per million tokens"; with a cached price: "…, cached input US$0.30". */
internal fun priceSentence(price: ModelPrice): String = buildString {
    val c = price.currencyLabel
    append(c).append(amountText(price.inputPerMTokMicros)).append(" in, ")
    append(c).append(amountText(price.outputPerMTokMicros)).append(" out per million tokens")
    price.cachedInputPerMTokMicros?.let { append(", cached input ").append(c).append(amountText(it)) }
}

/** An amount to read, with at least two decimals as prices are written: 3000000 → "3.00", 75000 → "0.075". */
internal fun amountText(micros: Long): String {
    val value = BigDecimal.valueOf(micros, 6).stripTrailingZeros()
    return (if (value.scale() < 2) value.setScale(2) else value).toPlainString()
}

/** The cost of a typical stretch of work at [price]: three parts read to one part written, a million tokens in all. */
internal fun typicalCostMicros(price: ModelPrice): Long = CostEstimate.of(TypicalWork, price) ?: Long.MAX_VALUE

private val TypicalWork = TokenCounts(input = 750_000, output = 250_000)

// --- Speed and reliability ---

/** The model's speed and reliability over the last days, in words. */
@Immutable
data class SpeedUi(val lines: List<String>, val hasCalls: Boolean)

/** How many days "Speed and reliability" looks back. */
internal const val SPEED_DAYS = 30

/** Answers needed before average times are shown: fewer say little. */
internal const val SPEED_MIN_ANSWERS = 5

/** [totals] of this model over the last [SPEED_DAYS] days, null when it made no call, in plain sentences. */
internal fun speedOf(totals: UsageTotals?): SpeedUi {
    if (totals == null || totals.calls == 0L) return SpeedUi(listOf("No calls in the last $SPEED_DAYS days."), hasCalls = false)
    val lines = mutableListOf<String>()
    // "Answers" as the daily limits and Usage and cost count them: every request a model received, even one that
    // failed, but not one the provider asked the app to slow down for (D3A-9). "Came back" and "failed" are outcomes.
    val counted = DailyUse.counted(totals)
    val slowDown = totals.errors[ErrorClass.RATE_LIMITED] ?: 0
    lines += buildString {
        append("Last $SPEED_DAYS days: ").append(count(counted, "answer")).append(" counted")
        val outcomes = listOfNotNull(
            "${formatCount(totals.ok)} came back".takeIf { totals.ok != counted || totals.failed > 0 || totals.cancelled > 0 },
            (totals.failed - slowDown).takeIf { it > 0 }?.let { "$it failed" },
            totals.cancelled.takeIf { it > 0 }?.let { "$it stopped" },
        )
        if (outcomes.isNotEmpty()) append(" (").append(outcomes.joinToString(", ")).append(")")
        append('.')
        if (slowDown > 0) append(" ").append(count(slowDown, "request")).append(" ended because the provider asked the app to slow down; they are not counted.")
        if (totals.blocked > 0) append(" ").append(count(totals.blocked, "request")).append(" not sent.")
    }
    if (totals.ok >= SPEED_MIN_ANSWERS) {
        totals.averageAnswerMs?.let { lines += "A whole answer took ${seconds(it)} on average." }
        totals.averageTtftMs?.let { lines += "The first words came ${seconds(it).let { s -> if (s == "under a second") "in $s" else "after $s" }} on average." }
    } else if (totals.ok > 0) {
        lines += "Average times are shown once it has given $SPEED_MIN_ANSWERS answers."
    }
    val failures = ErrorClass.entries.mapNotNull { kind -> totals.errors[kind]?.let { "$it ${failureWords(kind)}" } }
    if (failures.isNotEmpty()) lines += "Why calls failed: " + failures.joinToString(", ") + "."
    if (totals.rateLimitedWaits > 0) lines += "The provider asked the app to slow down ${times(totals.rateLimitedWaits)}."
    if (totals.noUsage > 0) lines += "The provider did not say how many tokens ${count(totals.noUsage, "answer")} used."
    return SpeedUi(lines, hasCalls = true)
}

private fun failureWords(kind: ErrorClass): String = when (kind) {
    ErrorClass.AUTH -> "key rejected"
    ErrorClass.RATE_LIMITED -> "asked to slow down"
    ErrorClass.OVERLOADED -> "too busy"
    ErrorClass.TIMEOUT -> "took too long"
    ErrorClass.NETWORK -> "could not connect"
    ErrorClass.SERVER -> "server trouble"
    ErrorClass.INVALID -> "request refused"
    ErrorClass.CONTEXT -> "conversation too long"
    ErrorClass.OTHER -> "other"
}

private fun count(n: Long, word: String) = "${formatCount(n)} ${if (n == 1L) word else word + "s"}"

private fun times(n: Long) = if (n == 1L) "once" else "${formatCount(n)} times"

/** "under a second", "0.8 seconds", "3.2 seconds", "1 minute 5 seconds" (never "0 seconds", D3A-16). */
internal fun seconds(ms: Long): String = when {
    ms < 500 -> "under a second"
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

// --- The cheaper model for helpers (Tier B) ---

/**
 * The cheaper model for helper agents, as the model editor shows it: either this model is the cheapest of the
 * owner's models that can use tools and have a price, and helpers on dearer models could use it ([isThisModel],
 * offered through "Use for agents…" with the helpers ticked), or another one is cheaper than this one.
 */
@Immutable
data class CheaperHelperUi(
    val isThisModel: Boolean,
    val title: String,
    val text: String,
    /** The helper agents that would change, for "Use for helpers…" (empty when this is not the cheapest). */
    val helpers: Set<com.aicontrolcenter.core.AgentId> = emptySet(),
    /**
     * Ticked when "Use for helpers…" opens: none when this model is rated lower at reasoning than a model the helpers use
     * now, so the owner chooses one at a time (D3A-10).
     */
    val preselected: Set<com.aicontrolcenter.core.AgentId> = helpers,
    /** "Llama 3.2 3B is rated Basic at reasoning; …": the quality trade-off, also said in the chooser; null when there is none. */
    val tradeOff: String? = null,
)

/**
 * Among the enabled models that can call tools, have a price in [currency] and could run now (provider on and
 * listed; not blocked by local-only mode; not the simulator), the cheapest for typical work; null when none.
 */
internal fun RegistrySnapshot.cheapestPriced(prices: Map<ModelId, ModelPrice>, currency: String): Pair<ModelDescriptor, ModelPrice>? =
    models.asSequence()
        .filter { it.enabled && it.capabilities.toolCalling && !it.isSimulator && it.status != ModelStatus.UNAVAILABLE }
        .filter { model ->
            val provider = provider(model)
            // A model on this phone costs nothing and takes no price (D3A-10): it is never "the cheapest priced".
            provider != null && provider.enabled && provider.effectiveLocality != Locality.ON_DEVICE &&
                !(settings.localOnlyMode && provider.effectiveLocality.leavesDevice)
        }
        .mapNotNull { model -> prices[model.id]?.takeIf { it.currencyLabel == currency }?.let { model to it } }
        .minWithOrNull(compareBy<Pair<ModelDescriptor, ModelPrice>> { typicalCostMicros(it.second) }.thenBy { it.first.displayName.lowercase() })

/** The suggestion for the editor of [model], or null when there is nothing to suggest. */
internal fun RegistrySnapshot.cheaperHelper(model: ModelDescriptor, prices: Map<ModelId, ModelPrice>): CheaperHelperUi? {
    // A model on this phone is free already: no cheaper one to suggest, and it is never offered as the cheapest (D3A-10).
    if (provider(model)?.effectiveLocality == Locality.ON_DEVICE) return null
    val own = prices[model.id]
    val currency = own?.currencyLabel ?: ModelPrice.DEFAULT_CURRENCY
    val (cheapest, cheapestPrice) = cheapestPriced(prices, currency) ?: return null
    val cheapestCost = typicalCostMicros(cheapestPrice)
    if (cheapest.id == model.id) {
        // Helpers: every agent but the Directors whose model has a price in the same currency and costs more for the
        // same work. One on a model without a price is left alone: it may be free (on this phone or the owner's computer).
        val helpers = agents.filter { entry ->
            entry.profile.kind != AgentKind.DIRECTOR && !entry.isDesignatedDirector && entry.profile.modelId != model.id &&
                entry.profile.modelId?.let { prices[it] }?.let { it.currencyLabel == currency && typicalCostMicros(it) > cheapestCost } == true
        }
        if (helpers.isEmpty()) return null
        val names = listNames(helpers.map { it.profile.name }.sortedBy { it.lowercase() })
        val tradeOff = tradeOffOf(model, helpers.mapNotNull { it.profile.modelId?.let(modelsById::get) })
        return CheaperHelperUi(
            isThisModel = true,
            title = "Cheaper model for helpers",
            text = "${model.displayName} costs the least of your models that can use tools (${priceSentence(cheapestPrice)}). " +
                "Helpers do most of an objective's work, so giving it to them spends less: $names. The Director keeps its own model." +
                (tradeOff?.let { " $it" } ?: ""),
            helpers = helpers.map { it.profile.id }.toSet(),
            preselected = if (tradeOff != null) emptySet() else helpers.map { it.profile.id }.toSet(),
            tradeOff = tradeOff,
        )
    }
    if (own == null || typicalCostMicros(own) <= cheapestCost) return null
    val factor = typicalCostMicros(own).toDouble() / cheapestCost.coerceAtLeast(1)
    val howMuch = if (factor >= 2) "about ${factor.toInt()} times less" else "less"
    return CheaperHelperUi(
        isThisModel = false,
        title = "A cheaper model can use tools",
        text = "${cheapest.displayName} costs $howMuch for the same work (${priceSentence(cheapestPrice)}). " +
            "To spend less, open it in Models and give it to helper agents with \"Use for agents…\".",
    )
}

/**
 * What giving the helpers [model] trades away, by the reasoning ratings (D3A-10): "Llama 3.2 3B is rated Basic at
 * reasoning; the helpers now use Claude Sonnet 4.5, rated Strong. Cheaper models can give weaker results; try it on one
 * helper first." Null when no model the helpers use now is rated higher (or a rating is not known).
 */
internal fun tradeOffOf(model: ModelDescriptor, helperModels: List<ModelDescriptor>): String? {
    val tier = model.capabilities.reasoning
    if (tier == CapabilityTier.UNKNOWN) return null
    val stronger = helperModels.distinctBy { it.id }
        .filter { it.capabilities.reasoning != CapabilityTier.UNKNOWN && it.capabilities.reasoning > tier }
        .sortedWith(compareByDescending<ModelDescriptor> { it.capabilities.reasoning }.thenBy { it.displayName.lowercase() })
    if (stronger.isEmpty()) return null
    val now = listNames(stronger.map { "${it.displayName}, rated ${tierName(it.capabilities.reasoning)}" })
    return "${model.displayName} is rated ${tierName(tier)} at reasoning; the helpers now use $now. " +
        "Cheaper models can give weaker results; try it on one helper first."
}

private fun tierName(tier: CapabilityTier): String = when (tier) {
    CapabilityTier.UNKNOWN -> "unknown"
    CapabilityTier.BASIC -> "Basic"
    CapabilityTier.GOOD -> "Good"
    CapabilityTier.STRONG -> "Strong"
}

/** "about US$0.45 for a typical million tokens", for the price section. */
internal fun typicalCostLine(price: ModelPrice): String =
    "A million tokens of typical work (three parts read, one part written) would cost about ${moneyText(typicalCostMicros(price), price.currencyLabel)}."
