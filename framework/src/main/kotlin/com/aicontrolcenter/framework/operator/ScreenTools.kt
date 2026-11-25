package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.FrontState
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The screen tools (`screen.*`) over the accessibility service
 * ([com.aicontrolcenter.core.operator.ScreenOperator]). Every call is constrained to the app in
 * front ([CapabilityConstraint.App]).
 *
 * Before anything of a screen is looked at, the owner's switches are read fresh (phone
 * operation, screen control and, for the tools that act, local-only mode), then the app in front
 * is checked to be authorised and not protected; a refused call's summary holds no screen text.
 *
 * Tap, type, scroll, Back and Home act on the app, which may send data off the phone, so
 * local-only mode refuses them. Every app asks before each of them by default
 * ([ScreenActionPolicy.ASK_EVERY_ACTION]): such a call is CONSEQUENTIAL. For an app the owner set
 * to ask only before consequential actions, [ConsequentialControls] decides from the control, its
 * row and its window. A consequential call's preview shows the window's title and every text in it
 * ([ScreenPreview]), read without starting a session; before it acts, the window must still show
 * exactly that (its digest is compared, which covers the whole window: also the elements a long
 * list leaves out and the end of a cut text) and the control must be the same one. A window too
 * large to fingerprint whole is not put to the owner at all: the call fails with the reason. Right after the owner
 * answered (inside this app), the call first waits, outside every time limit, for the app to be in
 * front again ([com.aicontrolcenter.core.tools.PreparedAction.changedSinceOwnerAnswered]).
 *
 * Back and Home name the launcher in [ActionAssessment.leadsTo] (Back can leave the app too), so
 * they are refused unless the owner authorised the launcher as well.
 */
