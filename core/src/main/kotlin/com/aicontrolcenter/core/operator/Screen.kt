package com.aicontrolcenter.core.operator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Whether the accessibility service can be used. */
enum class ScreenServiceState {
    /**
     * Not known yet: Android's settings are read in the background when the app starts, never on
     * the thread of whoever looks at the state first. It changes as soon as that read is done.
     */
    CHECKING,

    /** Not switched on in Android's accessibility settings. */
    NOT_ENABLED,

    /** Not switched on, and Android's "restricted settings" block applies (an app installed from a file): the owner must first allow restricted settings. Best effort: Android does not report it directly. */
    RESTRICTED,

    /** Advanced Protection is on, which disables services like this one. */
    ADVANCED_PROTECTION,

    /** Switched on in settings, but the service has not connected (yet). */
    STARTING,

    /** Connected and usable. */
    READY,
}

/** The window in front: its app, and its title only when that app is authorised (a title can hold private content). */
data class ActiveWindow(val packageName: String, val title: String?)

/** A rectangle on screen, in pixels. */
@Serializable
data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * One control or text on screen, as the model sees it. Password fields and views marked
 * sensitive are never included.
 */
@Serializable
data class ScreenElement(
    /** The snapshot it was read in ([ScreenSnapshot.id]). */
    val snapshotId: Long,
    /** Position in that snapshot, from 1; what the model refers to. */
    val number: Int,
    val packageName: String,
    /** Short widget kind: "Button", "EditText", "TextView", "CheckBox", "ImageButton"... */
    val role: String,
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    /** Android view id such as "com.example:id/send", when the app sets one. */
    val resourceId: String? = null,
    /**
     * Everything else that labels it, merged: the text of its descendants (a clickable row's
     * child TextViews), state description, tooltip and custom action labels. Classified with
     * [ConsequentialControls.matchedWord].
     */
    val label: String? = null,
    /** Where it is: the nearest labelled row or ancestor text, then the window title ("Row: Invoice 42 · Inbox"). Tells apart repeated controls. */
    val context: String? = null,
    /** A stable node identity from `:device`: `getUniqueId()` on Android 13+, else the child-index path from the window root. */
    val nodeKey: String? = null,
    val bounds: ScreenBounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    /**
     * How many characters the reader left out of [text], [description] and [hint] together: each
     * is cut at [ScreenOperator.MAX_TEXT_CHARS]. The uncut texts are still part of
     * [ScreenSnapshot.windowDigest].
     */
    val cutChars: Int = 0,
) {
    /**
     * Whether [other] is the same control, read again: same app, role, text, description, view
     * id, label, context and node key. Bounds are left out (scrolling moves controls). A
     * gateway acts only when exactly one control on the current screen is the same as the one
     * read; several or none is [ScreenResult.Stale], never a guess by position.
     */
    fun sameControlAs(other: ScreenElement): Boolean =
        packageName == other.packageName && role == other.role && text == other.text &&
            description == other.description && resourceId == other.resourceId && label == other.label &&
            context == other.context && nodeKey == other.nodeKey
}

/** The active window's elements at one moment. */
data class ScreenSnapshot(
    /** Increases with every read; elements carry it so stale references are detected. */
    val id: Long,
    val packageName: String,
    val windowTitle: String?,
    val elements: List<ScreenElement>,
    /** How many password or sensitive fields were left out, so the model knows something is there. */
    val withheld: Int,
    /** More elements than the limit; the rest were left out. */
    val truncated: Boolean,
    val capturedAt: Long,
    /**
     * A fingerprint of the whole window as it was read for [ScreenOperator.inspect]
     * ([WindowDigest]): every element in reading order, also those [elements] leaves out, each
     * with its texts uncut. Null from a read that does not make one ([ScreenOperator.read]), and
     * when the window could not be walked to its end (more than
     * [ScreenOperator.DIGEST_MAX_ELEMENTS] elements, or nested too deep): then nothing vouches for
     * what [elements] leaves out, and no action that needs the owner's approval is taken on it.
     */
    val windowDigest: String? = null,
    /** How many elements of the window [elements] leaves out while [windowDigest] still covers them; 0 without one. */
    val unlisted: Int = 0,
)

