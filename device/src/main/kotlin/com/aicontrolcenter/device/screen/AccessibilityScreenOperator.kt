package com.aicontrolcenter.device.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.FrontState
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScreenshotInfo
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [ScreenOperator] over [OperatorAccessibilityService].
 *
 * Every call is serialised and runs the checks of the [ScreenOperator] contract in its order:
 * the service is connected, Stop is not pressed, the app is not protected, the app is in
 * `operator_apps` now and its signing certificate is the one recorded when it was authorised (an
 * app whose certificate was not recorded, or cannot be read now, is not trusted), and the window
 * in front is that app's `TYPE_APPLICATION` window with no lock screen. Only then is anything of
 * the window read. An action re-reads the window, acts only on the single control that is
 * [ScreenElement.sameControlAs] the one named, re-checks the window and Stop immediately before
 * the node action, asks the app once more for the control and the row around it (Android's node
 * cache can lag behind the app), and never touches password fields or views marked sensitive.
 *
 * Android's settings are never read on the thread of whoever looks at [state]: the first read
 * runs on [io] and the state is [ScreenServiceState.CHECKING] until it is done; every later read
 * runs on [io] too, one at a time.
 *
 * Stop ([StopSignal]: it can read and press Stop, never Resume) cancels the call in progress, which returns [ScreenResult.Stopped], ends
 * every session and hides the banner. A session (the banner "<agent> is operating <app> · Stop")
 * starts with an agent's first call that passes the checks and lasts until [endSession], Stop,
 * or [ScreenOperator.SESSION_IDLE_MS] after its last call.
 */
