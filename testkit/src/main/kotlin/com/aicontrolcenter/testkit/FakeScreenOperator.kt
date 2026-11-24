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
    val password: Boolean = false,
    val sensitive: Boolean = false,
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
 * (calls that acted) and [endedSessions]. Snapshots are numbered from 1; the last
 * [KEPT_SNAPSHOTS] are kept for [ScreenTarget.Numbered].
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
        return resolve(element) { acted(call) }
    }

    override suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit> {
        val call = ScreenCall.Type(agentName, element, text, submit)
        calls += call
        val shown = gate(element.packageName) ?: return refusal(element.packageName)
        val control = controlFor(element, shown)
        if (control != null && !control.withheld && !control.editable) return ScreenResult.NotSupported("That element does not take text.")
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
        fun hasText(s: FakeScreen?) = s != null && s.controls.any { !it.withheld && (it.text?.contains(text) == true || it.description?.contains(text) == true) }
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

    private fun snapshot(shown: FakeScreen, maxElements: Int): ScreenSnapshot = synchronized(lock) {
        val visible = shown.controls.filter { !it.withheld }
        val id = ++snapshotCounter
        val elements = visible.take(maxElements).mapIndexed { i, c -> element(c, shown.packageName, id, i + 1) }
        ScreenSnapshot(id, shown.packageName, shown.title, elements, shown.controls.size - visible.size, visible.size > maxElements, clock.now())
            .also {
                snapshots[id] = it to shown
                while (snapshots.size > KEPT_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
            }
    }

    private fun element(c: FakeControl, packageName: String, snapshotId: Long, number: Int) = ScreenElement(
        snapshotId = snapshotId, number = number, packageName = packageName, role = c.role, text = c.text, description = c.description,
        hint = c.hint, resourceId = c.resourceId, label = c.label, context = c.context, nodeKey = c.nodeKey, bounds = c.bounds,
        clickable = c.clickable, editable = c.editable, scrollable = c.scrollable,
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
    }
}

/** [ScreenControl] over a [FakeScreenOperator]: what the owner's side gets, never the agents'. */
private class FakeScreenControl(private val screen: FakeScreenOperator) : ScreenControl {
    override val stopped: StateFlow<Boolean> get() = screen.stopped

    override fun stop() = screen.stop()

    override fun resume() = screen.resume()
}