internal object ScreenTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(
        ScreenRead(d), ScreenWaitFor(d), ScreenScreenshot(d), ScreenTap(d), ScreenType(d), ScreenScroll(d), ScreenBack(d), ScreenHome(d),
    )

    /** The app in front, checked before anything of it is read. */
    class Front(val packageName: String, val app: OperatorApp?, val protected: Boolean) {
        val label: String get() = app?.label ?: packageName
        val constraint get() = CapabilityConstraint.App(packageName)

        /** Every action in the app asks the owner (the default), rather than only consequential ones. */
        val asksEveryTime: Boolean get() = (app?.policy ?: ScreenActionPolicy.ASK_EVERY_ACTION) == ScreenActionPolicy.ASK_EVERY_ACTION

        /** Why this app is not operated, or null. */
        val refusal: String?
            get() = when {
                protected -> "$packageName is a protected app (system settings, installers, this app); agents never operate it."
                app == null -> "$packageName is not an app the owner authorised for screen control."
                else -> null
            }

        fun name(): String = "${q(label)} ($packageName)"
    }

    /**
     * The app in front, after the owner's switches, read now: nothing of a screen is looked at
     * for a call the broker would refuse on them. [acting]: the tool acts on the app, which may
     * send data off the phone, so local-only mode refuses it.
     */
    suspend fun front(d: OperatorToolDependencies, title: String, acting: Boolean, agent: AgentId? = null): Front {
        val settings = d.settings()
        if (!settings.operatorEnabled) throw ToolCallRejected("Phone operation is switched off in Settings, so \"$title\" cannot be used.")
        if (!settings.accessibilityOperatorEnabled) throw ToolCallRejected("Screen control is switched off in Settings.")
        if (acting && settings.localOnlyMode) throw ToolCallRejected(localOnly(title))
        val state = knownState(d)
        if (state != ScreenServiceState.READY) throw ToolCallRejected(unavailable(state))
        if (d.screen.stopped.value) throw ToolCallRejected(STOPPED)
        val window = when (val front = frontAfterLaunch(d, agent)) {
            is FrontState.App -> front.window
            // D6: a window Android does not let this app read (its permission screens), said as such.
            FrontState.Hidden -> throw ToolCallRejected(HIDDEN_WINDOW)
            FrontState.None -> throw ToolCallRejected("No app window is in front, so there is nothing to operate.")
        }
        val protected = d.protectedPackages().isProtected(window.packageName)
        return Front(window.packageName, if (protected) null else d.authorisedApps.get(window.packageName), protected)
    }

    /**
     * What is in front (D5): when [agent] opened an app less than [OperatorLimits.launchRecentMs]
     * ago ([RecentLaunches]), first a bounded wait, every [OperatorLimits.screenPollMs] for up to
     * [OperatorLimits.screenOpenWaitMs], for that app (or, for a link or a web page, any app other
     * than this one) to come to the front, so a screen call right after opening an app is not
     * refused while Android still brings it up. Stop ends the wait.
     */
    private suspend fun frontAfterLaunch(d: OperatorToolDependencies, agent: AgentId?): FrontState {
        var state = d.screen.frontState()
        val launch = agent?.let { d.launches.latest(it, d.limits.launchRecentMs, d.clock.now()) } ?: return state
        if (arrived(d, state, launch)) return state
        withTimeoutOrNull(d.limits.screenOpenWaitMs) {
            while (true) {
                delay(d.limits.screenPollMs)
                if (d.screen.stopped.value) break
                state = d.screen.frontState()
                if (arrived(d, state, launch)) break
            }
        }
        return state
    }

    /** Whether the app [launch] opened is in front: that package, or for a link any app other than this one. */
    private fun arrived(d: OperatorToolDependencies, state: FrontState, launch: RecentLaunches.Launch): Boolean {
        val front = (state as? FrontState.App)?.window?.packageName ?: return false
        return if (launch.packageName != null) front == launch.packageName else front != d.ownPackage
    }

    /**
     * The service's state, waiting a moment when it is not known yet: Android's settings are first read in the
     * background when the app starts ([ScreenServiceState.CHECKING]).
     */
    private suspend fun knownState(d: OperatorToolDependencies): ScreenServiceState {
        val flow = d.screen.state
        if (flow.value != ScreenServiceState.CHECKING) return flow.value
        return withTimeoutOrNull(d.limits.screenSettleMs) { flow.first { it != ScreenServiceState.CHECKING } } ?: flow.value
    }

    fun localOnly(title: String): String =
        "Local-only mode is on, and \"$title\" may send data off this phone through the app it operates, so it was not run."

    /** Why the app in front is no longer [packageName], or null. */
    suspend fun frontChange(d: OperatorToolDependencies, packageName: String): String? {
        val now = d.screen.activeWindow()?.packageName
        return if (now == packageName) null else "the app in front changed (it is now ${now ?: "unknown"})"
    }

    /** For a call assessed as not consequential: why it must now ask, because the owner set its app to ask before every action. */
    suspend fun policyChange(d: OperatorToolDependencies, packageName: String): String? {
        val app = d.authorisedApps.get(packageName) ?: return "the app is no longer authorised for screen control"
        return if (app.policy == ScreenActionPolicy.ASK_EVERY_ACTION) "the owner set the app to ask before every action" else null
    }

    /** Why [packageName]'s window no longer shows what was approved ([ScreenPreview.digest]), or null. */
    suspend fun windowChange(d: OperatorToolDependencies, packageName: String, digest: String): String? =
        when (val r = d.screen.inspect(packageName)) {
            is ScreenResult.Ok -> if (ScreenPreview.digest(r.value) == digest) null else "what the window shows changed"
            else -> "the window could not be read again (${problem(r, "reading it").removeSuffix(".")})"
        }

    /**
     * [check], right after the owner answered a request for a call on [front]'s app. The owner
     * answered inside this app, which then steps aside: first wait, for at most
     * [OperatorLimits.screenReturnMs], until [front]'s app is in front again, then until [check]
     * holds, for at most [OperatorLimits.screenSettleMs] (a window can take a moment to show again
     * what it showed). Time the owner spends, so it counts against no time limit
     * ([WaitClock.pausedWhile]). Stop ends the wait.
     */
    suspend fun afterOwnerAnswered(d: OperatorToolDependencies, front: Front, check: suspend () -> String?): String? = WaitClock.pausedWhile {
        when (withTimeoutOrNull(d.limits.screenReturnMs) { awaitFront(d, front.packageName) }) {
            null -> {
                val now = d.screen.activeWindow()?.packageName ?: "unknown"
                return@pausedWhile "the app in front changed (it is now $now; ${q(front.label)} did not come back within " +
                    "${d.limits.screenReturnMs / 1000} seconds of the owner's answer)"
            }
            false -> return@pausedWhile "the owner pressed Stop on screen control"
            true -> Unit
        }
        var reason = check()
        if (reason != null) {
            withTimeoutOrNull(d.limits.screenSettleMs) {
                while (reason != null) {
                    delay(d.limits.screenPollMs)
                    reason = check()
                }
            }
        }
        reason
    }

    /** True once [packageName] is in front; false when the owner pressed Stop first. */
    private suspend fun awaitFront(d: OperatorToolDependencies, packageName: String): Boolean {
        while (true) {
            if (d.screen.stopped.value) return false
            if (d.screen.activeWindow()?.packageName == packageName) return true
            delay(d.limits.screenPollMs)
        }
    }

    /** Why an action that needs the owner's approval is not taken in a window that could not be read to its end. */
    fun tooLarge(front: Front): String =
        "The window of ${front.name()} holds more than can be read in full (over ${ScreenOperator.DIGEST_MAX_ELEMENTS} elements, or " +
            "nested too deep), so the owner could not be shown everything an approval would cover, and nothing was done. " +
            "Do not retry on this screen: go to a simpler screen of the app first, or leave this step to the owner."

    /** Why an action in [front]'s app asks when the app asks before every action; null for an app that asks only before consequential ones. */
    fun everyTime(front: Front): String? = if (front.asksEveryTime) "${front.name()} is set to ask you before every action" else null

    /** Where data may go when an agent acts in [front]'s app. */
    fun dataLine(front: Front): String = "May send data off this phone through ${front.name()}."

    fun unavailable(state: ScreenServiceState): String = "Screen control is not available: " + when (state) {
        ScreenServiceState.CHECKING -> "this app is still looking up the accessibility service in Android's settings; try again in a moment."
        ScreenServiceState.NOT_ENABLED -> "the owner has not switched on this app's accessibility service."
        ScreenServiceState.RESTRICTED -> "Android blocks the accessibility service until the owner allows restricted settings for this app."
        ScreenServiceState.ADVANCED_PROTECTION -> "Android's Advanced Protection is on, which turns services like this off."
        ScreenServiceState.STARTING -> "the accessibility service is starting; try again in a moment."
        ScreenServiceState.READY -> "it is ready."
    }

    const val STOPPED = "The owner pressed Stop on screen control. Screen actions are refused until the owner resumes."

    /** D6: the window in front is one Android does not let this app read. */
    const val HIDDEN_WINDOW = "The window in front is one Android does not let this app read, such as its permission screens. Nothing was read."

    fun problem(r: ScreenResult<*>, what: String): String = when (r) {
        is ScreenResult.Ok -> "$what: done."
        is ScreenResult.Unavailable -> "$what: ${unavailable(r.state)}"
        ScreenResult.Stopped -> "$what: $STOPPED"
        is ScreenResult.WrongWindow -> "$what: ${r.expected} is no longer in front${r.actual?.let { " ($it is)" } ?: ""}. Read the screen again."
        is ScreenResult.Protected -> "$what: ${r.packageName} is a protected app; agents never operate it."
        is ScreenResult.NotAuthorised -> "$what: ${r.packageName} is not an app the owner authorised for screen control."
        is ScreenResult.NotFound -> "$what: ${r.what} was not found."
        is ScreenResult.Ambiguous -> "$what: ${r.count} elements have that text; name it by its number from screen_read."
        ScreenResult.Stale -> "$what: the element is no longer on screen as it was read. Read the screen again."
        ScreenResult.Sensitive -> "$what: that is a password or sensitive field, which agents never touch."
        is ScreenResult.NotSupported -> "$what: not possible here: ${r.reason}"
        is ScreenResult.Failed -> "$what: Android refused: ${r.reason}"
    }

    fun JsonObjectBuilder.targetProps(what: String) {
        intProp("snapshot", "The snapshot number from screen_read, with \"element\".", 1, Long.MAX_VALUE)
        intProp("element", "The number of the $what in that snapshot.", 1, 10_000)
        stringProp("exact_text", "Instead of a number: the $what's exact visible text or description, when exactly one element has it.")
    }

    /** The element an agent named; [optional] allows naming none (null). */
    fun target(args: Args, optional: Boolean = false): ScreenTarget? {
        val byNumber = args.has("element") || args.has("snapshot")
        val byText = args.has("exact_text")
        if (byNumber && byText) throw ToolCallRejected("Name the element either by \"snapshot\" and \"element\", or by \"exact_text\", not both.")
        if (!byNumber && !byText) {
            if (optional) return null
            throw ToolCallRejected("Name the element by \"snapshot\" and \"element\" from screen_read, or by \"exact_text\".")
        }
        if (byText) return ScreenTarget.ExactText(args.text("exact_text", 500)!!)
        if (!args.has("snapshot") || !args.has("element")) throw ToolCallRejected("\"snapshot\" and \"element\" go together; read the screen with screen_read first.")
        return ScreenTarget.Numbered(args.long("snapshot", 0, 1..Long.MAX_VALUE), args.int("element", 0, 1..10_000))
    }

    /** One element as the model sees it: screen text is data, shown on one line with hidden characters made visible. */
    fun line(e: ScreenElement): String {
        val parts = ArrayList<String>()
        parts += "${e.number}. ${e.role}"
        e.text?.takeIf { it.isNotBlank() }?.let { parts += clip(it) }
        e.description?.takeIf { it.isNotBlank() && it != e.text }?.let { parts += "description ${clip(it)}" }
        e.hint?.takeIf { it.isNotBlank() }?.let { parts += "hint ${clip(it)}" }
        e.label?.takeIf { it.isNotBlank() && it != e.text && it != e.description }?.let { parts += "label ${clip(it)}" }
        e.resourceId?.let { parts += "id ${it.substringAfter(":id/")}" }
        e.context?.takeIf { it.isNotBlank() }?.let { parts += "in ${clip(it)}" }
        val flags = listOfNotNull(
            "clickable".takeIf { e.clickable }, "editable".takeIf { e.editable }, "scrollable".takeIf { e.scrollable },
            (if (e.checked) "checked" else "unchecked").takeIf { e.checkable }, "disabled".takeIf { !e.enabled },
            "focused".takeIf { e.focused }, "selected".takeIf { e.selected },
        )
        if (flags.isNotEmpty()) parts += "[${flags.joinToString(", ")}]"
        return parts.joinToString(" ")
    }

    private fun clip(s: String): String = q(if (s.length > 200) s.take(200) + "…" else s)

    fun format(s: ScreenSnapshot, front: Front): String = buildString {
        append("Screen of ${front.name()}")
        s.windowTitle?.let { append(", window ${q(it)}") }
        append(", snapshot ${s.id}. Name an element with \"snapshot\": ${s.id} and its \"element\" number.\n")
        if (s.elements.isEmpty()) append("(No readable elements.)\n")
        s.elements.forEach { append(line(it)).append('\n') }
        if (s.withheld > 0) append("(${OperatorText.count(s.withheld, "password or sensitive field")} not shown; agents never use them.)\n")
        if (s.truncated) append("(More elements were left out; scroll to reach them.)\n")
    }.trimEnd()

    /** A refused call's assessment: the app only, no screen text. */
    fun refused(tool: OperatorTool, front: Front, verb: String, failing: PreparedCall): ActionAssessment =
        ActionAssessment(tool.spec.risk, "$verb on the screen of ${front.packageName}", constraint = front.constraint, prepared = failing)
}