/**
 * The fingerprint of a whole window, fed by whatever reads the screen while it walks it: every
 * element in reading order with its texts uncut, and a mark for each password or sensitive field
 * (never its content). `:device`'s operator and the testkit's fake both build
 * [ScreenSnapshot.windowDigest] with this class, so it means the same from either: it changes
 * when any text, checked, selected or enabled state anywhere in the window changes, listed or not.
 * Positions and focus are left out, as in [ScreenPreview.digest]. SHA-256, hex.
 */
class WindowDigest {
    private val md = java.security.MessageDigest.getInstance("SHA-256")

    /** Elements and withheld fields fed so far. */
    var count: Int = 0
        private set

    /** One element; [state] is whatever else the reader knows that says what the control shows (a state description, a merged label). */
    fun element(
        role: String,
        text: String?,
        description: String?,
        hint: String?,
        resourceId: String?,
        state: String?,
        checkable: Boolean,
        checked: Boolean,
        selected: Boolean,
        enabled: Boolean,
    ) {
        md.field(role); md.field(text); md.field(description); md.field(hint); md.field(resourceId); md.field(state)
        md.field(if (checkable) checked.toString() else "")
        md.field(selected.toString())
        md.field(enabled.toString())
        count++
    }

    /** A password or sensitive field: that it is there, nothing of what it holds. */
    fun withheld() {
        md.field("\u0000withheld")
        count++
    }

    /** The fingerprint of everything fed. Call once, when the walk reached the end of the window. */
    fun finish(): String = md.digest().hex()
}

