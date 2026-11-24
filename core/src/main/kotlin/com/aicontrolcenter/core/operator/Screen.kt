package com.aicontrolcenter.core.operator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Whether the accessibility service can be used. */
enum class ScreenServiceState {
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
)

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
     * [maxElements] elements; [ScreenSnapshot.truncated] says when more were left out.
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

    /** Timing of the operating indicator. */
    companion object {
        /** The indicator stays this long after an agent's last screen call. */
        const val SESSION_IDLE_MS: Long = 30_000

        /** The most elements [inspect] returns by default: more than any read, so a preview covers the whole window. */
        const val INSPECT_MAX_ELEMENTS: Int = 1_000
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
}

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

    /** The control's name: text, else description, else label, else hint, else view id, else "unlabelled". */
    fun nameOf(element: ScreenElement): String {
        val name = listOf(element.text, element.description, element.label, element.hint, element.resourceId?.substringAfter(":id/"))
            .firstOrNull { !it.isNullOrBlank() }
        return if (name == null) "unlabelled ${element.role}" else "${element.role} \"${PreviewText.exact(name)}\""
    }

    /**
     * Details lines for an action on [element] in [appLabel]'s window. With [consequential],
     * also every editable field in [snapshot] that has text and every other text of the window
     * ([windowLines]), so the owner sees the payee, the price and what a "Send" would send
     * (passwords and sensitive fields are never in a snapshot).
     */
    fun details(appLabel: String, element: ScreenElement, snapshot: ScreenSnapshot?, consequential: Boolean): List<String> = buildList {
        add(appLine(appLabel, element.packageName, snapshot))
        add("Control: ${nameOf(element)}")
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
        val title = snapshot?.windowTitle?.let { ", window \"${PreviewText.exact(it)}\"" } ?: ""
        return "App: ${PreviewText.exact(appLabel)} ($packageName)$title"
    }

    /** The fields that hold text, then [windowLines]. */
    private fun contents(snapshot: ScreenSnapshot): List<String> = buildList {
        for (field in snapshot.elements.filter { it.editable && !it.text.isNullOrEmpty() }) {
            add("Field \"${PreviewText.exact(fieldName(field))}\" contains: \"${PreviewText.exact(field.text!!)}\"")
        }
        addAll(windowLines(snapshot))
    }

    /**
     * The window's texts for an approval preview: one line per element that shows a text or a
     * description and is not an editable field (those are listed by [details]), in reading
     * order, each [PreviewText.exact] and at most [MAX_TEXT_CHARS] characters; at most
     * [MAX_WINDOW_LINES] lines, then how many were left out. Notes password fields left out and
     * a read that stopped early.
     */
    fun windowLines(snapshot: ScreenSnapshot): List<String> = buildList {
        val texts = snapshot.elements.filter { !it.editable }.mapNotNull { e ->
            listOfNotNull(e.text, e.description?.takeIf { it != e.text }).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }
                ?.let { "${e.role}: \"${PreviewText.exact(clip(it))}\"" }
        }
        add("On screen now (${texts.size} ${if (texts.size == 1) "text" else "texts"}):")
        texts.take(MAX_WINDOW_LINES).forEach { add("  $it") }
        if (texts.size > MAX_WINDOW_LINES) add("  …and ${texts.size - MAX_WINDOW_LINES} more texts not listed here.")
        if (snapshot.withheld > 0) add("  (${snapshot.withheld} password or sensitive ${if (snapshot.withheld == 1) "field is" else "fields are"} never shown.)")
        if (snapshot.truncated) add("  (The window holds more than could be read; the rest is not shown.)")
    }

    /**
     * A fingerprint of everything [windowLines] and [details] show of [snapshot], and more: the
     * window title and, per element in order, its kind, text, description, hint, view id, label
     * and checked state, plus how many fields were withheld. Positions and focus are left out, so
     * moving the keyboard or the owner's return to the app do not change it; any changed text
     * (a payee, a price, a field) does. SHA-256, hex.
     */
    fun digest(snapshot: ScreenSnapshot): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            val bytes = (value ?: "\u0000null").toByteArray(Charsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        add(snapshot.packageName)
        add(snapshot.windowTitle)
        add(snapshot.withheld.toString())
        add(snapshot.truncated.toString())
        for (e in snapshot.elements) {
            add(e.role); add(e.text); add(e.description); add(e.hint); add(e.resourceId); add(e.label)
            add(if (e.checkable) e.checked.toString() else "")
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fieldName(field: ScreenElement): String =
        field.hint ?: field.description ?: field.resourceId?.substringAfter(":id/") ?: "field ${field.number}"

    private fun clip(s: String): String = if (s.length > MAX_TEXT_CHARS) s.take(MAX_TEXT_CHARS) + "…" else s
}