/** screen.read */
internal class ScreenRead(private val d: OperatorToolDependencies) : OperatorTool(
    "screen.read", "Read the screen",
    "Reads the app in front as a numbered list of its controls and texts (role, text, description, state). Works only in apps the owner " +
        "authorised; password and sensitive fields are never shown. Use the snapshot and element numbers with the other screen tools.",
    objectSchema { intProp("max_elements", "At most this many elements. Default 150.", 1, 300) },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val max = Args(arguments).int("max_elements", 150, 1..d.limits.maxScreenElements)
        val front = ScreenTools.front(d, spec.title, acting = false, agent = context.agent.id)
        front.refusal?.let { return ScreenTools.refused(this, front, "Read", failing(it)) }
        return assessment(
            "Read the screen of ${front.name()}",
            prepared(afterOwner = { ScreenTools.afterOwnerAnswered(d, front) { ScreenTools.frontChange(d, front.packageName) } }) { ctx ->
                when (val r = d.screen.read(ctx.agent.name, front.packageName, max)) {
                    is ScreenResult.Ok -> ToolOutcome.success(ScreenTools.format(r.value, front), snapshotJson(r.value))
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Reading the screen"))
                }
            },
            constraint = front.constraint,
        )
    }

    private fun snapshotJson(s: ScreenSnapshot) = buildJsonObject {
        put("snapshot", s.id)
        put("package", s.packageName)
        put("elements", s.elements.size)
        put("withheld", s.withheld)
        put("truncated", s.truncated)
    }
}

