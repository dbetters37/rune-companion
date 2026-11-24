package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.ScreenBounds
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScreenshotInfo
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.operator.WindowDigest
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A control on a [FakeScreen]. Password and sensitive controls are withheld from reads and refused as targets. */
data class FakeControl(
    val role: String = "Button",
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val resourceId: String? = null,
    /** Merged child texts and other labels ([ScreenElement.label]). */
    val label: String? = null,
    /** The row or ancestor it sits in ([ScreenElement.context]). */
    val context: String? = null,
    val nodeKey: String? = null,
    val bounds: ScreenBounds = ScreenBounds(0, 0, 200, 60),
    val clickable: Boolean = true,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val sensitive: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
) {
    val withheld: Boolean get() = password || sensitive
}

/** What the fake phone shows: one app's window. */
data class FakeScreen(val packageName: String, val title: String? = null, val controls: List<FakeControl> = emptyList())

/** One call to [FakeScreenOperator], recorded whether or not it acted: one subtype per method, holding its arguments. */
sealed interface ScreenCall {
    val agentName: String

    data class Read(override val agentName: String, val packageName: String) : ScreenCall
    data class Tap(override val agentName: String, val element: ScreenElement) : ScreenCall
    data class Type(override val agentName: String, val element: ScreenElement, val text: String, val submit: Boolean) : ScreenCall
    data class Scroll(override val agentName: String, val packageName: String, val direction: ScrollDirection, val element: ScreenElement?) : ScreenCall
    data class Global(override val agentName: String, val fromPackage: String, val action: GlobalAction) : ScreenCall
    data class WaitFor(override val agentName: String, val packageName: String, val text: String) : ScreenCall
    data class Screenshot(override val agentName: String, val packageName: String, val target: WorkspaceFile) : ScreenCall
}

/**
 * A scriptable screen that follows the [ScreenOperator] contract: tests set [screen] (and change
 * it to simulate navigation, or from [onAction] after a tap), choose the [authorised] apps,
 * switch [setState] and Stop ([stop], [resume]), and check [calls] (every call), [performed]
 * (calls that acted), [inspected] and [endedSessions]. Snapshots are numbered from 1; the last
 * [KEPT_SNAPSHOTS] are kept for [ScreenTarget.Numbered]. To act out the owner answering inside
 * this app, set [screen] to a window of [ownPackage] and back.
 *
 * Like the device's operator it keeps at most [ScreenOperator.MAX_TEXT_CHARS] characters of a
 * text, description or hint ([ScreenElement.cutChars] counts the rest), and [inspect] fingerprints
 * the whole window, uncut texts and unlisted elements included ([ScreenSnapshot.windowDigest]),
 * unless it holds more than [digestMaxElements] elements. Both pass the testkit's
 * `ScreenInspectContract`.
 *
 * Like the device's operator it is not the owner's [ScreenControl]: hand [asControl] to the
 * owner's side, so nothing agent-facing that holds this fake can be cast to Stop and Resume.
 */
