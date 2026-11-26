package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.usage.AgentModelUsage
import com.aicontrolcenter.core.usage.AgentUsage
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** The spans the Usage and cost tab can show, each the owner's local days ([UsageRange]). */
enum class UsagePeriod(val title: String, val phrase: String) {
    TODAY("Today", "today"),
    WEEK("7 days", "in the last 7 days"),
    DAYS_30("30 days", "in the last 30 days"),
    THIS_MONTH("This month", "this month"),
    ;

    fun range(now: Long, zone: ZoneId): UsageRange = when (this) {
        TODAY -> UsageRange.day(now, zone)
        WEEK -> UsageRange.lastDays(7, now, zone)
        DAYS_30 -> UsageRange.lastDays(30, now, zone)
        THIS_MONTH -> UsageRange.month(now, zone)
    }
}

/** The headline numbers of a period. */
@Immutable
data class UsageSummaryUi(
    /** Answers as the daily limits count them, every model ([DailyUse.counted]). */
    val answers: Long,
    val tokens: Long,
    /** "US$0.12" (models with a price only), or null when no model used has a price. */
    val cost: String?,
    /** Plain sentences under the numbers: today's limits, models on this phone, slow-down answers, models without a price. */
    val lines: List<String>,
    /** Models used off this phone that have no price, for "Add a price". */
    val unpriced: List<UnpricedModelUi>,
)

/** A model without a price, which the owner can open to add one. */
@Immutable
data class UnpricedModelUi(val modelId: ModelId, val name: String)

/** One model's row: how much, at what estimated cost, how reliably and how fast. */
@Immutable
data class ModelUsageUi(
    val modelId: ModelId,
    val name: String,
    /** "Test server · In the cloud". */
    val where: String,
    val answers: Long,
    val tokens: Long,
    /** "about US$0.12", "No price entered", "Free: on this phone". */
    val cost: String,
    val needsPrice: Boolean,
    val reliability: List<String>,
    /** Average times, only with [SPEED_MIN_ANSWERS] answers or more. */
    val speed: String?,
    /** What the log is searched for to show this model's calls: "<model> via <provider>", as each call's entry says. */
    val logText: String,
)

/**
 * One agent's row. [agentId] null: calls the app made for no agent. [cost]: "about US$0.12" (every model it used off
 * this phone has a price), "about US$0.12 for the models with a price", "No price entered" or "Free: on this phone";
 * null when nothing is known of its models.
 */
@Immutable
data class AgentUsageUi(val agentId: AgentId?, val name: String, val answers: Long, val tokens: Long, val cost: String? = null)

/** How long requests waited for the owner in the period. */
@Immutable
data class WaitingTimesUi(
    /** One line per kind of request that was answered or ran out of time. */
    val lines: List<String>,
    /** How many wait now, if any. */
    val waitingNow: Int,
    /** Set when the period reaches further back than the requests read. */
    val partial: String?,
)

/** What the Usage and cost tab shows. */
@Immutable
data class UsageUiState(
    val isLoading: Boolean = true,
    val period: UsagePeriod = UsagePeriod.TODAY,
    /** Null when nothing at all was used in the period. */
    val summary: UsageSummaryUi? = null,
    val models: List<ModelUsageUi> = emptyList(),
    val agents: List<AgentUsageUi> = emptyList(),
    val waiting: WaitingTimesUi? = null,
)

