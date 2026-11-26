package com.aicontrolcenter.app.ui.chat

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.WorkUsage

/**
 * What a conversation has used so far (Phase 6, goal 1, rule 29), under its transcript: its
 * tokens and, for the models the owner gave a price, the estimated cost.
 */
@Immutable
data class ChatCostUi(
    /** "This chat so far: 34,567 tokens, about US$0.03." */
    val line: String,
    /** What tokens are, and that money is an estimate (or how to see one). */
    val explanation: String,
    /** True when a cost is shown. */
    val priced: Boolean,
)

/** Plain words for "token", said wherever a count of them is shown for the first time. */
const val TOKENS_EXPLAINED: String = "Tokens are the pieces of words a model reads and writes; providers count and charge by them."

/**
 * The cost line of a chat from its usage rows ([WorkUsage] of kind CHAT, one per model) and the
 * owner's [prices]; null while the chat has used nothing. Money is added per currency, never
 * across currencies; models without a price add tokens only, and the line says so.
 */
fun chatCostOf(rows: List<WorkUsage>, prices: Map<ModelId, ModelPrice>): ChatCostUi? {
    val tokens = rows.sumOf { it.inputTokens + it.outputTokens }
    if (rows.isEmpty() || tokens == 0L) return null
    val money = moneyByCurrency(rows, prices)
    val unpriced = rows.any { it.modelId !in prices && it.inputTokens + it.outputTokens > 0 }
    val line = buildString {
        append("This chat so far: ").append(formatCount(tokens)).append(if (tokens == 1L) " token" else " tokens")
        if (money != null) {
            append(if (money.startsWith("less than")) ", " else ", about ").append(money)
            if (unpriced) append(" for the models with a price")
        }
        append('.')
    }
    val explanation = if (money != null) {
        "$TOKENS_EXPLAINED ${CostEstimate.DISCLAIMER}"
    } else {
        "$TOKENS_EXPLAINED Enter the model's prices in Models to see what this chat costs."
    }
    return ChatCostUi(line, explanation, priced = money != null)
}

/** "US$0.03", or "US$0.03 and €0.01" when the owner priced models in more than one currency; null without any price. */
internal fun moneyByCurrency(rows: List<WorkUsage>, prices: Map<ModelId, ModelPrice>): String? {
    val byCurrency = LinkedHashMap<String, Long>()
    rows.forEach { row ->
        val price = prices[row.modelId] ?: return@forEach
        val cost = CostEstimate.of(row.tokens, price) ?: return@forEach
        byCurrency[price.currencyLabel] = (byCurrency[price.currencyLabel] ?: 0L).let { if (Long.MAX_VALUE - it < cost) Long.MAX_VALUE else it + cost }
    }
    if (byCurrency.isEmpty()) return null
    return byCurrency.entries.joinToString(" and ") { (currency, micros) -> moneyText(micros, currency) }
}

/** [micros] of [currency] as the owner reads it: "US$0.03"; a cost under half a cent is "less than US$0.01", never "US$0.00". */
fun moneyText(micros: Long, currency: String): String =
    if (micros in 1 until HALF_CENT_MICROS) "less than ${DailyLimits.money(CENT_MICROS, currency)}" else DailyLimits.money(micros, currency)

private const val CENT_MICROS = 10_000L
private const val HALF_CENT_MICROS = 5_000L