/** screen.wait_for */
internal class ScreenWaitFor(private val d: OperatorToolDependencies) : OperatorTool(
    "screen.wait_for", "Wait for text on screen",
    "Waits until a control or text containing the given text appears in the app in front, for at most \"timeout_ms\".",
    objectSchema("text") {
        stringProp("text", "The text to wait for.")
        intProp("timeout_ms", "How long to wait at most. Default 10000.", 100, 30_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val text = args.text("text", 500)!!
        val timeout = args.long("timeout_ms", 10_000, 100..d.limits.maxWaitMs)
        val front = ScreenTools.front(d, spec.title, acting = false, agent = context.agent.id)
        front.refusal?.let { return ScreenTools.refused(this, front, "Wait", failing(it)) }
        return assessment(
            "Wait up to ${timeout / 1000.0} s for ${q(text)} to appear in ${front.name()}",
            prepared(afterOwner = { ScreenTools.afterOwnerAnswered(d, front) { ScreenTools.frontChange(d, front.packageName) } }) { ctx ->
                when (val r = d.screen.waitFor(ctx.agent.name, front.packageName, text, timeout)) {
                    is ScreenResult.Ok -> ToolOutcome.success("It appeared: ${ScreenTools.line(r.value)} (snapshot ${r.value.snapshotId}).")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Waiting for ${q(text)}"))
                }
            },
            constraint = front.constraint,
        )
    }
}

/** screen.screenshot */
internal class ScreenScreenshot(private val d: OperatorToolDependencies) : OperatorTool(
    "screen.screenshot", "Take a screenshot",
    "Saves a PNG of the window of the app in front to the workspace (Android 11 or later; authorised apps only). Never replaces a file.",
    objectSchema { stringProp("to", "Where to save it in the workspace, ending in .png. Default: \"Screenshots/<app>-<time>.png\".") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val front = ScreenTools.front(d, spec.title, acting = false, agent = context.agent.id)
        val to = if (args.has("to")) args.entryPath("to") else RelativePath.of("Screenshots/${front.packageName}-${STAMP.format(Instant.ofEpochMilli(d.clock.now()))}.png")
        if (!to.name.lowercase(Locale.ROOT).endsWith(".png")) throw ToolCallRejected("\"to\" must end in .png.")
        front.refusal?.let { return ScreenTools.refused(this, front, "Take a screenshot", failing(it)) }
        val summary = "Save a screenshot of ${front.name()} to the workspace as ${q(to.value)}"
        val target = WorkspaceStates.of(d.workspace, to)
        if (target.exists) return assessment(summary, failing("${q(to.value)} already exists; screenshots never replace a file."), constraint = front.constraint)
        val check: suspend () -> String? = { ScreenTools.frontChange(d, front.packageName) ?: target.changeTo(WorkspaceStates.of(d.workspace, to), q(to.value)) }
        return assessment(
            summary,
            prepared(check = check, afterOwner = { ScreenTools.afterOwnerAnswered(d, front, check) }) { ctx ->
                // Through the workspace, so its size limits, lock and change signal apply to the picture.
                val saved = d.workspace.receive(to, d.limits.maxCopyBytes) { file, _ -> DocResult.Ok(d.screen.screenshot(ctx.agent.name, front.packageName, file)) }
                when (val r = (saved as? DocResult.Ok)?.value) {
                    null -> ToolOutcome.failure(OperatorText.problem(saved, "Saving to ${q(to.value)}"))
                    is ScreenResult.Ok -> ToolOutcome.success("Saved a screenshot of ${front.label} as ${q(to.value)} (${r.value.widthPx}×${r.value.heightPx}, ${OperatorText.size(r.value.sizeBytes)}).")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Taking the screenshot"))
                }
            },
            listOf("App: ${front.name()}", "Only that app's window is captured."),
            constraint = front.constraint,
        )
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
    }
}

/**
 * What the tools that act on an app (tap, type, scroll, Back, Home) share: the owner's switches
 * with local-only mode, the per-app policy, the whole-window preview of a consequential call and
 * the re-checks before it acts.
 */
internal abstract class ScreenActingTool(
    protected val d: OperatorToolDependencies,
    id: String,
    title: String,
    description: String,
    parameters: JsonObject,
) : OperatorTool(id, title, description, parameters) {
    /** The app in front for an action; local-only mode refuses it. */
    protected suspend fun front(context: ToolContext): ScreenTools.Front = ScreenTools.front(d, spec.title, acting = true, agent = context.agent.id)

    /** The window, read without starting a session; null with an assessment that fails with why it could not be read. */
    protected suspend fun inspect(front: ScreenTools.Front, summary: String, leadsTo: String? = null): Pair<ScreenSnapshot?, ActionAssessment?> =
        when (val r = d.screen.inspect(front.packageName)) {
            is ScreenResult.Ok -> r.value to null
            else -> null to assessment(
                summary, failing(ScreenTools.problem(r, "Reading the window to show the owner")), constraint = front.constraint, leadsTo = leadsTo,
            )
        }

    /**
     * The assessment of an action in [front]'s app: CONSEQUENTIAL when [reason] is not null, else
     * MODIFYING.
     * - The details name the app (and [element], the control acted on, with its row), then
     *   [extra], where data may go, and why the owner is asked.
     * - A consequential action shows the whole [window] (read now when not given): title, fields
     *   and every text, and pins its [ScreenPreview.digest].
     * - Before acting: the app is still in front; a MODIFYING call's app was not set meanwhile to
     *   ask before every action; [pinned] (the control, the launcher) still holds; a consequential
     *   call's window shows exactly what was approved.
     * - Right after the owner answered, those checks wait for the app to be in front again
     *   ([ScreenTools.afterOwnerAnswered]).
     */
    protected suspend fun acting(
        front: ScreenTools.Front,
        summary: String,
        element: ScreenElement?,
        reason: String?,
        extra: List<String> = emptyList(),
        window: ScreenSnapshot? = null,
        leadsTo: String? = null,
        pinned: suspend () -> String? = { null },
        act: suspend (ToolContext) -> ToolOutcome,
    ): ActionAssessment {
        val consequential = reason != null
        val shown = when {
            !consequential -> null
            window != null -> window
            else -> inspect(front, summary, leadsTo).let { (read, failed) -> read ?: return failed!! }
        }
        // Nothing vouches for what such a window holds beyond the list, so the owner is not asked to approve it.
        if (shown != null && shown.windowDigest == null) {
            return assessment(summary, failing(ScreenTools.tooLarge(front)), constraint = front.constraint, leadsTo = leadsTo)
        }
        val digest = shown?.let(ScreenPreview::digest)
        val where = if (element != null) ScreenPreview.details(front.label, element, shown, consequential) else ScreenPreview.windowDetails(front.label, front.packageName, shown)
        val details = where + extra + ScreenTools.dataLine(front) + listOfNotNull(reason?.let { "Asked because $it." })
        val check: suspend () -> String? = {
            ScreenTools.frontChange(d, front.packageName)
                ?: (if (!consequential) ScreenTools.policyChange(d, front.packageName) else null)
                ?: pinned()
                ?: digest?.let { ScreenTools.windowChange(d, front.packageName, it) }
        }
        return assessment(
            summary,
            prepared(check = check, afterOwner = { ScreenTools.afterOwnerAnswered(d, front, check) }, action = act),
            details,
            risk = if (consequential) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
            constraint = front.constraint,
            leadsTo = leadsTo,
        )
    }
}

/** What tap and type share: find the control, classify it, and re-check it before acting. */
internal abstract class ScreenControlTool(
    d: OperatorToolDependencies,
    id: String,
    title: String,
    description: String,
    parameters: JsonObject,
) : ScreenActingTool(d, id, title, description, parameters) {
    /** What was found for one call. */
    protected class Found(val front: ScreenTools.Front, val target: ScreenTarget, val element: ScreenElement)

    /** The element, or an assessment that refuses (app not usable) or fails (element not found). */
    protected suspend fun find(args: Args, verb: String, context: ToolContext): Pair<Found?, ActionAssessment?> {
        val target = ScreenTools.target(args)!!
        val front = front(context)
        front.refusal?.let { return null to ScreenTools.refused(this, front, verb, failing(it)) }
        return when (val r = d.screen.find(front.packageName, target)) {
            is ScreenResult.Ok -> Found(front, target, r.value) to null
            else -> null to assessment("$verb in ${front.name()}", failing(ScreenTools.problem(r, verb)), constraint = front.constraint)
        }
    }

    /** Why [found]'s control is no longer the one assessed, or null. */
    protected suspend fun controlChange(found: Found): String? {
        val again = d.screen.find(found.front.packageName, found.target)
        return if (again !is ScreenResult.Ok || !again.value.sameControlAs(found.element)) "the control is no longer on screen as it was" else null
    }
}

/** screen.tap */
internal class ScreenTap(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.tap", "Tap on screen",
    "Taps a control in the app in front, named by its number from screen_read or its exact text. The owner approves each tap, " +
        "unless the owner set that app to ask only before buying, sending, deleting and similar.",
    objectSchema { with(ScreenTools) { targetProps("control") } },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val (found, early) = find(Args(arguments), "Tap", context)
        if (found == null) return early!!
        val front = found.front
        val element = found.element
        val summary = "Tap ${ScreenPreview.nameOf(element)} in ${front.name()}"
        var window: ScreenSnapshot? = null
        val reason = ScreenTools.everyTime(front) ?: ConsequentialControls.consequentialReason(element) ?: run {
            // "OK", "Yes", "Continue": what they answer is in the window around them.
            if (!ConsequentialControls.isPositive(element)) return@run null
            window = inspect(front, summary).let { (read, failed) -> read ?: return failed!! }
            ConsequentialControls.consequentialReason(element, window)
        }
        return acting(front, summary, element, reason, window = window, pinned = { controlChange(found) }) { ctx ->
            when (val r = d.screen.tap(ctx.agent.name, element)) {
                is ScreenResult.Ok -> ToolOutcome.success("Tapped ${ScreenPreview.nameOf(element)}. Read the screen again to see what changed.")
                else -> ToolOutcome.failure(ScreenTools.problem(r, "Tapping ${ScreenPreview.nameOf(element)}"))
            }
        }
    }
}