/** One length-prefixed field of a fingerprint, so neighbouring values cannot run into each other. */
private fun java.security.MessageDigest.field(value: String?) {
    val bytes = (value ?: "\u0000null").toByteArray(Charsets.UTF_8)
    update(bytes.size.toString().toByteArray(Charsets.UTF_8))
    update(':'.code.toByte())
    update(bytes)
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** How the model names an element. */
sealed interface ScreenTarget {
    /**
     * By number in the snapshot [snapshotId] (the one the model read; other agents' reads do not
     * renumber it). The gateway looks the element up in that stored snapshot, then re-finds it
     * on the current screen with [ScreenElement.sameControlAs]. An unknown or evicted snapshot
     * is [ScreenResult.Stale].
     */
    data class Numbered(val snapshotId: Long, val number: Int) : ScreenTarget

    /** The single visible element whose text or description is exactly [text]. */
    data class ExactText(val text: String) : ScreenTarget
}

/** Which way content moves into view: DOWN shows what is further down. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** The only global actions offered. Recents, notifications and quick settings are not. */
enum class GlobalAction { BACK, HOME }

/** A screenshot written to the workspace. */
data class ScreenshotInfo(val widthPx: Int, val heightPx: Int, val sizeBytes: Long)

/** The outcome of a screen operation. Expected refusals are values, not exceptions. */
sealed interface ScreenResult<out T> {
    /** Done; [value] is the result. */
    data class Ok<T>(val value: T) : ScreenResult<T>

    /** The service cannot be used; [state] says why. */
    data class Unavailable(val state: ScreenServiceState) : ScreenResult<Nothing>

    /** The owner pressed Stop; nothing is done until [ScreenOperator.resume]. */
    data object Stopped : ScreenResult<Nothing>

    /** The window in front is not the expected app's. */
    data class WrongWindow(val expected: String, val actual: String?) : ScreenResult<Nothing>

    /** The app is never operated ([ProtectedPackages]). */
    data class Protected(val packageName: String) : ScreenResult<Nothing>

    /** The owner has not authorised this app for screen operation ([OperatorAppRepository]); nothing of it was read. */
    data class NotAuthorised(val packageName: String) : ScreenResult<Nothing>

    /** No such element, or [waitFor][ScreenOperator.waitFor] timed out; [what] names what was looked for. */
    data class NotFound(val what: String) : ScreenResult<Nothing>

    /** An [ScreenTarget.ExactText] matched [count] elements. */
    data class Ambiguous(val count: Int) : ScreenResult<Nothing>

    /** The element is no longer on screen as it was read. */
    data object Stale : ScreenResult<Nothing>

    /** The target is a password field or a view marked sensitive. */
    data object Sensitive : ScreenResult<Nothing>

    /** Not possible here (screenshots before Android 11, secure windows, an element that cannot take text). */
    data class NotSupported(val reason: String) : ScreenResult<Nothing>

    /** Android refused the action or the service failed. */
    data class Failed(val reason: String) : ScreenResult<Nothing>
}

/**
 * Reads and drives the screen through this app's accessibility service (`:device`). Whatever
 * the caller asks, an implementation checks, in this order, on every call:
 * 1. [state] is READY, else [ScreenResult.Unavailable];
 * 2. not stopped ([ScreenControl]), else [ScreenResult.Stopped];
 * 3. the package is not protected ([ProtectedPackages]), else [ScreenResult.Protected];
 * 4. the package is authorised now ([OperatorAppRepository], re-read per call, signing
 *    certificate re-verified), else [ScreenResult.NotAuthorised]; nothing of it is read;
 * 5. the window in front is that package's, of type `TYPE_APPLICATION`, with no keyguard
 *    showing, else [ScreenResult.WrongWindow].
 *
 * Then, for an element from an earlier read, it re-reads the screen and acts only if exactly one
 * control is [ScreenElement.sameControlAs] it ([ScreenResult.Stale] otherwise). Password fields
 * and views marked sensitive are never read or acted on ([ScreenResult.Sensitive]). Actions are
 * node actions (`ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_*`, IME action) on the node
 * re-found inside the expected window, never `dispatchGesture` at coordinates, which would land on
 * whatever window is on top.
 *
 * The "<agent> is operating <app> · Stop" overlay is shown from an agent's first call until
 * [endSession] or [SESSION_IDLE_MS] after its last call, so Stop stays reachable between calls;
 * the foreground notification carries a Stop action too. Calls are serialised; each returns
 * [ScreenResult.Stopped] promptly once Stop is pressed, including one already in progress.
 *
 * Grants and risk are the broker's; tools also check authorisation before [find], and the broker
 * checks it again.
 */
interface ScreenOperator {
    val state: StateFlow<ScreenServiceState>

    /** The owner's Stop, read-only here; [ScreenControl] changes it. */
    val stopped: StateFlow<Boolean>

    /** The app in front, without reading its content ([ActiveWindow.title] only for an authorised app); null when unknown. */
    suspend fun activeWindow(): ActiveWindow?

    /** The home screen app, so a HOME action can be checked like any other app; null when unknown. */
    suspend fun launcherPackage(): String?

    /** A numbered list of the active window's elements, if it is [packageName]'s. The snapshot is kept so [ScreenTarget.Numbered] can refer to it. */
    suspend fun read(agentName: String, packageName: String, maxElements: Int = 200): ScreenResult<ScreenSnapshot>

    /** Finds an element in [packageName]'s window without acting. Side-effect free: for assessing a call. */
    suspend fun find(packageName: String, target: ScreenTarget): ScreenResult<ScreenElement>

    /**
     * Reads [packageName]'s window like [read], under the same checks, but side-effect free: no
     * session or indicator starts and the snapshot is not kept, so its elements cannot be named
     * by number ([ScreenSnapshot.id] is 0). For assessing a call and re-checking it before it
     * acts: the approval preview shows, and the re-check compares, the whole window. At most
     * [maxElements] elements are listed; [ScreenSnapshot.truncated] says when more were left out.
     * The walk goes on past that limit only to fingerprint the rest
     * ([ScreenSnapshot.windowDigest], up to [DIGEST_MAX_ELEMENTS] elements), so what is not listed
     * is pinned all the same; a window larger than that has no fingerprint.
     */
    suspend fun inspect(packageName: String, maxElements: Int = INSPECT_MAX_ELEMENTS): ScreenResult<ScreenSnapshot>

    suspend fun tap(agentName: String, element: ScreenElement): ScreenResult<Unit>

    /**
     * Replaces the text of an editable [element]. With [submit], then presses the field's
     * keyboard action, which may send: tools classify that as consequential.
     */
    suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit>

    /** Scrolls [element], or the first scrollable element of [packageName]'s window when null. */
    suspend fun scroll(agentName: String, packageName: String, direction: ScrollDirection, element: ScreenElement? = null): ScreenResult<Unit>

    /**
     * Performs [action] while [fromPackage]'s window is in front. BACK may land in any app; every
     * later call on an app that is not authorised is refused by the checks above.
     */
    suspend fun globalAction(agentName: String, fromPackage: String, action: GlobalAction): ScreenResult<Unit>

    /** Waits until an element whose text or description contains [text] appears in [packageName]'s window. [ScreenResult.NotFound] after [timeoutMs]. */
    suspend fun waitFor(agentName: String, packageName: String, text: String, timeoutMs: Long): ScreenResult<ScreenElement>

    /**
     * Writes a PNG of [packageName]'s window, and nothing else, to [target]: on Android 14+
     * `takeScreenshotOfWindow`; on 11–13 the display shot cropped to the window's bounds, and
     * [ScreenResult.NotSupported] if any other non-overlay window intersects it or the
     * notification shade is open; before 11, NotSupported. Never overwrites an existing file.
     */
    suspend fun screenshot(agentName: String, packageName: String, target: WorkspaceFile): ScreenResult<ScreenshotInfo>

    /** Hides [agentName]'s operating indicator; the framework calls it when that agent's run or task ends. */
    fun endSession(agentName: String)

    /**
     * Keeps [agentName]'s session in [packageName] open, with its banner ("<agent> waits for your
     * answer to act in <app> · Stop"), from the moment one of its screen actions waits for the
     * owner until [releaseSession] with the same [holdId] (Phase 6, K7, D4, rule 12). Holds are
     * counted per id (the request's id), so one run's [endSession] never ends a session another
     * call still holds, and the idle time-out does not end it either. Stop ends every hold. A
     * second hold with the same id is the same hold. The default does nothing.
     */
    fun holdSession(holdId: String, agentName: String, packageName: String) {}

    /** Ends the hold [holdId]; the session then ends as usual (idle time-out or [endSession]). Unknown ids are ignored. */
    fun releaseSession(holdId: String) {}

    /**
     * What is in front, as far as this app may know (Phase 6, K7, D6): [FrontState.App] with the
     * window, [FrontState.Hidden] when a window is in front that Android does not let this app
     * read (its permission screens, a secure window), [FrontState.None] when nothing is known.
     * The default builds it from [activeWindow] and never answers Hidden.
     */
    suspend fun frontState(): FrontState = activeWindow()?.let { FrontState.App(it) } ?: FrontState.None

    /** Timing of the operating indicator. */
    companion object {
        /** The indicator stays this long after an agent's last screen call. */
        const val SESSION_IDLE_MS: Long = 30_000

        /** The most elements [inspect] returns by default: more than any read, so a preview covers the whole window. */
        const val INSPECT_MAX_ELEMENTS: Int = 1_000

        /** The most elements of one window [inspect] fingerprints; a larger window gets no [ScreenSnapshot.windowDigest]. */
        const val DIGEST_MAX_ELEMENTS: Int = 10_000

        /** The most characters of one text, description or hint a reader keeps on an element; the rest is counted in [ScreenElement.cutChars]. */
        const val MAX_TEXT_CHARS: Int = 500
    }
}

/**
 * The owner's Stop for screen operation (the overlay, the notification action and Tools ›
 * Screen control). Owner-only: no tool, broker or agent-facing code holds it.
 */
interface ScreenControl {
    /** Survives app restarts (the implementation persists it). */
    val stopped: StateFlow<Boolean>

    /** Immediate: every screen call is refused, and the one in progress ends, until [resume]. */
    fun stop()

    fun resume()

    /**
     * True when Android switched screen control off by itself: the service had been ready, and
     * after the app was stopped (or force-stopped) it is no longer enabled (Phase 6, K7, D7). The
     * owner then turns it on again in Android's accessibility settings. False by default.
     */
    val switchedOffByAndroid: StateFlow<Boolean> get() = NEVER_SWITCHED_OFF
}

/** What [ScreenOperator.frontState] reports. */
sealed interface FrontState {
    /** Nothing is known: the service is not ready, or no window is in front. */
    data object None : FrontState

    /**
     * A window is in front that Android does not let this app read, such as its permission
     * screens: "The window in front is one Android does not let this app read. Nothing was read."
     */
    data object Hidden : FrontState

    /** [window] is in front. */
    data class App(val window: ActiveWindow) : FrontState
}

/** A flow that is false for good: read-only, so nothing can switch it. */
private val NEVER_SWITCHED_OFF: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * How often screen actions in one authorised app ask the owner. Stored per app
 * ([OperatorApp.policy]); a word list cannot recognise every consequential control, so the
 * default asks every time.
 */
enum class ScreenActionPolicy {
    /**
     * Every tap, typing, scroll, Back and Home in the app needs the owner's approval, each time,
     * with the window's text in the preview. The default for every authorised app.
     */
    ASK_EVERY_ACTION,

    /**
     * Only actions [ConsequentialControls] recognises as buying, sending, deleting, consenting
     * and similar need the owner's approval; other actions run under the agent's permission.
     */
    ASK_CONSEQUENTIAL_ONLY,
}

/** An app the owner authorised for screen operation. Agents still need screen grants for it. */
@Serializable
data class OperatorApp(
    val packageName: String,
    val label: String,
    val authorisedAt: Long,
    /**
     * SHA-256 of the app's signing certificate when it was authorised. `:device` re-verifies it
     * on every call and treats a mismatch as not authorised, so another app installed later
     * under the same package name inherits nothing. Null only if it could not be read.
     */
    val signingCertSha256: String? = null,
    /** How often its screen actions ask the owner; asking every time unless the owner chose otherwise. */
    val policy: ScreenActionPolicy = ScreenActionPolicy.ASK_EVERY_ACTION,
)

/**
 * The apps the owner authorised for screen operation (Tools › Screen control). Lists are by
 * authorisation time, then package name; flows emit at once and after every change.
 * Implementations: Room (`:data`) and in-memory (`:testkit`); both pass
 * `OperatorAppRepositoryContract`. Protected packages are refused by [OwnerOperatorApps],
 * the broker and the screen operator, not by storage.
 */
interface OperatorAppRepository {
    fun observeAll(): Flow<List<OperatorApp>>

    suspend fun getAll(): List<OperatorApp>

    suspend fun get(packageName: String): OperatorApp?

    /** Authorises (or relabels) by package name. */
    suspend fun upsert(app: OperatorApp)

    /** Withdraws authorisation. No-op if absent. [OwnerOperatorApps.withdraw] revokes the app's grants first. */
    suspend fun delete(packageName: String)
}

/**
 * The owner-facing description of a screen action, so every screen tool's approval preview
 * names the same things: the app and window, the control and where it is, and for a
 * consequential action everything the window shows. Every value is [PreviewText.exact].
 */
object ScreenPreview {
    /** The most texts of a window listed in a preview; the rest are counted. */
    const val MAX_WINDOW_LINES: Int = 150

    /** The most characters of one text in a preview. */
    const val MAX_TEXT_CHARS: Int = 300

    /** Closes a list that leaves something out which the pinned fingerprint still covers. */
    const val STILL_CHECKED: String =
        "  (What is not listed is still checked: if anything in the window changes before the action runs, it is not done.)"

    /** The control's name: text, else description, else label, else hint, else view id, else "unlabelled". */
    fun nameOf(element: ScreenElement): String {
        val name = listOf(element.text, element.description, element.label, element.hint, element.resourceId?.substringAfter(":id/"))
            .firstOrNull { !it.isNullOrBlank() }
        return if (name == null) "unlabelled ${element.role}" else "${element.role} ${PreviewText.quoted(name)}"
    }

    /**
     * What state a control is in, as a preview line carries it: " [checked]" or " [not checked]"
     * for a control that can be checked, " [selected]", " [disabled]"; empty for none of these.
     */
    fun stateOf(element: ScreenElement): String = buildString {
        if (element.checkable) append(if (element.checked) " [checked]" else " [not checked]")
        if (element.selected) append(" [selected]")
        if (!element.enabled) append(" [disabled]")
    }

    /**
     * Details lines for an action on [element] in [appLabel]'s window. With [consequential],
     * also every editable field in [snapshot] that has text and every other text of the window
     * ([windowLines]), so the owner sees the payee, the price and what a "Send" would send
     * (passwords and sensitive fields are never in a snapshot).
     */
    fun details(appLabel: String, element: ScreenElement, snapshot: ScreenSnapshot?, consequential: Boolean): List<String> = buildList {
        add(appLine(appLabel, element.packageName, snapshot))
        add("Control: ${nameOf(element)}${stateOf(element)}")
        element.context?.takeIf { it.isNotBlank() }?.let { add("Where: ${PreviewText.exact(it)}") }
        if (consequential && snapshot != null) addAll(contents(snapshot))
    }

    /**
     * Details lines for an action on [packageName]'s window as a whole (Back, Home, a scroll of
     * the page): the app and window, and, given a [snapshot], everything in it as [details] shows.
     */
    fun windowDetails(appLabel: String, packageName: String, snapshot: ScreenSnapshot?): List<String> =
        listOf(appLine(appLabel, packageName, snapshot)) + (snapshot?.let(::contents) ?: emptyList())

    private fun appLine(appLabel: String, packageName: String, snapshot: ScreenSnapshot?): String {
        val title = snapshot?.windowTitle?.let { ", window ${PreviewText.quoted(it)}" } ?: ""
        return "App: ${PreviewText.exact(appLabel)} ($packageName)$title"
    }

    /** The fields that hold text (each with its state, and how much of a long text the reader left out), then [windowLines]. */
    private fun contents(snapshot: ScreenSnapshot): List<String> = buildList {
        for (field in snapshot.elements.filter { it.editable && !it.text.isNullOrEmpty() }) {
            val text = field.text!! + if (field.cutChars > 0) "…" else ""
            add("Field ${PreviewText.quoted(fieldName(field))} contains: ${PreviewText.quoted(text)}${leftOut(field.cutChars)}${stateOf(field)}")
        }
        addAll(windowLines(snapshot))
    }

    /**
     * The window's texts for an approval preview: one line per element that is not an editable
     * field (those are listed by [details]), in reading order, each [PreviewText.exact]:
     * - its text and description; without either, the words of its label that no other line shows
     *   (a row's label repeats the texts inside it), else its hint, marked as a hint;
     * - its state ([stateOf]): "[checked]", "[selected]", "[disabled]". A control in such a state
     *   is listed even when nothing else is left to say of it;
     * - at most [MAX_TEXT_CHARS] characters, then how many more there are, counting what the
     *   reader itself left out ([ScreenElement.cutChars]).
     *
     * At most [MAX_WINDOW_LINES] lines, then how many were left out. Notes password fields left
     * out and a window with more elements than the list holds; when the snapshot carries a
     * fingerprint of the whole window ([ScreenSnapshot.windowDigest]) it ends with [STILL_CHECKED]
     * whenever anything was left out, since the pinned [digest] covers that too.
     */
    fun windowLines(snapshot: ScreenSnapshot): List<String> = buildList {
        val listed = snapshot.elements.filter { !it.editable }
        val ownLines = HashSet<String>()
        for (e in listed) {
            e.text?.let(ownLines::add)
            e.description?.let(ownLines::add)
        }
        val texts = listed.mapNotNull { e -> line(e, ownLines) }
        add("On screen now (${texts.size} ${if (texts.size == 1) "text" else "texts"}):")
        texts.take(MAX_WINDOW_LINES).forEach { add("  ${it.text}") }
        if (texts.size > MAX_WINDOW_LINES) add("  …and ${texts.size - MAX_WINDOW_LINES} more texts not listed here.")
        if (snapshot.withheld > 0) add("  (${snapshot.withheld} password or sensitive ${if (snapshot.withheld == 1) "field is" else "fields are"} never shown.)")
        val pinned = snapshot.windowDigest != null
        if (snapshot.truncated) {
            add(
                when {
                    !pinned -> "  (The window holds more than could be read; the rest is not shown.)"
                    snapshot.unlisted == 1 -> "  (The window holds 1 more element than could be read into this list.)"
                    else -> "  (The window holds ${snapshot.unlisted} more elements than could be read into this list.)"
                },
            )
        }
        val cut = snapshot.elements.any { it.cutChars > 0 } || texts.take(MAX_WINDOW_LINES).any { it.cut }
        if (pinned && (snapshot.truncated || cut || texts.size > MAX_WINDOW_LINES)) add(STILL_CHECKED)
    }

    /** One line of [windowLines]; [cut]: part of its text is left out. */
    private class Line(val text: String, val cut: Boolean)

    /** [e]'s line in [windowLines], or null when it has nothing to show; [ownLines]: the texts and descriptions that get lines of their own. */
    private fun line(e: ScreenElement, ownLines: Set<String>): Line? {
        val state = stateOf(e)
        fun of(words: String, alreadyCut: Int, prefix: String = ""): Line =
            Line("${e.role}: $prefix${clipped(words, alreadyCut)}$state", words.length > MAX_TEXT_CHARS || alreadyCut > 0)
        val own = listOfNotNull(e.text, e.description?.takeIf { it != e.text }).filter { it.isNotBlank() }.joinToString(" · ")
        if (own.isNotEmpty()) return of(own, e.cutChars)
        val label = e.label?.takeIf { it.isNotBlank() }
        if (label != null) {
            // A control in a state worth showing keeps its whole label; otherwise only what no other line says.
            val words = if (state.isNotEmpty()) label else label.split(" · ").filter { it.isNotBlank() && it !in ownLines }.joinToString(" · ")
            if (words.isNotEmpty()) return of(words, 0)
        }
        e.hint?.takeIf { it.isNotBlank() }?.let { return of(it, e.cutChars, prefix = "hint ") }
        return if (state.isEmpty()) null else Line("${e.role}:$state", cut = false)
    }

    /**
     * A fingerprint of everything [windowLines] and [details] show of [snapshot], and more: the
     * window title and, per element in order, its kind, text, description, hint, view id, label
     * and checked, selected and enabled state, plus how many fields were withheld and the
     * reader's fingerprint of the whole window ([ScreenSnapshot.windowDigest]: the elements the
     * list leaves out and the uncut texts). Positions and focus are left out, so moving the
     * keyboard or the owner's return to the app do not change it; any changed text (a payee, a
     * price, a field) or state (a ticked box, the selected tab, a control switched off) does.
     * SHA-256, hex.
     */
    fun digest(snapshot: ScreenSnapshot): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.field(snapshot.packageName)
        md.field(snapshot.windowTitle)
        md.field(snapshot.withheld.toString())
        md.field(snapshot.truncated.toString())
        md.field(snapshot.windowDigest)
        for (e in snapshot.elements) {
            md.field(e.role); md.field(e.text); md.field(e.description); md.field(e.hint); md.field(e.resourceId); md.field(e.label)
            md.field(if (e.checkable) e.checked.toString() else "")
            md.field(e.selected.toString())
            md.field(e.enabled.toString())
        }
        return md.digest().hex()
    }

    private fun fieldName(field: ScreenElement): String =
        field.hint ?: field.description ?: field.resourceId?.substringAfter(":id/") ?: "field ${field.number}"

    /** [s] between quotes, cut to [MAX_TEXT_CHARS], then how many characters are left out ([alreadyCut]: by the reader, before). */
    private fun clipped(s: String, alreadyCut: Int): String {
        val shown = if (s.length > MAX_TEXT_CHARS) s.take(MAX_TEXT_CHARS) else s
        val left = s.length - shown.length + alreadyCut
        return PreviewText.quoted(if (left > 0) "$shown…" else shown) + leftOut(left)
    }

    private fun leftOut(chars: Int): String = when {
        chars <= 0 -> ""
        chars == 1 -> " (1 more character not shown)"
        else -> " ($chars more characters not shown)"
    }
}
