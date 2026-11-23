package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
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
 * front ([CapabilityConstraint.App]). Before anything of a screen is looked at, the tool checks
 * that the app is authorised and not protected; a refused call's summary holds no screen text.
 * Taps and typing are CONSEQUENTIAL when [ConsequentialControls.consequentialReason] finds the
 * control consequential, or when typing presses the keyboard's action key; their preview then
 * also shows what the fields on screen hold ([ScreenPreview.details]). HOME names the launcher in
 * [ActionAssessment.leadsTo], so it is refused unless the launcher is authorised too.
 */
internal object ScreenTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(
        ScreenRead(d), ScreenWaitFor(d), ScreenScreenshot(d), ScreenTap(d), ScreenType(d), ScreenScroll(d), ScreenBack(d), ScreenHome(d),
    )

    /** The app in front, checked before anything of it is read. */
    class Front(val packageName: String, val app: OperatorApp?, val protected: Boolean) {
        val label: String get() = app?.label ?: packageName
        val constraint get() = CapabilityConstraint.App(packageName)

        /** Why this app is not operated, or null. */
        val refusal: String?
            get() = when {
                protected -> "$packageName is a protected app (system settings, installers, this app); agents never operate it."
                app == null -> "$packageName is not an app the owner authorised for screen control."
                else -> null
            }

        fun name(): String = "${q(label)} ($packageName)"
    }

    suspend fun front(d: OperatorToolDependencies): Front {
        val state = d.screen.state.value
        if (state != ScreenServiceState.READY) throw ToolCallRejected(unavailable(state))
        if (d.screen.stopped.value) throw ToolCallRejected(STOPPED)
        val window = d.screen.activeWindow() ?: throw ToolCallRejected("No app window is in front, so there is nothing to operate.")
        val protected = d.protectedPackages().isProtected(window.packageName)
        return Front(window.packageName, if (protected) null else d.authorisedApps.get(window.packageName), protected)
    }

    /** Why the app in front is no longer [packageName], or null. */
    suspend fun frontChange(d: OperatorToolDependencies, packageName: String): String? {
        val now = d.screen.activeWindow()?.packageName
        return if (now == packageName) null else "the app in front changed (it is now ${now ?: "unknown"})"
    }

    fun unavailable(state: ScreenServiceState): String = "Screen control is not available: " + when (state) {
        ScreenServiceState.NOT_ENABLED -> "the owner has not switched on this app's accessibility service."
        ScreenServiceState.RESTRICTED -> "Android blocks the accessibility service until the owner allows restricted settings for this app."
        ScreenServiceState.ADVANCED_PROTECTION -> "Android's Advanced Protection is on, which turns services like this off."
        ScreenServiceState.STARTING -> "the accessibility service is starting; try again in a moment."
        ScreenServiceState.READY -> "it is ready."
    }

    const val STOPPED = "The owner pressed Stop on screen control. Screen actions are refused until the owner resumes."

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

    /** The text fields of a snapshot, by what names them, so a change between approval and action is seen. */
    fun fields(s: ScreenSnapshot?): List<Pair<String, String?>> =
        s?.elements?.filter { it.editable }?.map { (it.resourceId ?: it.hint ?: it.description ?: "field") to it.text }.orEmpty()

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
        val front = ScreenTools.front(d)
        front.refusal?.let { return ScreenTools.refused(this, front, "Read", failing(it)) }
        return assessment(
            "Read the screen of ${front.name()}",
            prepared { ctx ->
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
        val front = ScreenTools.front(d)
        front.refusal?.let { return ScreenTools.refused(this, front, "Wait", failing(it)) }
        return assessment(
            "Wait up to ${timeout / 1000.0} s for ${q(text)} to appear in ${front.name()}",
            prepared { ctx ->
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
        val front = ScreenTools.front(d)
        val to = if (args.has("to")) args.entryPath("to") else RelativePath.of("Screenshots/${front.packageName}-${STAMP.format(Instant.ofEpochMilli(d.clock.now()))}.png")
        if (!to.name.lowercase(Locale.ROOT).endsWith(".png")) throw ToolCallRejected("\"to\" must end in .png.")
        front.refusal?.let { return ScreenTools.refused(this, front, "Take a screenshot", failing(it)) }
        val summary = "Save a screenshot of ${front.name()} to the workspace as ${q(to.value)}"
        val target = WorkspaceStates.of(d.workspace, to)
        if (target.exists) return assessment(summary, failing("${q(to.value)} already exists; screenshots never replace a file."), constraint = front.constraint)
        return assessment(
            summary,
            prepared(check = { ScreenTools.frontChange(d, front.packageName) ?: target.changeTo(WorkspaceStates.of(d.workspace, to), q(to.value)) }) { ctx ->
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

/** What tap and type share: find the control, classify it, and re-check it before acting. */
internal abstract class ScreenControlTool(
    protected val d: OperatorToolDependencies,
    id: String,
    title: String,
    description: String,
    parameters: JsonObject,
) : OperatorTool(id, title, description, parameters) {
    /** What was found for one call. */
    protected class Found(val front: ScreenTools.Front, val target: ScreenTarget, val element: ScreenElement)

    /** The element, or an assessment that refuses (app not usable) or fails (element not found). */
    protected suspend fun find(args: Args, verb: String): Pair<Found?, ActionAssessment?> {
        val target = ScreenTools.target(args)!!
        val front = ScreenTools.front(d)
        front.refusal?.let { return null to ScreenTools.refused(this, front, verb, failing(it)) }
        return when (val r = d.screen.find(front.packageName, target)) {
            is ScreenResult.Ok -> Found(front, target, r.value) to null
            else -> null to assessment("$verb in ${front.name()}", failing(ScreenTools.problem(r, verb)), constraint = front.constraint)
        }
    }

    /** The current fields, for a consequential preview; null when the screen cannot be read. */
    protected suspend fun snapshot(context: ToolContext, front: ScreenTools.Front): ScreenSnapshot? =
        (d.screen.read(context.agent.name, front.packageName) as? ScreenResult.Ok)?.value

    /** Re-checks, after approval, the app, the control and (when consequential) the fields shown to the owner. */
    protected suspend fun changed(found: Found, context: ToolContext, shownFields: List<Pair<String, String?>>?): String? {
        ScreenTools.frontChange(d, found.front.packageName)?.let { return it }
        val again = d.screen.find(found.front.packageName, found.target)
        if (again !is ScreenResult.Ok || !again.value.sameControlAs(found.element)) return "the control is no longer on screen as it was"
        if (shownFields != null && ScreenTools.fields(snapshot(context, found.front)) != shownFields) return "the text in the fields on screen changed"
        return null
    }
}

/** screen.tap */
internal class ScreenTap(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.tap", "Tap on screen",
    "Taps a control in the app in front, named by its number from screen_read or its exact text. Buying, sending, deleting and " +
        "similar controls, and controls without a label, need the owner's approval each time.",
    objectSchema { with(ScreenTools) { targetProps("control") } },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val (found, early) = find(Args(arguments), "Tap")
        if (found == null) return early!!
        val element = found.element
        val reason = ConsequentialControls.consequentialReason(element)
        val snapshot = if (reason != null) snapshot(context, found.front) else null
        val shown = if (reason != null) ScreenTools.fields(snapshot) else null
        val details = ScreenPreview.details(found.front.label, element, snapshot, consequential = reason != null) +
            listOfNotNull(reason?.let { "Asked because $it." })
        return assessment(
            "Tap ${ScreenPreview.nameOf(element)} in ${found.front.name()}",
            prepared(check = { changed(found, context, shown) }) { ctx ->
                when (val r = d.screen.tap(ctx.agent.name, element)) {
                    is ScreenResult.Ok -> ToolOutcome.success("Tapped ${ScreenPreview.nameOf(element)}. Read the screen again to see what changed.")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Tapping ${ScreenPreview.nameOf(element)}"))
                }
            },
            details,
            risk = if (reason != null) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
            constraint = found.front.constraint,
        )
    }
}

/** screen.type */
internal class ScreenType(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.type", "Type on screen",
    "Replaces the text of an editable field in the app in front. With \"submit\": true it then presses the keyboard's action key " +
        "(send, go, search), which needs the owner's approval each time.",
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
        val (found, early) = find(args, "Type")
        if (found == null) return early!!
        val element = found.element
        val summary = "Type ${q(text)} into ${ScreenPreview.nameOf(element)} in ${found.front.name()}" +
            if (submit) ", then press the keyboard's action key (it may send)" else ""
        if (!element.editable) return assessment(summary, failing("${ScreenPreview.nameOf(element)} does not take text."), constraint = found.front.constraint)
        val reason = ConsequentialControls.consequentialReason(element) ?: if (submit) "pressing the keyboard's action key may send what is typed" else null
        val snapshot = if (reason != null) snapshot(context, found.front) else null
        val shown = if (reason != null) ScreenTools.fields(snapshot) else null
        val details = ScreenPreview.details(found.front.label, element, snapshot, consequential = reason != null) +
            listOfNotNull("Text to type: ${q(text)}", "Then: press the keyboard's action key.".takeIf { submit }, reason?.let { "Asked because $it." })
        return assessment(
            summary,
            prepared(check = { changed(found, context, shown) }) { ctx ->
                when (val r = d.screen.type(ctx.agent.name, element, text, submit)) {
                    is ScreenResult.Ok -> ToolOutcome.success("Typed into ${ScreenPreview.nameOf(element)}${if (submit) " and pressed the action key" else ""}.")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Typing into ${ScreenPreview.nameOf(element)}"))
                }
            },
            details,
            risk = if (reason != null) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
            constraint = found.front.constraint,
        )
    }
}

/** screen.scroll */
internal class ScreenScroll(d: OperatorToolDependencies) : ScreenControlTool(
    d, "screen.scroll", "Scroll the screen",
    "Scrolls the app in front, or one scrollable element of it. \"down\" shows what is further down.",
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
        val front: ScreenTools.Front
        var element: ScreenElement? = null
        var target: ScreenTarget? = null
        if (ScreenTools.target(args, optional = true) != null) {
            val (found, early) = find(args, "Scroll")
            if (found == null) return early!!
            front = found.front
            element = found.element
            target = found.target
        } else {
            front = ScreenTools.front(d)
            front.refusal?.let { return ScreenTools.refused(this, front, "Scroll", failing(it)) }
        }
        return assessment(
            "Scroll ${element?.let { ScreenPreview.nameOf(it) + " " } ?: ""}$word in ${front.name()}",
            prepared(check = {
                ScreenTools.frontChange(d, front.packageName) ?: if (element != null && target != null) {
                    val again = d.screen.find(front.packageName, target)
                    if (again !is ScreenResult.Ok || !again.value.sameControlAs(element)) "the element is no longer on screen as it was" else null
                } else null
            }) { ctx ->
                when (val r = d.screen.scroll(ctx.agent.name, front.packageName, direction, element)) {
                    is ScreenResult.Ok -> ToolOutcome.success("Scrolled $word. Read the screen again to see what is shown.")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Scrolling"))
                }
            },
            constraint = front.constraint,
        )
    }
}

/** screen.back */
internal class ScreenBack(private val d: OperatorToolDependencies) : OperatorTool(
    "screen.back", "Press Back",
    "Presses Android's Back button while an authorised app is in front. It may leave that app; screen tools then refuse apps that are not authorised.",
    objectSchema { },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val front = ScreenTools.front(d)
        front.refusal?.let { return ScreenTools.refused(this, front, "Press Back", failing(it)) }
        return assessment(
            "Press Back in ${front.name()}",
            prepared(check = { ScreenTools.frontChange(d, front.packageName) }) { ctx ->
                when (val r = d.screen.globalAction(ctx.agent.name, front.packageName, GlobalAction.BACK)) {
                    is ScreenResult.Ok -> ToolOutcome.success("Pressed Back. Read the screen to see where it led.")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Pressing Back"))
                }
            },
            listOf("App: ${front.name()}", "Back may close this app and show another one."),
            constraint = front.constraint,
        )
    }
}

/** screen.home */
internal class ScreenHome(private val d: OperatorToolDependencies) : OperatorTool(
    "screen.home", "Press Home",
    "Presses Android's Home button while an authorised app is in front. Allowed only when the owner also authorised the home screen app.",
    objectSchema { },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val front = ScreenTools.front(d)
        val launcher = d.screen.launcherPackage() ?: throw ToolCallRejected("The home screen app is not known, so Home is not pressed.")
        front.refusal?.let { return ScreenTools.refused(this, front, "Press Home", failing(it)).copy(leadsTo = launcher) }
        return assessment(
            "Press Home in ${front.name()}, which shows the home screen ($launcher)",
            prepared(check = {
                ScreenTools.frontChange(d, front.packageName)
                    ?: (d.screen.launcherPackage()).let { if (it == launcher) null else "the home screen app changed" }
            }) { ctx ->
                when (val r = d.screen.globalAction(ctx.agent.name, front.packageName, GlobalAction.HOME)) {
                    is ScreenResult.Ok -> ToolOutcome.success("Pressed Home; the home screen ($launcher) is in front.")
                    else -> ToolOutcome.failure(ScreenTools.problem(r, "Pressing Home"))
                }
            },
            listOf("From: ${front.name()}", "To: the home screen app $launcher"),
            constraint = front.constraint,
            leadsTo = launcher,
        )
    }
}