/** screen.type */
internal class ScreenType(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.type", "Type on screen",
    "Replaces the text of an editable field in the app in front. With \"submit\": true it then presses the keyboard's action key " +
        "(send, go, search). The owner approves each time, unless the owner set that app to ask only before consequential actions; " +
        "pressing the action key and typing a code always ask.",
    objectSchema("text") {
        with(ScreenTools) { targetProps("field") }
        stringProp("text", "The text to put in the field.")
        boolProp("submit", "True to press the keyboard's action key afterwards. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val text = args.string("text") ?: throw ToolCallRejected("\"text\" is required.")
        if (text.length > 10_000) throw ToolCallRejected("\"text\" is longer than 10000 characters.")
        val submit = args.bool("submit", false)
        val (found, early) = find(args, "Type", context)
        if (found == null) return early!!
        val front = found.front
        val element = found.element
        val summary = "Type ${q(text)} into ${ScreenPreview.nameOf(element)} in ${front.name()}" +
            if (submit) ", then press the keyboard's action key (it may send)" else ""
        if (!element.editable) return assessment(summary, failing("${ScreenPreview.nameOf(element)} does not take text."), constraint = front.constraint)
        val reason = ScreenTools.everyTime(front) ?: ConsequentialControls.typingReason(element)
            ?: if (submit) "pressing the keyboard's action key may send what is typed" else null
        val extra = listOfNotNull("Text to type: ${q(text)}", "Then: press the keyboard's action key.".takeIf { submit })
        return acting(front, summary, element, reason, extra, pinned = { controlChange(found) }) { ctx ->
            when (val r = d.screen.type(ctx.agent.name, element, text, submit)) {
                is ScreenResult.Ok -> ToolOutcome.success("Typed into ${ScreenPreview.nameOf(element)}${if (submit) " and pressed the action key" else ""}.")
                else -> ToolOutcome.failure(ScreenTools.problem(r, "Typing into ${ScreenPreview.nameOf(element)}"))
            }
        }
    }
}