internal class AccessibilityScreenOperator(
    /** The authorised apps, read only. */
    private val apps: AuthorisedAppLookup,
    /** Read on every check; resolved off the main thread at start-up ([ProtectedPackagesResolver.startResolving]). */
    private val protectedPackages: () -> ProtectedPackages,
    /** The owner's Stop, without Resume. */
    private val stopSwitch: StopSignal,
    private val link: ServiceLink,
    private val availability: ScreenAvailability,
    private val device: DeviceFacts,
    clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    /** The Android version whose screenshot and keyboard-action rules apply; the device's own outside tests. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val reader: ScreenReader = ScreenReader(),
    private val pollMs: Long = WAIT_POLL_MS,
) : ScreenOperator {
    private val stateFlow = MutableStateFlow(ScreenServiceState.CHECKING)
    private val stateLock = Any()
    private val started = AtomicBoolean(false)
    private val snapshots = SnapshotStore(clock)
    private val callLock = Mutex()
    private val activeCall = AtomicReference<Job?>(null)
    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, Session>()

    private val publicState = stateFlow.asStateFlow()

    override val state: StateFlow<ScreenServiceState>
        get() {
            start()
            return publicState
        }

    override val stopped: StateFlow<Boolean> = stopSwitch.stopped

    /** Whether the lock screen was showing when the banner was last applied; the banner never shows over it. */
    private val lockedWhenApplied = AtomicBoolean(false)

    init {
        stopSwitch.onStop(::onOwnerStop)
        scope.launch {
            link.host.collect {
                // The service connected or went away. Android is read where it always is, never on this scope's own threads.
                if (started.get()) withContext(io) { refreshState() }
                applyIndicator()
            }
        }
        scope.launch {
            // The lock screen coming or going changes the windows: hide the banner over it, show it again after.
            link.changes.collect {
                val operating = synchronized(sessionLock) { sessions.isNotEmpty() }
                if (operating && device.keyguardLocked() != lockedWhenApplied.get()) applyIndicator()
            }
        }
    }

    /**
     * Reads Android's settings again, on the caller's thread: call it on [io] only (the layer does, when the Screen
     * control screen comes back into view). Reads run one at a time, each seeing the service as it is when it runs, so
     * the last one to finish leaves the newest state.
     */
    fun refreshState(): ScreenServiceState = synchronized(stateLock) {
        screenState(link.host.value != null, availability, sdkInt).also { stateFlow.value = it }
    }

    /**
     * Starts the first read of Android's settings, and the watch for changes, on [io]: never on the thread that asked.
     * Until that read is done the state is [ScreenServiceState.CHECKING].
     */
    internal fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            refreshState()
            // Android calls the settings observer on the main thread; the re-read (settings and PackageManager) runs off it.
            availability.watch { scope.launch(io) { refreshState() } }
        }
    }

    override suspend fun activeWindow(): ActiveWindow? = withContext(io) {
        start()
        val host = link.host.value ?: return@withContext null
        val front = front(host) ?: return@withContext null
        val packageName = front.packageName ?: return@withContext null
        val titled = !protectedPackages().isProtected(packageName) && !device.keyguardLocked() && authorised(packageName) != null
        ActiveWindow(packageName, if (titled) ScreenReader.clean(front.title) else null)
    }

    /**
     * What is in front (Phase 6, D6): [FrontState.Hidden] for an active window whose content Android
     * does not let this app read (no root, as its permission screens), so a screen tool can say so
     * instead of "no app window"; nothing of it is read.
     */
    override suspend fun frontState(): FrontState = withContext(io) {
        start()
        val host = link.host.value ?: return@withContext FrontState.None
        val front = front(host) ?: return@withContext FrontState.None
        val packageName = front.packageName
        if (front.root == null || packageName == null) return@withContext FrontState.Hidden
        val titled = !protectedPackages().isProtected(packageName) && !device.keyguardLocked() && authorised(packageName) != null
        FrontState.App(ActiveWindow(packageName, if (titled) ScreenReader.clean(front.title) else null))
    }

    override suspend fun launcherPackage(): String? = withContext(io) { device.launcherPackage() }

    override suspend fun read(agentName: String, packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> =
        call(agentName, packageName) { open ->
            ScreenResult.Ok(snapshots.record(readTree(open), maxElements).snapshot)
        }

    /** No session starts (no agent is named) and nothing is kept: the read an assessment shows the owner. */
    override suspend fun inspect(packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> =
        call(null, packageName) { open -> ScreenResult.Ok(snapshots.unrecorded(readTree(open, wholeWindow = true), maxElements)) }

    override suspend fun find(packageName: String, target: ScreenTarget): ScreenResult<ScreenElement> = call(null, packageName) { open ->
        val tree = readTree(open)
        when (target) {
            is ScreenTarget.Numbered -> {
                val stored = snapshots.get(target.snapshotId) ?: return@call ScreenResult.Stale
                if (stored.packageName != packageName) return@call ScreenResult.WrongWindow(packageName, stored.packageName)
                val element = stored.elements.getOrNull(target.number - 1) ?: return@call ScreenResult.NotFound("element ${target.number}")
                val entry = when (val found = refind(tree, element)) {
                    is Refound.Refused -> return@call found.result
                    is Refound.Found -> found.entry
                }
                // Report the control a tap would really press, so that is what gets classified and shown.
                if (entry.pressed === entry) ScreenResult.Ok(element) else ScreenResult.Ok(numbered(tree, entry.pressed))
            }
            is ScreenTarget.ExactText -> {
                val matches = tree.entries.filter { it.element.text == target.text || it.element.description == target.text }
                val visible = matches.filter { !it.withheld }
                when {
                    visible.isEmpty() && matches.isNotEmpty() -> ScreenResult.Sensitive
                    visible.isEmpty() -> ScreenResult.NotFound("\"${target.text}\"")
                    else -> {
                        // A label and the button it sits in are one control.
                        val pressed = visible.map { it.pressed }.distinct()
                        if (pressed.size > 1) ScreenResult.Ambiguous(pressed.size) else ScreenResult.Ok(numbered(tree, pressed.single()))
                    }
                }
            }
        }
    }

    override suspend fun tap(agentName: String, element: ScreenElement): ScreenResult<Unit> = call(agentName, element.packageName) { open ->
        val entry = when (val found = refind(readTree(open), element)) {
            is Refound.Refused -> return@call found.result
            is Refound.Found -> found.entry
        }
        if (!entry.element.clickable) {
            val around = entry.clickTarget
            return@call ScreenResult.NotSupported(
                if (around != null) {
                    "That element is not a control of its own; a tap would press ${ScreenPreview.nameOf(around.element)} around it. " +
                        "Find the element again and tap the control it returns."
                } else {
                    "That element cannot be tapped."
                },
            )
        }
        if (!entry.element.enabled) return@call ScreenResult.NotSupported("That control is switched off (disabled) right now.")
        act(open, entry, "The app did not accept the tap.") { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    override suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit> =
        call(agentName, element.packageName) { open ->
            var entry = when (val found = refind(readTree(open), element)) {
                is Refound.Refused -> return@call found.result
                is Refound.Found -> found.entry
            }
            var focusedFirst = false
            if (!entry.element.editable) return@call ScreenResult.NotSupported("That element does not take text.")
            if (!entry.element.enabled) return@call ScreenResult.NotSupported("That field is switched off (disabled) right now.")
            if (!entry.node.offers(AccessibilityNodeInfo.ACTION_SET_TEXT)) {
                return@call ScreenResult.NotSupported("That field does not accept text from the operator.")
            }
            val imeEnter = imeEnterAction()
            if (submit && (sdkInt < Build.VERSION_CODES.R || imeEnter == null)) {
                return@call ScreenResult.NotSupported("Pressing the keyboard's action key needs Android 11 or later.")
            }
            if (submit && !entry.node.offers(imeEnter!!)) {
                // Android's text fields offer their keyboard action only while focused: focus the field, then look again.
                if (entry.element.focused || !entry.node.offers(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    return@call ScreenResult.NotSupported(NO_KEYBOARD_ACTION)
                }
                val focused = act(open, entry, "The field could not be focused.") { it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                if (focused !is ScreenResult.Ok) return@call focused
                focusedFirst = true
                entry = focused(open, entry, element, imeEnter) ?: return@call ScreenResult.NotSupported(NO_KEYBOARD_ACTION)
            }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            // After this call's own focus the field may say other things about itself (its label), but its row must still be the one that was read.
            val typed = act(open, entry, "The app did not accept the text.", if (focusedFirst) Recheck.CONTEXT else Recheck.ROW) {
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            if (typed !is ScreenResult.Ok || !submit) return@call typed
            // Stop, a window change or a row re-bound between the two steps leaves the text unsent. The field now holds the new text.
            act(open, entry, "The text was entered, but the field's keyboard action did not run.", Recheck.CONTEXT_BUT_TEXT) { it.performAction(imeEnter!!) }
        }

    override suspend fun scroll(agentName: String, packageName: String, direction: ScrollDirection, element: ScreenElement?): ScreenResult<Unit> =
        call(agentName, packageName) { open ->
            if (element != null && element.packageName != packageName) return@call ScreenResult.WrongWindow(packageName, element.packageName)
            val tree = readTree(open)
            val target = if (element != null) {
                val entry = when (val found = refind(tree, element)) {
                    is Refound.Refused -> return@call found.result
                    is Refound.Found -> found.entry
                }
                entry.scrollTarget ?: return@call ScreenResult.NotSupported("That element does not scroll, and nothing around it does.")
            } else {
                tree.visible.firstOrNull { it.element.scrollable } ?: return@call ScreenResult.NotFound("anything to scroll")
            }
            val action = scrollAction(target.node, direction)
                ?: return@call ScreenResult.NotSupported("That element cannot scroll ${direction.name.lowercase()}.")
            // What a scrolling area's label is made of is the content that scrolling moves: its row and ancestors decide, not its label.
            act(open, target, "It did not scroll; it may already be at the end.", Recheck.CONTEXT) { it.performAction(action) }
        }

    override suspend fun globalAction(agentName: String, fromPackage: String, action: GlobalAction): ScreenResult<Unit> =
        call(agentName, fromPackage) { open ->
            val code = when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            }
            currentCoroutineContext().ensureActive()
            stillInFront(open)?.let { return@call it }
            if (open.host.performGlobalAction(code)) ScreenResult.Ok(Unit) else ScreenResult.Failed("Android did not perform ${action.name}.")
        }

    override suspend fun waitFor(agentName: String, packageName: String, text: String, timeoutMs: Long): ScreenResult<ScreenElement> =
        call(agentName, packageName) { first ->
            if (text.isEmpty()) return@call ScreenResult.NotSupported("Give the text to wait for.")
            var open = first
            val outcome = withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                var result: ScreenResult<ScreenElement>? = null
                while (result == null) {
                    val seen = link.changes.value
                    val tree = readTree(open)
                    val hit = tree.visible.firstOrNull { it.element.text?.contains(text) == true || it.element.description?.contains(text) == true }
                    if (hit != null) {
                        result = ScreenResult.Ok(checkNotNull(snapshots.record(tree).elementOf(hit)))
                    } else {
                        // Wake on the next accessibility event, or look again after a while anyway.
                        withTimeoutOrNull(pollMs) { link.changes.first { it != seen } }
                        when (val gate = gate(packageName)) {
                            is Gate.Shut -> result = gate.refusal
                            is Gate.Open -> open = gate.passed
                        }
                    }
                }
                result
            }
            outcome ?: ScreenResult.NotFound("\"$text\" did not appear within $timeoutMs ms")
        }

    override suspend fun screenshot(agentName: String, packageName: String, target: WorkspaceFile): ScreenResult<ScreenshotInfo> =
        call(agentName, packageName) { open ->
            if (sdkInt < Build.VERSION_CODES.R) return@call ScreenResult.NotSupported("Screenshots need Android 11 or later.")
            if (target.file.exists()) return@call ScreenResult.Failed("${target.path} already exists.")
            val picture = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                capture { open.host.captureWindow(open.front.id) }
            } else {
                captureCropped(open)
            }
            val bitmap = when (picture) {
                is Picture.Refused -> return@call picture.result
                is Picture.Taken -> picture.bitmap
            }
            stillInFront(open)?.let { return@call it }
            val size = try {
                withContext(io) { Screenshots.writeNewPng(bitmap, target.file) }
            } catch (_: FileAlreadyExistsException) {
                return@call ScreenResult.Failed("${target.path} already exists.")
            } catch (e: IOException) {
                return@call ScreenResult.Failed("The screenshot could not be saved (${e.message ?: e.javaClass.simpleName}).")
            }
            ScreenResult.Ok(ScreenshotInfo(bitmap.width, bitmap.height, size))
        }

    override fun endSession(agentName: String) {
        synchronized(sessionLock) {
            val session = sessions[agentName] ?: return
            if (session.calls > 0 || session.waitingForOwner) {
                // Never hide the banner while this agent's call is still operating or one of its actions waits for the
                // owner (another run's end, Phase 6 D4); end when the last of them is over.
                session.ending = true
                return
            }
            session.idle?.cancel()
            sessions.remove(agentName)
        }
        applyIndicator()
    }

    /**
     * Keeps [agentName]'s session, with the banner "<agent> waits for your answer to act in <app>",
     * while its screen action [holdId] waits for the owner (Phase 6, D4, S22; rule 12): neither
     * [endSession] (of any run of that agent) nor the idle time-out ends a session with an open
     * hold. The same id is the same hold. Stop ends every hold. Nothing here suspends.
     */
    override fun holdSession(holdId: String, agentName: String, packageName: String) {
        var labelKnown = true
        synchronized(sessionLock) {
            if (stopSwitch.stopped.value || holdId in holdOwners) return
            val session = sessions.getOrPut(agentName) {
                labelKnown = false
                Session(packageName)
            }
            session.holds += holdId
            holdOwners[holdId] = agentName
            session.idle?.cancel()
            session.idle = null
        }
        // A session that starts with the hold names the app as the owner authorised it, once that is read.
        if (!labelKnown) {
            scope.launch(io) {
                val label = apps.get(packageName)?.label?.takeIf { it.isNotBlank() } ?: return@launch
                synchronized(sessionLock) { sessions[agentName]?.takeIf { it.appLabel == packageName }?.appLabel = label }
                applyIndicator()
            }
        }
        applyIndicator()
    }

    /** Ends the hold [holdId]; the session then ends as usual (idle time-out, or at once when its agent's run ended meanwhile). */
    override fun releaseSession(holdId: String) {
        synchronized(sessionLock) {
            val agentName = holdOwners.remove(holdId) ?: return
            val session = sessions[agentName] ?: return
            session.holds -= holdId
            if (session.waitingForOwner || session.calls > 0) return@synchronized
            if (session.ending) {
                sessions.remove(agentName)
            } else {
                session.idle?.cancel()
                session.idle = scope.launch {
                    delay(ScreenOperator.SESSION_IDLE_MS)
                    expire(agentName, session)
                }
            }
        }
        // Shown afresh: the session either ended or no longer waits for the owner.
        applyIndicator()
    }

    // ---- Calls and checks ----

    /** What the checks found: the window in front, its root, and the authorised app. */
    private class Passed(val host: ScreenHost, val front: Front, val root: AccessibilityNodeInfo, val app: OperatorApp) {
        val packageName: String get() = app.packageName
    }

    private sealed interface Gate {
        class Shut(val refusal: ScreenResult<Nothing>) : Gate

        class Open(val passed: Passed) : Gate
    }

    /** The window in front, read once. */
    private class Front(val id: Int, val type: Int, val root: AccessibilityNodeInfo?, val packageName: String?, val title: String?)

    private sealed interface Refound {
        class Found(val entry: ScreenEntry) : Refound

        class Refused(val result: ScreenResult<Nothing>) : Refound
    }

    /** Sealed outcome of taking a picture. */
    private sealed interface Picture {
        class Taken(val bitmap: Bitmap) : Picture

        class Refused(val result: ScreenResult<Nothing>) : Picture
    }

    private class Session(var appLabel: String) {
        var calls = 0
        var idle: Job? = null
        var ending = false

        /** The holds of calls that wait for the owner to act in the app (Phase 6, D4): the session stays while any is open. */
        val holds = LinkedHashSet<String>()

        /** True while one of the agent's screen actions waits for the owner's answer. */
        val waitingForOwner: Boolean get() = holds.isNotEmpty()
    }

    /** Which agent's session each open hold keeps ([holdSession]); guarded by [sessionLock]. */
    private val holdOwners = HashMap<String, String>()

    /**
     * Runs [block] as the one call in progress, after the checks passed. Stop cancels it and it
     * returns [ScreenResult.Stopped]; cancelling the caller still cancels it the usual way.
     */
    private suspend fun <T> call(agentName: String?, packageName: String, block: suspend (Passed) -> ScreenResult<T>): ScreenResult<T> {
        start()
        stopSwitch.awaitLoaded()
        return callLock.withLock {
            coroutineScope {
                val work = async(io, start = CoroutineStart.LAZY) {
                    when (val gate = gate(packageName)) {
                        is Gate.Shut -> gate.refusal
                        is Gate.Open -> {
                            agentName?.let { beginCall(it, gate.passed.app.label) }
                            try {
                                block(gate.passed)
                            } finally {
                                agentName?.let(::endCall)
                            }
                        }
                    }
                }
                // Registered before it starts, so a Stop from now on cancels it; an earlier Stop is seen by its checks.
                activeCall.set(work)
                try {
                    work.await()
                } catch (e: CancellationException) {
                    // The caller's own cancellation is passed on; otherwise it was the owner's Stop.
                    currentCoroutineContext().ensureActive()
                    ScreenResult.Stopped
                } finally {
                    activeCall.compareAndSet(work, null)
                }
            }
        }
    }

    /** The contract's checks, in its order. Nothing of the window is read before the app is known to be authorised. */
    private suspend fun gate(packageName: String): Gate {
        val host = link.host.value
        if (host == null) {
            val state = refreshState()
            return Gate.Shut(ScreenResult.Unavailable(if (state == ScreenServiceState.READY) ScreenServiceState.STARTING else state))
        }
        if (stateFlow.value != ScreenServiceState.READY) refreshState()
        if (stopSwitch.stopped.value) return Gate.Shut(ScreenResult.Stopped)
        if (protectedPackages().isProtected(packageName)) return Gate.Shut(ScreenResult.Protected(packageName))
        val app = authorised(packageName) ?: return Gate.Shut(ScreenResult.NotAuthorised(packageName))
        // Phase 6 (rule 12, S15): nothing is read or done while the screen is off; the agent waits until the owner uses the phone.
        if (!device.screenOn()) return Gate.Shut(ScreenResult.NotSupported(SCREEN_OFF))
        if (device.keyguardLocked()) return Gate.Shut(ScreenResult.WrongWindow(packageName, null))
        val front = front(host) ?: return Gate.Shut(ScreenResult.WrongWindow(packageName, null))
        val root = front.root
        if (front.packageName != packageName || front.type != AccessibilityWindowInfo.TYPE_APPLICATION || root == null) {
            return Gate.Shut(ScreenResult.WrongWindow(packageName, front.packageName))
        }
        return Gate.Open(Passed(host, front, root, app))
    }

    /** The app's authorisation now: listed, with the recorded signing certificate still the installed one. */
    private suspend fun authorised(packageName: String): OperatorApp? {
        val app = apps.get(packageName) ?: return null
        val recorded = app.signingCertSha256 ?: return null
        val installed = device.signingCertSha256(packageName) ?: return null
        return app.takeIf { recorded.equals(installed, ignoreCase = true) }
    }

    /** The active window (else the focused one). */
    private fun front(host: ScreenHost): Front? {
        val windows = host.windows()
        val window = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused } ?: return null
        val root = window.root
        return Front(window.id, window.type, root, root?.packageName?.toString(), window.title?.toString())
    }

    /** Null while Stop is not pressed and the same window of the same app is still in front with no lock screen; else why not. */
    private fun stillInFront(open: Passed): ScreenResult<Nothing>? {
        if (stopSwitch.stopped.value) return ScreenResult.Stopped
        if (!device.screenOn()) return ScreenResult.NotSupported(SCREEN_OFF)
        if (device.keyguardLocked()) return ScreenResult.WrongWindow(open.packageName, null)
        val now = front(open.host)
        if (now == null || now.id != open.front.id || now.packageName != open.packageName || now.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            return ScreenResult.WrongWindow(open.packageName, now?.packageName)
        }
        return null
    }

    /** [wholeWindow]: go on past the node limit to fingerprint the rest of the window ([ScreenReader.read]). */
    private suspend fun readTree(open: Passed, wholeWindow: Boolean = false): ScreenTree {
        val job = currentCoroutineContext().job
        return reader.read(open.root, open.packageName, open.front.title, wholeWindow) { job.ensureActive() }
    }

    /** The one current control that is [element]; [ScreenResult.Stale] for none or several. */
    private fun refind(tree: ScreenTree, element: ScreenElement): Refound {
        val matches = tree.matching(element)
        if (matches.size != 1) return Refound.Refused(ScreenResult.Stale)
        val entry = matches.single()
        if (entry.withheld) return Refound.Refused(ScreenResult.Sensitive)
        return Refound.Found(entry)
    }

    /** [entry] as numbered in a new snapshot of [tree], so a model can refer to it later. */
    private fun numbered(tree: ScreenTree, entry: ScreenEntry): ScreenElement = checkNotNull(snapshots.record(tree).elementOf(entry))

    /** How much of a control is asked again from the app right before a node action, and compared with what was read. */
    private enum class Recheck {
        /** The node, everything its label is made of, and the row or ancestors its context came from: a tap, and the first step of typing. */
        ROW,

        /**
         * The node and the row or ancestors its context came from, not its label: a scroll (the label of a scrolling area is
         * the content that scrolling moves), and typing right after this call focused the field (a focused field may
         * report other things about itself).
         */
        CONTEXT,

        /** As [CONTEXT], and not the node's text either: the keyboard action after this call typed into the field. */
        CONTEXT_BUT_TEXT,
    }

    /**
     * Re-checks what could have changed since the tree was read (Stop, the window in front, the
     * node itself and the row around it), then runs [action] on the node. The node is refreshed
     * from the app, and must still be the control that was matched: a list that re-bound the view
     * to another item in the meantime ([ScreenReader.sameIdentity]) is [ScreenResult.Stale]. The
     * row around it is asked again too ([ScreenReader.reread]), before every step: with
     * [Recheck.ROW] the control must be [ScreenElement.sameControlAs] the one matched, context
     * and label included, so a row re-bound to another item whose button still says "Delete" is
     * [ScreenResult.Stale] as well; with the other two its context must be the one that was read,
     * so a field whose row was re-bound to another chat between the steps of one type call is not
     * typed into, and what was typed is not sent.
     */
    private suspend fun act(
        open: Passed,
        entry: ScreenEntry,
        failure: String,
        recheck: Recheck = Recheck.ROW,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): ScreenResult<Unit> {
        val job = currentCoroutineContext().job
        job.ensureActive()
        stillInFront(open)?.let { return it }
        val node = entry.node
        if (!node.refresh()) return ScreenResult.Stale
        if (ScreenReader.isWithheld(node)) return ScreenResult.Sensitive
        if (!ScreenReader.sameIdentity(node, entry.element, compareText = recheck != Recheck.CONTEXT_BUT_TEXT)) return ScreenResult.Stale
        // With the window's title as it is now: an app can show another page in the same window.
        val title = front(open.host)?.takeIf { it.id == open.front.id }?.title
        val now = reader.reread(entry, open.packageName, title, withLabel = recheck == Recheck.ROW) { job.ensureActive() }
        // Asking took a moment: Stop and the window are looked at once more, right before the action.
        stillInFront(open)?.let { return it }
        val same = now != null && if (recheck == Recheck.ROW) now.sameControlAs(entry.element) else now.context == entry.element.context
        if (!same) return ScreenResult.Stale
        return if (action(node)) ScreenResult.Ok(Unit) else ScreenResult.Failed(failure)
    }

    /**
     * [entry], the field this call just focused, once it offers [actionId]; null when it does not. Its own node is asked
     * again from the app first, for at most [FOCUS_SETTLE_MS]: on a phone a new read of the tree is served from Android's
     * node cache, which still shows the field as it was before the focus (for a second or so), while the node itself,
     * refreshed, has the new actions at once. Only if that fails is the field looked up in the tree again.
     */
    private suspend fun focused(open: Passed, entry: ScreenEntry, element: ScreenElement, actionId: Int): ScreenEntry? {
        if (awaitOffer(entry.node, actionId)) return entry
        val again = (refind(readTree(open), element) as? Refound.Found)?.entry ?: return null
        return again.takeIf { it.node.offers(actionId) }
    }

    /**
     * Whether [node], asked again from the app, offers [actionId], now or within [FOCUS_SETTLE_MS]: a field reports its
     * keyboard action a moment after it was focused. Stop ends the wait (the call is cancelled).
     */
    private suspend fun awaitOffer(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        var waited = 0L
        while (true) {
            if (node.refresh() && node.offers(actionId)) return true
            if (waited >= FOCUS_SETTLE_MS) return false
            delay(FOCUS_POLL_MS)
            waited += FOCUS_POLL_MS
        }
    }

    private fun AccessibilityNodeInfo.offers(actionId: Int): Boolean = actionList.orEmpty().any { it.id == actionId }

    private fun imeEnterAction(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AccessibilityAction.ACTION_IME_ENTER.id else null

    /** The node action for [direction]; forward and backward stand in for down and up (or right and left in a row). */
    private fun scrollAction(node: AccessibilityNodeInfo, direction: ScrollDirection): Int? {
        val offered = node.actionList.orEmpty().map { it.id }.toSet()
        val exact = when (direction) {
            ScrollDirection.UP -> AccessibilityAction.ACTION_SCROLL_UP
            ScrollDirection.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            ScrollDirection.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            ScrollDirection.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }.id
        if (exact in offered) return exact
        val collection = node.collectionInfo
        val horizontal = collection != null && collection.columnCount > 1 && collection.rowCount <= 1
        val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
        if (vertical == horizontal) return null
        val fallback = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return fallback.takeIf { it in offered }
    }

    // ---- Screenshots ----

    private suspend fun capture(take: suspend () -> ScreenCapture): Picture {
        var result = take()
        if (result is ScreenCapture.Error && result.code == ERROR_INTERVAL_TIME_SHORT) {
            delay(SCREENSHOT_RETRY_MS)
            result = take()
        }
        return when (result) {
            is ScreenCapture.Image -> Picture.Taken(result.bitmap)
            is ScreenCapture.Error -> Picture.Refused(
                if (result.code == ERROR_SECURE_WINDOW) {
                    ScreenResult.NotSupported("The app does not allow screenshots of this window.")
                } else {
                    ScreenResult.Failed("Android could not take the screenshot (error ${result.code}).")
                },
            )
        }
    }

    /** Android 11–13: the display shot cut to the app's window, refused if anything else covers it. */
    private suspend fun captureCropped(open: Passed): Picture {
        val area = cropArea(open) ?: return Picture.Refused(ScreenResult.NotSupported(COVERED))
        val shot = when (val picture = capture { open.host.captureDisplay() }) {
            is Picture.Refused -> return picture
            is Picture.Taken -> picture.bitmap
        }
        // A pop-up or the shade may have appeared while the picture was taken.
        if (cropArea(open) != area) return Picture.Refused(ScreenResult.NotSupported(COVERED))
        val cropped = Screenshots.crop(shot, area) ?: return Picture.Refused(ScreenResult.Failed("The app's window is not on the screen."))
        if (Screenshots.looksBlank(cropped)) {
            return Picture.Refused(ScreenResult.NotSupported("The window came out blank: the app does not allow screenshots of it."))
        }
        return Picture.Taken(cropped)
    }

    private fun cropArea(open: Passed): Rect? {
        val windows = open.host.windows().map(WindowArea::of)
        val target = windows.firstOrNull { it.id == open.front.id } ?: return null
        return Screenshots.cropArea(target, windows)
    }

    // ---- Sessions and the banner ----

    private fun beginCall(agentName: String, appLabel: String) {
        synchronized(sessionLock) {
            val session = sessions.getOrPut(agentName) { Session(appLabel) }
            session.appLabel = appLabel
            session.calls++
            session.ending = false
            session.idle?.cancel()
            session.idle = null
        }
        applyIndicator()
    }

    private fun endCall(agentName: String) {
        var ended = false
        synchronized(sessionLock) {
            val session = sessions[agentName] ?: return
            session.calls = (session.calls - 1).coerceAtLeast(0)
            if (session.calls > 0 || session.waitingForOwner) return
            if (session.ending) {
                sessions.remove(agentName)
                ended = true
            } else {
                session.idle = scope.launch {
                    delay(ScreenOperator.SESSION_IDLE_MS)
                    expire(agentName, session)
                }
            }
        }
        if (ended) applyIndicator()
    }

    private fun expire(agentName: String, session: Session) {
        synchronized(sessionLock) {
            if (sessions[agentName] !== session || session.calls > 0 || session.waitingForOwner) return
            sessions.remove(agentName)
        }
        applyIndicator()
    }

    private fun onOwnerStop() {
        activeCall.get()?.cancel()
        synchronized(sessionLock) {
            sessions.values.forEach { it.idle?.cancel() }
            sessions.clear()
            // Stop ends every hold too (rule 12): the waiting actions are refused after any answer.
            holdOwners.clear()
        }
        applyIndicator()
    }

    /**
     * Shows the banner for the sessions as they are when it runs (so updates can never arrive out
     * of order). Never over the lock screen: the overlay would be drawn above it and name the
     * agent and the app to whoever holds the locked phone, while every call is refused anyway.
     */
    private fun applyIndicator() {
        scope.launch(main) {
            val host = link.host.value ?: return@launch
            val locked = device.keyguardLocked()
            lockedWhenApplied.set(locked)
            val text = synchronized(sessionLock) {
                sessions.entries.joinToString("\n") { (agent, session) ->
                    if (session.waitingForOwner) "${PreviewText.exact(agent)} waits for your answer to act in ${PreviewText.exact(session.appLabel)}"
                    else "${PreviewText.exact(agent)} is operating ${PreviewText.exact(session.appLabel)}"
                }
            }
            if (text.isEmpty() || locked) host.indicator.hide() else host.indicator.show(text) { stopSwitch.stop() }
        }
    }

    /** Timings and Android's screenshot error codes. */
    companion object {
        /** How often a wait looks again when no accessibility event arrives. */
        const val WAIT_POLL_MS = 500L

        /** How long a field that was just focused may take to offer its keyboard action. */
        const val FOCUS_SETTLE_MS = 500L

        /** How often the focused field is asked again within [FOCUS_SETTLE_MS]. */
        const val FOCUS_POLL_MS = 50L

        /** Android allows about three screenshots a second. */
        const val SCREENSHOT_RETRY_MS = 350L

        /** `AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (API 30). */
        private const val ERROR_INTERVAL_TIME_SHORT = 3

        /** `AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (API 34). */
        private const val ERROR_SECURE_WINDOW = 6

        private const val NO_KEYBOARD_ACTION =
            "That field offers no keyboard action to press, even when focused. Tap it first and type again, or tap the app's own send button."

        /** Rule 12 (S15): screen actions need a screen that is on. */
        const val SCREEN_OFF = "The phone's screen is off. Screen actions wait until you use the phone."

        private const val COVERED =
            "Another window covers part of the app (the notification shade, a pop-up or another app), so a screenshot of the app alone is not possible right now."
    }
}

/** The authorised apps, read only: what the screen operator re-reads on every call. Never authorising or withdrawing one. */
internal fun interface AuthorisedAppLookup {
    suspend fun get(packageName: String): OperatorApp?

    companion object {
        /** A lookup over [store] that exposes its reads only. */
        fun of(store: OperatorAppRepository): AuthorisedAppLookup = AuthorisedAppView(store)
    }
}

/** [AuthorisedAppLookup] over the store: a separate object, so the operator cannot reach the store's writes. */
internal class AuthorisedAppView(private val store: OperatorAppRepository) : AuthorisedAppLookup {
    override suspend fun get(packageName: String): OperatorApp? = store.get(packageName)
}
