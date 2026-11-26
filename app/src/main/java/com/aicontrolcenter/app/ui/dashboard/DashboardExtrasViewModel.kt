package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.health.SafeStartControl
import com.aicontrolcenter.app.ui.activity.UnpricedModelUi
import com.aicontrolcenter.app.ui.activity.answers
import com.aicontrolcenter.app.ui.activity.countedAnswers
import com.aicontrolcenter.app.ui.activity.listWords
import com.aicontrolcenter.app.ui.activity.moneyWords
import com.aicontrolcenter.app.ui.activity.times
import com.aicontrolcenter.app.ui.activity.tokens
import com.aicontrolcenter.app.ui.components.LIMIT_PAUSES_OBJECTIVES
import com.aicontrolcenter.app.ui.components.RESUME_AFTER_LIMIT
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.usage.TodayUsage
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Today tile: today's use as the limits count it, against the limits, and this month. Its money is what the limiter
 * counts toward the money limit (the models with a price), so the tile, Activity › Usage and cost and Settings › Daily
 * limits show the same figure on the same day (D3A-1); models without a price are named under it.
 */
@Immutable
data class TodayTileUi(
    /** False before any model off this phone was used today. */
    val used: Boolean,
    val answers: Long,
    val tokens: Long,
    val limits: DailyLimits,
    /** The cost tile's value: today's estimated cost as the money limit counts it ("US$0.38"), or [COST_NOT_KNOWN]. */
    val cost: String,
    /** Under the cost: "of US$1.00", "no price entered", or null when there is no money limit. */
    val costCaption: String?,
    /** True when the money limit is reached now. */
    val costReached: Boolean = false,
    /** What the cost leaves out ("Llama 3.3 70B has no price yet, so its tokens are not in the cost."); null when nothing. */
    val costNote: String?,
    val unpriced: List<UnpricedModelUi>,
    /** "This month: 1,234 answers · 2,345,678 tokens · US$4.56", or null when nothing was used this month or it is not known. */
    val month: String?,
    /** The limiter's sentence when a limit is reached now. */
    val reached: String?,
    /** The currency the money limit is shown in. */
    val currency: String,
    /** "Models on this phone also answered 6 times today: free, and not counted."; null when none did or it is not known. */
    val phoneNote: String? = null,
)

/** One step of Getting started (D47). */
@Immutable
data class GettingStartedStepUi(val title: String, val text: String, val action: String, val done: Boolean, val target: GettingStartedTarget)

/** Where a Getting started step leads. */
enum class GettingStartedTarget { CONCEPTS, ADD_PROVIDER, DAILY_LIMITS, AGENTS }

/** Getting started (Tier B), or null once the owner said done. */
@Immutable
data class GettingStartedUi(val steps: List<GettingStartedStepUi>)

/** Where a phone attention item leads; null: nothing in the app fixes it. */
sealed interface PhoneTarget {
    data object Settings : PhoneTarget
    data object DailyLimits : PhoneTarget
    data class Objective(val id: TaskId) : PhoneTarget
}

/** One thing about the phone (not the registry) the owner should know. */
@Immutable
data class PhoneItemUi(val id: String, val tone: StatusTone, val title: String, val detail: String, val target: PhoneTarget?)

/** Safe start is on, and why. */
@Immutable
data class SafeStartUi(val reason: String, val leaving: Boolean = false)

/** What the Dashboard's Phase 6 parts show. */
@Immutable
data class DashboardExtrasUiState(
    val isLoading: Boolean = true,
    val today: TodayTileUi? = null,
    val gettingStarted: GettingStartedUi? = null,
    val phone: List<PhoneItemUi> = emptyList(),
    val safeStart: SafeStartUi? = null,
)