class FakeScreenOperator(
    initialState: ScreenServiceState = ScreenServiceState.READY,
    screen: FakeScreen? = null,
    var ownPackage: String = "com.aicontrolcenter.app",
    /** Apps the owner authorised; by default the initial screen's app. */
    authorisedApps: Collection<String> = listOfNotNull(screen?.packageName),
    @Volatile var launcher: String? = "com.android.launcher3",
    @Volatile var screenshotsSupported: Boolean = true,
    private val clock: Clock = Clock { 1_700_000_000_000L },
    /** The most elements of one window [inspect] fingerprints; above it a snapshot has no [ScreenSnapshot.windowDigest]. */
    @Volatile var digestMaxElements: Int = ScreenOperator.DIGEST_MAX_ELEMENTS,
) : ScreenOperator {
    private val stateFlow = MutableStateFlow(initialState)
    private val stoppedFlow = MutableStateFlow(false)
    private val screenFlow = MutableStateFlow(screen)
    private val lock = Any()
    private var snapshotCounter = 0L
    private val snapshots = LinkedHashMap<Long, Pair<ScreenSnapshot, FakeScreen>>()

    override val state: StateFlow<ScreenServiceState> = stateFlow.asStateFlow()
    override val stopped: StateFlow<Boolean> = stoppedFlow.asStateFlow()

    /** Package names authorised for screen operation, read on every call. */
    val authorised: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(authorisedApps) }

    /** Packages protected on this "device" in addition to the static list ([com.aicontrolcenter.core.operator.ProtectedPackages.resolved]). */
    val extraProtected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The window in front now; null for none. */
    var screen: FakeScreen?
        get() = screenFlow.value
        set(value) {
            screenFlow.value = value
        }

    val calls = CopyOnWriteArrayList<ScreenCall>()
    val performed = CopyOnWriteArrayList<ScreenCall>()

    /** Every text [type] put into a field, in order. */
    val typed = CopyOnWriteArrayList<Pair<ScreenElement, String>>()

    /** Agent names passed to [endSession]. */
    val endedSessions = CopyOnWriteArrayList<String>()

    /** Packages [inspect] read (side-effect free reads, not in [calls]: they start no session). */
    val inspected = CopyOnWriteArrayList<String>()

    /** Runs after every call that acted, e.g. to show the next screen. */
    @Volatile var onAction: (ScreenCall) -> Unit = {}

    fun setState(value: ScreenServiceState) {
        stateFlow.value = value
    }

    /** The owner's Stop, as a test presses it. */
    fun stop() {
        stoppedFlow.value = true
    }

    /** The owner's Resume, as a test presses it. */
    fun resume() {
        stoppedFlow.value = false
    }

    /** The owner's Stop and Resume of this screen: a separate object, for the owner's side only. */
    fun asControl(): ScreenControl = FakeScreenControl(this)

    override fun endSession(agentName: String) {
        endedSessions += agentName
    }

    override suspend fun activeWindow(): ActiveWindow? =
        screen?.takeIf { stateFlow.value == ScreenServiceState.READY }
            ?.let { ActiveWindow(it.packageName, it.title.takeIf { _ -> it.packageName in authorised && !isProtected(it.packageName) }) }

    override suspend fun launcherPackage(): String? = launcher

    override suspend fun read(agentName: String, packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> {
        val call = ScreenCall.Read(agentName, packageName)
        calls += call
        val shown = gate(packageName) ?: return refusal(packageName)
        return ScreenResult.Ok(snapshot(shown, maxElements)).also { acted(call) }
    }

    override suspend fun inspect(packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> {
        val shown = gate(packageName) ?: return refusal(packageName)
        inspected += packageName
        return ScreenResult.Ok(snapshot(shown, maxElements, keep = false))
    }

    override suspend fun find(packageName: String, target: ScreenTarget): ScreenResult<ScreenElement> {
        val shown = gate(packageName) ?: return refusal(packageName)
        return when (target) {
            is ScreenTarget.Numbered -> {
                val stored = synchronized(lock) { snapshots[target.snapshotId]?.first } ?: return ScreenResult.Stale
                if (stored.packageName != packageName) return ScreenResult.WrongWindow(packageName, stored.packageName)
                val element = stored.elements.getOrNull(target.number - 1) ?: return ScreenResult.NotFound("element ${target.number}")
                if (controlFor(element, shown) == null) ScreenResult.Stale else ScreenResult.Ok(element)
            }
            is ScreenTarget.ExactText -> {
                val matches = snapshot(shown, Int.MAX_VALUE).elements.filter { it.text == target.text || it.description == target.text }
                when (matches.size) {
                    0 -> ScreenResult.NotFound("\"${target.text}\"")
                    1 -> ScreenResult.Ok(matches.single())
                    else -> ScreenResult.Ambiguous(matches.size)
                }
            }
        }
    }

    override suspend fun tap(agentName: String, element: ScreenElement): ScreenResult<Unit> {
        val call = ScreenCall.Tap(agentName, element)
        calls += call
        val shown = gate(element.packageName) ?: return refusal(element.packageName)
        val control = controlFor(element, shown)
        if (control != null && !control.withheld && !control.enabled) return ScreenResult.NotSupported("That control is switched off (disabled) right now.")
        return resolve(element) { acted(call) }
    }

    override suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit> {
        val call = ScreenCall.Type(agentName, element, text, submit)
        calls += call
        val shown = gate(element.packageName) ?: return refusal(element.packageName)
        val control = controlFor(element, shown)
        if (control != null && !control.withheld && !control.editable) return ScreenResult.NotSupported("That element does not take text.")
        if (control != null && !control.withheld && !control.enabled) return ScreenResult.NotSupported("That field is switched off (disabled) right now.")
        return resolve(element) {
            typed += element to text
            acted(call)
        }
    }

    override suspend fun scroll(agentName: String, packageName: String, direction: ScrollDirection, element: ScreenElement?): ScreenResult<Unit> {
        val call = ScreenCall.Scroll(agentName, packageName, direction, element)
        calls += call
        val shown = gate(packageName) ?: return refusal(packageName)
        if (element != null) {
            if (element.packageName != packageName) return ScreenResult.WrongWindow(packageName, element.packageName)
            return resolve(element) { acted(call) }
        }
        if (shown.controls.none { it.scrollable && !it.withheld }) return ScreenResult.NotFound("anything to scroll")
        acted(call)
        return ScreenResult.Ok(Unit)
    }

    override suspend fun globalAction(agentName: String, fromPackage: String, action: GlobalAction): ScreenResult<Unit> {
        val call = ScreenCall.Global(agentName, fromPackage, action)
        calls += call
        gate(fromPackage) ?: return refusal(fromPackage)
        acted(call)
        return ScreenResult.Ok(Unit)
    }

    override suspend fun waitFor(agentName: String, packageName: String, text: String, timeoutMs: Long): ScreenResult<ScreenElement> {
        val call = ScreenCall.WaitFor(agentName, packageName, text)
        calls += call
        gate(packageName) ?: return refusal(packageName)
        fun hasText(s: FakeScreen?) = s != null && s.controls.any {
            !it.withheld && (it.text?.take(MAX_TEXT)?.contains(text) == true || it.description?.take(MAX_TEXT)?.contains(text) == true)
        }
        withTimeoutOrNull(timeoutMs) {
            combine(screenFlow, stoppedFlow, stateFlow) { s, stop, st -> stop || st != ScreenServiceState.READY || s?.packageName != packageName || hasText(s) }
                .first { it }
        } ?: return ScreenResult.NotFound("\"$text\" did not appear within $timeoutMs ms")
        val shown = gate(packageName) ?: return refusal(packageName)
        val element = snapshot(shown, Int.MAX_VALUE).elements.first { it.text?.contains(text) == true || it.description?.contains(text) == true }
        acted(call)
        return ScreenResult.Ok(element)
    }

    override suspend fun screenshot(agentName: String, packageName: String, target: WorkspaceFile): ScreenResult<ScreenshotInfo> {
        val call = ScreenCall.Screenshot(agentName, packageName, target)
        calls += call
        gate(packageName) ?: return refusal(packageName)
        if (!screenshotsSupported) return ScreenResult.NotSupported("Screenshots need Android 11 or later.")
        if (target.file.exists()) return ScreenResult.Failed("${target.path} already exists.")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        target.file.parentFile?.mkdirs()
        target.file.writeBytes(png)
        acted(call)
        return ScreenResult.Ok(ScreenshotInfo(1080, 2400, png.size.toLong()))
    }

    private fun isProtected(packageName: String) = ConsequentialControls.isProtectedPackage(packageName, ownPackage, extraProtected)

    /** The screen if every rule allows acting on [packageName]'s window now, else null (see [refusal]). */
    private fun gate(packageName: String): FakeScreen? {
        val shown = screen
        return shown.takeIf {
            stateFlow.value == ScreenServiceState.READY && !stoppedFlow.value && !isProtected(packageName) &&
                packageName in authorised && shown?.packageName == packageName
        }
    }

    /** Why [gate] refused, checked in the contract's order. */
    private fun refusal(packageName: String): ScreenResult<Nothing> = when {
        stateFlow.value != ScreenServiceState.READY -> ScreenResult.Unavailable(stateFlow.value)
        stoppedFlow.value -> ScreenResult.Stopped
        isProtected(packageName) -> ScreenResult.Protected(packageName)
        packageName !in authorised -> ScreenResult.NotAuthorised(packageName)
        else -> ScreenResult.WrongWindow(packageName, screen?.packageName)
    }

    private fun acted(call: ScreenCall) {
        performed += call
        onAction(call)
    }

    /**
     * A snapshot of [shown]; [keep]: numbered and kept for [ScreenTarget.Numbered], else id 0 and not kept ([inspect]),
     * with the fingerprint of the whole window.
     */
    private fun snapshot(shown: FakeScreen, maxElements: Int, keep: Boolean = true): ScreenSnapshot = synchronized(lock) {
        val visible = shown.controls.filter { !it.withheld }
        val id = if (keep) ++snapshotCounter else 0L
        val elements = visible.take(maxElements).mapIndexed { i, c -> element(c, shown.packageName, id, i + 1) }
        val digest = if (keep) null else windowDigest(shown)
        ScreenSnapshot(
            id, shown.packageName, shown.title, elements, shown.controls.size - visible.size, visible.size > maxElements, clock.now(),
            windowDigest = digest, unlisted = if (digest != null) visible.size - elements.size else 0,
        ).also {
            if (keep) {
                snapshots[id] = it to shown
                while (snapshots.size > KEPT_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
            }
        }
    }

    /** Every control of [shown] in order, texts uncut, as the device's reader feeds [WindowDigest]; null above [digestMaxElements]. */
    private fun windowDigest(shown: FakeScreen): String? {
        if (shown.controls.size > digestMaxElements) return null
        val digest = WindowDigest()
        for (c in shown.controls) {
            if (c.withheld) digest.withheld() else digest.element(c.role, c.text, c.description, c.hint, c.resourceId, c.label, c.checkable, c.checked, c.selected, c.enabled)
        }
        return digest.finish()
    }

    private fun element(c: FakeControl, packageName: String, snapshotId: Long, number: Int) = ScreenElement(
        snapshotId = snapshotId, number = number, packageName = packageName, role = c.role, text = c.text?.take(MAX_TEXT),
        description = c.description?.take(MAX_TEXT), hint = c.hint?.take(MAX_TEXT), resourceId = c.resourceId, label = c.label,
        context = c.context, nodeKey = c.nodeKey, bounds = c.bounds,
        clickable = c.clickable, editable = c.editable, scrollable = c.scrollable, checkable = c.checkable, checked = c.checked,
        enabled = c.enabled, selected = c.selected,
        cutChars = listOf(c.text, c.description, c.hint).sumOf { ((it?.length ?: 0) - MAX_TEXT).coerceAtLeast(0) },
    )

    /** The one control on [shown] that [element] still is; null when none or several are. */
    private fun controlFor(element: ScreenElement, shown: FakeScreen): FakeControl? {
        if (shown.packageName != element.packageName) return null
        return shown.controls.singleOrNull { element(it, shown.packageName, 0, 0).sameControlAs(element) }
    }

    private inline fun resolve(element: ScreenElement, onFound: () -> Unit): ScreenResult<Unit> {
        val shown = gate(element.packageName) ?: return refusal(element.packageName)
        val control = controlFor(element, shown) ?: return ScreenResult.Stale
        if (control.withheld) return ScreenResult.Sensitive
        onFound()
        return ScreenResult.Ok(Unit)
    }

    /** Limits. */
    companion object {
        /** How many recent snapshots [ScreenTarget.Numbered] can refer to. */
        const val KEPT_SNAPSHOTS = 16

        private const val MAX_TEXT = ScreenOperator.MAX_TEXT_CHARS
    }
}

/** [ScreenControl] over a [FakeScreenOperator]: what the owner's side gets, never the agents'. */
private class FakeScreenControl(private val screen: FakeScreenOperator) : ScreenControl {
    override val stopped: StateFlow<Boolean> get() = screen.stopped

    override fun stop() = screen.stop()

    override fun resume() = screen.resume()
}