/** screen.scroll */
internal class ScreenScroll(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.scroll", "Scroll the screen",
    "Scrolls the app in front, or one scrollable element of it. \"down\" shows what is further down. The owner approves each " +
        "time, unless the owner set that app to ask only before consequential actions (a slider labelled \"Slide to pay\" still asks).",
    objectSchema("direction") {
        stringProp("direction", "Which way to bring content into view.", listOf("up", "down", "left", "right"))
        with(ScreenTools) { targetProps("scrollable element (optional)") }
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val raw = args.requireString("direction")
        val direction = ScrollDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw ToolCallRejected("\"direction\" must be up, down, left or right.")
        val word = direction.name.lowercase(Locale.ROOT)
        if (ScreenTools.target(args, optional = true) != null) {
            val (found, early) = find(args, "Scroll", context)
            if (found == null) return early!!
            val element = found.element
            // The same rule as a tap, on what is scrolled: a slider "Slide to pay" asks.
            val reason = ScreenTools.everyTime(found.front) ?: ConsequentialControls.consequentialReason(element)
            return acting(found.front, "Scroll ${ScreenPreview.nameOf(element)} $word in ${found.front.name()}", element, reason, pinned = { controlChange(found) }) { ctx ->
                scroll(ctx, found.front, direction, element)
            }
        }
        val front = front(context)
        front.refusal?.let { return ScreenTools.refused(this, front, "Scroll", failing(it)) }
        val summary = "Scroll $word in ${front.name()}"
        var window: ScreenSnapshot? = null
        val reason = ScreenTools.everyTime(front) ?: run {
            // Without a target the first scrollable element moves: classify that one.
            window = inspect(front, summary).let { (read, failed) -> read ?: return failed!! }
            window?.elements?.firstOrNull { it.scrollable }?.let { ConsequentialControls.consequentialReason(it, window) }
        }
        return acting(front, summary, null, reason, window = window) { ctx -> scroll(ctx, front, direction, null) }
    }

    private suspend fun scroll(ctx: ToolContext, front: ScreenTools.Front, direction: ScrollDirection, element: ScreenElement?): ToolOutcome =
        when (val r = d.screen.scroll(ctx.agent.name, front.packageName, direction, element)) {
            is ScreenResult.Ok -> ToolOutcome.success("Scrolled ${direction.name.lowercase(Locale.ROOT)}. Read the screen again to see what is shown.")
            else -> ToolOutcome.failure(ScreenTools.problem(r, "Scrolling"))
        }
}