/**
 * The Dashboard's Phase 6 parts (WP10a): the Today tile (answers, tokens and cost today and this month, cost only when
 * every used model has a price), Getting started (Tier B; `onboardingCompleted` gets its reader, D47), the phone
 * attention items (background check, storage, objectives paused by today's limit, work waiting to try again) and the
 * safe-start banner (Tier B). One view model for the four slots `DashboardScreen` places; each slot composable reads it.
 *
 * Today's numbers are [today]'s, counted exactly as the gateway's limiter counts them (`DailyUse.of`). [reports],
 * [prices] and [zone] add the month and the models without a price (the Dashboard route passes them; scratchpad
 * hand-off WP10a); without them the tile shows today's numbers only, with a cost whenever a used model has a price.
 *
 * @param today today's use and the limits, read only.
 * @param settings the owner's writer: Getting started's "Done" sets `onboardingCompleted`.
 * @param tasks objective rows only (`observeObjectiveRows`), for "Paused: today's limit is reached".
 * @param safeStart whether safe start is on, and "Back to normal".
 * @param waits work waiting to try again (`Orchestrator.timedWaits`), for "Trying again at 10:42".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardExtrasViewModel(
    private val today: TodayUsage,
    private val settings: SettingsRepository,
    private val directory: RegistryDirectory,
    private val tasks: TaskRepository,
    private val backgroundHealth: BackgroundHealth,
    private val storage: StorageWatch,
    private val safeStart: SafeStartControl,
    private val clock: Clock,
    private val reports: UsageReports? = null,
    private val prices: ModelPrices? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val waits: TimedWaits = TimedWaits.None,
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {

    private val leaving = MutableStateFlow(false)

    /** Today's and this month's rows by model, when the reports are known: for the models without a price and the month. */
    private val detail: Flow<UsageDetail?> =
        if (reports == null || prices == null) {
            flowOf(null)
        } else {
            today.observe().map { UsageRange.day(clock.now(), zone()) to UsageRange.month(clock.now(), zone()) }
                .distinctUntilChanged()
                .flatMapLatest { (day, month) ->
                    combine(reports.observeByModel(day), reports.observeByModel(month), prices.observeAll()) { dayRows, monthRows, priced ->
                        UsageDetail(dayRows, monthRows, priced.associateBy { it.modelId })
                    }
                }
        }

    private data class UsageDetail(val day: List<ModelUsage>, val month: List<ModelUsage>, val prices: Map<ModelId, ModelPrice>)

    private data class Phone(val background: BackgroundState, val storage: StorageLevel, val nextWait: Long?, val objectives: List<ObjectiveRow>)

    private val phone: Flow<Phone> =
        combine(backgroundHealth.state, storage.level, waits.nextDueAt, tasks.observeObjectiveRows(OBJECTIVES_READ)) { background, level, wait, rows ->
            Phone(background, level, wait, rows)
        }

    val uiState: StateFlow<DashboardExtrasUiState> =
        combine(today.observe(), detail, settings.settings, directory.observe(), phone) { use, usage, current, snapshot, phoneNow ->
            Built(use, usage, current.onboardingCompleted, snapshot, phoneNow)
        }.combine(combine(safeStart.state, leaving) { a, b -> a to b }) { built, (safe, isLeaving) ->
            DashboardExtrasUiState(
                isLoading = false,
                today = todayTile(built.use, built.usage),
                gettingStarted = if (built.onboarded) null else gettingStarted(built.snapshot, built.use.limits, built.use.currency),
                phone = phoneItems(built.phone),
                safeStart = if (safe.on) SafeStartUi(safe.reason?.ownerText ?: "The app did not start normally.", isLeaving) else null,
            )
        }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardExtrasUiState())

    private data class Built(val use: TodayUse, val usage: UsageDetail?, val onboarded: Boolean, val snapshot: RegistrySnapshot, val phone: Phone)

    /** Getting started's "Done": it does not come back (D47: `onboardingCompleted`). */
    fun finishGettingStarted() {
        viewModelScope.launch {
            try {
                settings.update { it.copy(onboardingCompleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The card stays; the owner can tap again.
            }
        }
    }

    /** Safe start's "Back to normal": interrupted work may resume by itself again at the next start, if the settings allow it. */
    fun leaveSafeStart() {
        if (leaving.value) return
        leaving.value = true
        viewModelScope.launch {
            try {
                safeStart.leave()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Safe start stays on; the button can be tapped again.
            } finally {
                leaving.value = false
            }
        }
    }

    private fun todayTile(use: TodayUse, usage: UsageDetail?): TodayTileUi {
        val counted = use.use
        val currency = use.currency
        val used = counted.calls > 0 || counted.tokens > 0
        val unpriced = usage?.day.orEmpty()
            .filter { it.locality.leavesDevice && countedAnswers(it.totals) + it.totals.totalTokens > 0 && it.modelId !in usage!!.prices }
        val maxSpend = use.limits.maxSpendMicros
        val limitWords = maxSpend?.let { moneyWords(it, currency) }
        // The money the limiter counts toward the money limit: the models with a price (D3A-1). Never "No cost": with no
        // price at all the cost is not known, which is not the same as free.
        val known = !used || counted.priced
        val cost = if (known) moneyWords(counted.spendMicros, currency) else COST_NOT_KNOWN
        val costReached = maxSpend != null && counted.spendMicros >= maxSpend && known && used
        val costCaption = when {
            !known -> "no price entered"
            limitWords != null -> "of $limitWords" + if (costReached) ", reached" else ""
            else -> null
        }
        val names = listWords(unpriced.map { it.modelLabel })
        val have = if (unpriced.size == 1) "has" else "have"
        val costNote = when {
            !used -> null
            !counted.priced && unpriced.isNotEmpty() ->
                "$names $have no price yet, so " + (limitWords?.let { "nothing counts toward your $it limit." } ?: "no cost can be shown.")
            !counted.priced ->
                "No model used today has a price yet, so " + (limitWords?.let { "nothing counts toward your $it limit." } ?: "no cost can be shown.")
            unpriced.isNotEmpty() -> "$names $have no price yet, so ${if (unpriced.size == 1) "its" else "their"} tokens are not in the cost."
            // Without the reports it cannot be told whether every model used today has a price: say what the cost covers.
            usage == null -> PRICED_ONLY
            else -> null
        }
        val phoneAnswers = usage?.day.orEmpty().filter { !it.locality.leavesDevice }.sumOf { countedAnswers(it.totals) }
        return TodayTileUi(
            used = used,
            answers = counted.calls,
            tokens = counted.tokens,
            limits = use.limits,
            cost = cost,
            costCaption = costCaption,
            costReached = costReached,
            costNote = costNote,
            unpriced = unpriced.map { UnpricedModelUi(it.modelId, it.modelLabel) },
            month = usage?.let { monthLine(it, currency) },
            reached = use.limits.reachedBy(counted.calls, counted.tokens, counted.spendMicros)?.let { use.limits.sentence(it, currency) },
            currency = currency,
            phoneNote = phoneAnswers.takeIf { it > 0 }?.let { phoneWords(it, alsoOthers = used) },
        )
    }

    /**
     * This month as the limits count it (models off this phone), with the estimated cost of the models with a price, as
     * Activity › Usage and cost shows this month.
     */
    private fun monthLine(usage: UsageDetail, currency: String): String? {
        val month = DailyUse.of(usage.month, usage.prices)
        if (month.calls == 0L && month.tokens == 0L) return null
        val everyPriced = usage.month.filter { it.locality.leavesDevice && it.totals.totalTokens > 0 }.all { it.modelId in usage.prices }
        return buildString {
            append("This month: ").append(answers(month.calls)).append(" · ").append(tokens(month.tokens))
            if (month.priced) {
                append(" · ").append(moneyWords(month.spendMicros, currency))
                if (!everyPriced) append(" for the models with a price")
            }
        }
    }

    private fun gettingStarted(snapshot: RegistrySnapshot, limits: DailyLimits, currency: String): GettingStartedUi {
        val hasProvider = snapshot.providers.any { !it.isSimulator }
        val limitWords = buildList {
            limits.maxCalls?.let { add("${formatCount(it.toLong())} answers") }
            limits.maxTokens?.let { add("${formatCount(it)} tokens") }
            limits.maxSpendMicros?.let { add(moneyWords(it, currency)) }
        }
        return GettingStartedUi(
            listOf(
                GettingStartedStepUi(
                    title = "What the words mean",
                    text = "A model is the AI that reads and writes text. A provider runs models for you: a company's " +
                        "service, or a program on your own computer. An agent is a helper in this app with a role, " +
                        "instructions and a model you give it.",
                    action = "Learn more",
                    done = false,
                    target = GettingStartedTarget.CONCEPTS,
                ),
                GettingStartedStepUi(
                    title = "Add your key",
                    text = "Choose your provider, such as xAI for Grok, and paste the key from its website. To pay less: " +
                        "\"Custom OpenAI-compatible server\" also works with Groq, OpenRouter, Gemini's OpenAI-compatible " +
                        "address, and a model on your own computer (Ollama or LM Studio), which is free and private. " +
                        "Some have free plans with limits; check the provider's page, terms change.",
                    action = if (hasProvider) "Add another" else "Add a provider",
                    done = hasProvider,
                    target = GettingStartedTarget.ADD_PROVIDER,
                ),
                GettingStartedStepUi(
                    title = "Set a daily limit",
                    text = if (limitWords.isEmpty()) {
                        "Choose how much the agents may use each day. $LIMIT_PAUSES_OBJECTIVES"
                    } else {
                        "On from the start: ${listWords(limitWords)} a day, whichever comes first. $LIMIT_PAUSES_OBJECTIVES"
                    },
                    action = "Daily limits",
                    done = false,
                    target = GettingStartedTarget.DAILY_LIMITS,
                ),
                GettingStartedStepUi(
                    title = "Try the simulator",
                    text = "Try chats and objectives offline, for free: the built-in simulator is not a real model, and " +
                        "every reply says so. Use it for the agents that have no model yet, or choose it on an agent's page.",
                    action = "Open agents",
                    done = false,
                    target = GettingStartedTarget.AGENTS,
                ),
            ),
        )
    }

    private fun phoneItems(phone: Phone): List<PhoneItemUi> = buildList {
        val background = phone.background
        if (!background.notificationsAllowed) {
            add(
                PhoneItemUi(
                    "phone-notifications", StatusTone.ATTENTION, "Notifications are off",
                    "You will not hear when work ends or when an agent waits for you. Turn them on in Settings › Working in the background.",
                    PhoneTarget.Settings,
                ),
            )
        }
        val limits = buildList {
            if (background.batteryOptimised == true) add("battery saving applies to the app")
            if (background.backgroundRestricted == true) add("its background use is restricted")
            if (background.standbyWarning) add("Android rarely lets it run")
        }
        if (limits.isNotEmpty()) {
            add(
                PhoneItemUi(
                    "phone-background", StatusTone.ATTENTION, "Android may stop agents working in the background",
                    "${listWords(limits).replaceFirstChar { it.uppercase() }}. Fix it with one tap in Settings › Working in the background.",
                    PhoneTarget.Settings,
                ),
            )
        }
        when (phone.storage) {
            StorageLevel.CRITICAL -> add(
                PhoneItemUi(
                    "phone-storage", StatusTone.UNAVAILABLE, "The phone is almost full",
                    "Running objectives were paused and new ones cannot start. Free some room on the phone, then resume them.",
                    null,
                ),
            )
            StorageLevel.LOW -> add(
                PhoneItemUi(
                    "phone-storage", StatusTone.ATTENTION, "The phone is running out of room",
                    "Less than 500 MB is free. Below 100 MB, running objectives pause.",
                    null,
                ),
            )
            StorageLevel.OK, StorageLevel.UNKNOWN -> Unit
        }
        val paused = phone.objectives.filter { it.status == TaskStatus.PAUSED && it.statusDetailShort == DailyLimits.PAUSED_TEXT }
        paused.take(MAX_PAUSED_SHOWN).forEach { row ->
            add(
                PhoneItemUi(
                    "phone-paused:${row.rootId.value}", StatusTone.ATTENTION, "Paused by today's limit: ${row.title}",
                    RESUME_AFTER_LIMIT,
                    PhoneTarget.Objective(row.rootId),
                ),
            )
        }
        if (paused.size > MAX_PAUSED_SHOWN) {
            val more = paused.size - MAX_PAUSED_SHOWN
            add(
                PhoneItemUi(
                    "phone-paused-more", StatusTone.ATTENTION,
                    if (more == 1) "1 more objective paused by today's limit" else "$more more objectives paused by today's limit",
                    "Raise the limit, or wait until midnight; then open each one in Tasks and tap Resume.", PhoneTarget.DailyLimits,
                ),
            )
        }
        phone.nextWait?.let { at ->
            add(
                PhoneItemUi(
                    "phone-waiting", StatusTone.WAITING, "Work tries again at ${formatTimeOfDay(at, zone())}",
                    "A provider asked the app to wait, or did not answer. The work goes on by itself; nothing for you to do.",
                    null,
                ),
            )
        }
    }

    companion object {
        /** Said under a cost that may leave out models without a price. */
        const val PRICED_ONLY: String = "The cost counts only the models you entered a price for."

        /** The cost tile when no model used today has a price: not known, which is not the same as free (D3A-1). */
        const val COST_NOT_KNOWN: String = "Not known"

        /** "Models on this phone also answered 6 times today: free, and not counted." */
        internal fun phoneWords(answers: Long, alsoOthers: Boolean): String =
            if (alsoOthers) "Models on this phone also answered ${times(answers)} today: free, and not counted."
            else "Only models on this phone answered today (${times(answers)}): free, and not counted."

        /** The newest objectives looked at for "paused by today's limit". */
        const val OBJECTIVES_READ: Int = 50

        private const val MAX_PAUSED_SHOWN = 3
    }
}