/**
 * The Usage and cost tab (WP10a). Read only: usage numbers (rule 7: numbers and ids only), the prices as read, agent and
 * model names, and the recent requests for "how long requests waited for you". Built off the main thread
 * ([computation], `Dispatchers.Default` in the app; the caller's dispatcher in tests) and conflated.
 *
 * Answers are counted as the daily limiter counts them ([DailyUse.counted]); a call that ended because the provider
 * asked the app to slow down is shown as such, never as an answer. Costs are estimates from the owner's prices (rule 29).
 *
 * @param recentRequests the owner's newest requests (`OwnerRequests.observeRecent`), for answer times only.
 * @param zone the owner's time zone (`AppGraph.zone`), where a day and a month start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    private val reports: UsageReports,
    private val prices: ModelPrices,
    private val directory: RegistryDirectory,
    private val recentRequests: (limit: Int) -> Flow<List<OwnerRequest>>,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {

    private val period = MutableStateFlow(UsagePeriod.TODAY)

    val uiState: StateFlow<UsageUiState> = period.flatMapLatest { selected ->
        val range = selected.range(clock.now(), zone())
        val usage = reports.observeByModel(range).distinctUntilChanged().mapLatest { rows ->
            Triple(rows, reports.byAgent(range), reports.byAgentAndModel(range))
        }
        combine(usage, prices.observeAll(), directory.observe(), recentRequests(REQUEST_LIMIT)) { (models, agents, pairs), priced, snapshot, requests ->
            build(selected, range, models, agents, pairs, priced.associateBy { it.modelId }, snapshot, requests)
        }
    }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState())

    fun selectPeriod(selected: UsagePeriod) {
        period.value = selected
    }

    private fun build(
        selected: UsagePeriod,
        range: UsageRange,
        models: List<ModelUsage>,
        agents: List<AgentUsage>,
        pairs: List<AgentModelUsage>,
        prices: Map<ModelId, ModelPrice>,
        snapshot: RegistrySnapshot,
        requests: List<OwnerRequest>,
    ): UsageUiState {
        val used = models.filter { it.totals.calls > 0 }
        val byAgent = pairs.groupBy { it.agentId }
        val modelsById = models.associateBy { it.modelId }
        return UsageUiState(
            isLoading = false,
            period = selected,
            summary = if (used.isEmpty()) null else summary(selected, used, prices, snapshot),
            models = used.map { modelRow(it, prices) },
            agents = agents.filter { it.totals.calls > 0 }.map { agentRow(it, byAgent[it.agentId].orEmpty(), modelsById, prices, snapshot) },
            waiting = waiting(requests, range),
        )
    }

    private fun summary(selected: UsagePeriod, used: List<ModelUsage>, prices: Map<ModelId, ModelPrice>, snapshot: RegistrySnapshot): UsageSummaryUi {
        val all = used.fold(UsageTotals()) { sum, row -> sum + row.totals }
        val onPhone = used.filter { !it.locality.leavesDevice }
        val unpriced = used.filter { it.locality.leavesDevice && it.modelId !in prices && it.totals.totalTokens > 0 }
        val lines = mutableListOf<String>()
        if (selected == UsagePeriod.TODAY) {
            // Exactly the limiter's counts: models off this phone, at the prices entered now.
            val counted = DailyUse.of(used, prices)
            val limits = snapshot.settings.dailyLimits
            lines += buildString {
                append("Today's limits count ").append(answers(counted.calls))
                limits.maxCalls?.let { append(" of ").append(formatCount(it.toLong())) }
                append(" and ").append(tokens(counted.tokens))
                limits.maxTokens?.let { append(" of ").append(formatCount(it)) }
                val spendLimit = limits.maxSpendMicros
                if (counted.priced && spendLimit != null) {
                    val currency = limitCurrency(prices)
                    append(", and ").append(moneyWords(counted.spendMicros, currency))
                        .append(" of ").append(moneyWords(spendLimit, currency))
                }
                append('.')
            }
        }
        if (onPhone.isNotEmpty()) {
            val phoneAnswers = onPhone.sumOf { countedAnswers(it.totals) }
            lines += "${answers(phoneAnswers).replaceFirstChar { it.uppercase() }} came from models on this phone: they cost nothing, " +
                "and the daily limits do not count them."
        }
        val slowDown = slowDownCalls(all)
        if (slowDown > 0) {
            lines += "${calls(slowDown).replaceFirstChar { it.uppercase() }} ended because the provider asked the app to slow down. " +
                "They are not counted as answers."
        }
        if (unpriced.isNotEmpty()) {
            lines += "${listWords(unpriced.map { it.modelLabel })} ${if (unpriced.size == 1) "has" else "have"} no price, so " +
                "${if (unpriced.size == 1) "its" else "their"} tokens are not in the cost. Add a price in Models (0 if it is free)."
        }
        return UsageSummaryUi(
            answers = used.sumOf { countedAnswers(it.totals) },
            tokens = all.totalTokens,
            cost = costWords(used, prices),
            lines = lines,
            unpriced = unpriced.map { UnpricedModelUi(it.modelId, it.modelLabel) },
        )
    }

    private fun modelRow(row: ModelUsage, prices: Map<ModelId, ModelPrice>): ModelUsageUi {
        val price = prices[row.modelId]
        val cost = when {
            price != null -> costWords(listOf(row), prices)?.let(::aboutMoney) ?: "No cost"
            !row.locality.leavesDevice -> "Free: on this phone"
            else -> "No price entered"
        }
        return ModelUsageUi(
            modelId = row.modelId,
            name = row.modelLabel,
            where = "${row.providerLabel} · ${whereWords(row.locality)}",
            answers = countedAnswers(row.totals),
            tokens = row.totals.totalTokens,
            cost = cost,
            needsPrice = price == null && row.locality.leavesDevice,
            reliability = reliabilityLines(row.totals, row.providerLabel),
            speed = speedLine(row.totals),
            logText = "${row.modelLabel} via ${row.providerLabel}",
        )
    }

    private fun agentRow(
        row: AgentUsage,
        pairs: List<AgentModelUsage>,
        models: Map<ModelId, ModelUsage>,
        prices: Map<ModelId, ModelPrice>,
        snapshot: RegistrySnapshot,
    ): AgentUsageUi {
        val name = when (val id = row.agentId) {
            null -> "The app itself"
            else -> snapshot.agentsById[id]?.profile?.name ?: "An agent that no longer exists"
        }
        return AgentUsageUi(row.agentId, name, countedAnswers(row.totals), row.totals.totalTokens, agentCost(pairs, models, prices))
    }

    /**
     * An agent's estimated cost from the prices of the models it used (rule 29: never a cost without a price): its
     * tokens on each model at that model's price, added per currency. Models on this phone cost nothing. Null when the
     * store cannot tell which models the agent used.
     */
    private fun agentCost(pairs: List<AgentModelUsage>, models: Map<ModelId, ModelUsage>, prices: Map<ModelId, ModelPrice>): String? {
        val used = pairs.filter { it.totals.totalTokens > 0 }
        if (used.isEmpty()) return null
        val offPhone = used.filter { models[it.modelId]?.locality?.leavesDevice != false }
        if (offPhone.isEmpty()) return "Free: on this phone"
        val priced = offPhone.filter { it.modelId in prices }
        val money = agentMoney(priced, prices) ?: return "No price entered"
        return if (priced.size == offPhone.size) aboutMoney(money) else "${aboutMoney(money)} for the models with a price"
    }

    /** How long the owner's answers took in [range]: approvals, permission requests and questions, each on its own line. */
    private fun waiting(requests: List<OwnerRequest>, range: UsageRange): WaitingTimesUi? {
        val inRange = requests.filter { it.createdAt >= range.fromMs && it.createdAt < range.untilMs }
        val waitingNow = inRange.count { it.status == OwnerRequestStatus.PENDING }
        val lines = OwnerRequestKind.entries.mapNotNull { kind ->
            val ofKind = inRange.filter { it.kind == kind }
            val answered = ofKind.filter { (it.status == OwnerRequestStatus.APPROVED || it.status == OwnerRequestStatus.DENIED) && it.decidedAt != null }
            val expired = ofKind.count { it.status == OwnerRequestStatus.EXPIRED }
            if (answered.isEmpty() && expired == 0) return@mapNotNull null
            buildString {
                append(kindWords(kind, answered.size + expired)).append(": ")
                if (answered.isNotEmpty()) {
                    val waits = answered.map { (it.decidedAt!! - it.createdAt).coerceAtLeast(0) }
                    append("you answered ").append(formatCount(answered.size.toLong()))
                    append(" after ").append(waitWords(waits.sum() / waits.size)).append(" on average")
                    if (answered.size > 1) append(", the longest after ").append(waitWords(waits.max()))
                }
                if (expired > 0) {
                    if (answered.isNotEmpty()) append("; ")
                    append(formatCount(expired.toLong())).append(" ran out of time before you answered")
                }
                append('.')
            }
        }
        if (lines.isEmpty() && waitingNow == 0) return null
        val partial = if (requests.size >= REQUEST_LIMIT && (requests.minOfOrNull { it.createdAt } ?: Long.MIN_VALUE) > range.fromMs) {
            "Counted over your newest ${formatCount(REQUEST_LIMIT.toLong())} requests."
        } else {
            null
        }
        return WaitingTimesUi(lines, waitingNow, partial)
    }

    private fun kindWords(kind: OwnerRequestKind, count: Int): String = when (kind) {
        OwnerRequestKind.APPROVAL -> if (count == 1) "1 action to approve" else "$count actions to approve"
        OwnerRequestKind.PERMISSION -> if (count == 1) "1 permission request" else "$count permission requests"
        OwnerRequestKind.QUESTION -> if (count == 1) "1 question" else "$count questions"
    }

    companion object {
        /** The newest requests read for answer times. */
        const val REQUEST_LIMIT: Int = 500
    }
}