/** What Back and Home share: both may leave the app, so both name the launcher and need it authorised too. */
internal abstract class ScreenGlobalTool(
    d: OperatorToolDependencies,
    id: String,
    title: String,
    description: String,
    private val action: GlobalAction,
) : ScreenActingTool(d, id, title, description, objectSchema { }) {
    protected abstract val verb: String

    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val front = front(context)
        val launcher = d.screen.launcherPackage() ?: throw ToolCallRejected("The home screen app is not known, and $verb can lead there, so it is not pressed.")
        front.refusal?.let { return ScreenTools.refused(this, front, verb, failing(it)).copy(leadsTo = launcher) }
        return acting(
            front, summary(front, launcher), null, ScreenTools.everyTime(front), extra(front, launcher), leadsTo = launcher,
            pinned = { d.screen.launcherPackage().let { if (it == launcher) null else "the home screen app changed" } },
        ) { ctx ->
            when (val r = d.screen.globalAction(ctx.agent.name, front.packageName, action)) {
                is ScreenResult.Ok -> ToolOutcome.success(done(front, launcher, d.screen.activeWindow()?.packageName))
                else -> ToolOutcome.failure(ScreenTools.problem(r, "Pressing $verb"))
            }
        }
    }

    protected abstract fun summary(front: ScreenTools.Front, launcher: String): String

    protected abstract fun extra(front: ScreenTools.Front, launcher: String): List<String>

    protected abstract fun done(front: ScreenTools.Front, launcher: String, now: String?): String
}

/** screen.back */
internal class ScreenBack(d: OperatorToolDependencies) : ScreenGlobalTool(
    d, "screen.back", "Press Back",
    "Presses Android's Back button while an authorised app is in front. Back can leave the app, so it is allowed only when the owner " +
        "also authorised the home screen app; the owner approves each time unless that app asks only before consequential actions.",
    GlobalAction.BACK,
) {
    override val verb = "Back"

    override fun summary(front: ScreenTools.Front, launcher: String) = "Press Back in ${front.name()}"

    override fun extra(front: ScreenTools.Front, launcher: String) =
        listOf("Back may close ${q(front.label)} and show the home screen ($launcher) or the app used before it.")

    override fun done(front: ScreenTools.Front, launcher: String, now: String?) = when (now) {
        front.packageName -> "Pressed Back; ${q(front.label)} is still in front. Read the screen to see where it led."
        null -> "Pressed Back. Read the screen to see where it led."
        else -> "Pressed Back; it left ${q(front.label)}, and $now is in front now. Screen tools work only in apps the owner authorised."
    }
}

/** screen.home */
internal class ScreenHome(d: OperatorToolDependencies) : ScreenGlobalTool(
    d, "screen.home", "Press Home",
    "Presses Android's Home button while an authorised app is in front. Allowed only when the owner also authorised the home screen " +
        "app; the owner approves each time unless that app asks only before consequential actions.",
    GlobalAction.HOME,
) {
    override val verb = "Home"

    override fun summary(front: ScreenTools.Front, launcher: String) = "Press Home in ${front.name()}, which shows the home screen ($launcher)"

    override fun extra(front: ScreenTools.Front, launcher: String) = listOf("To: the home screen app $launcher")

    override fun done(front: ScreenTools.Front, launcher: String, now: String?) = "Pressed Home; the home screen ($launcher) is in front."
}
