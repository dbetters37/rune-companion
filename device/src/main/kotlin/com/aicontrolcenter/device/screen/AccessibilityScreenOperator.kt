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
 * the node action, and never touches password fields or views marked sensitive.
 *
 * Stop ([StopSwitch]) cancels the call in progress, which returns [ScreenResult.Stopped], ends
 * every session and hides the banner. A session (the banner "<agent> is operating <app> · Stop")
 * starts with an agent's first call that passes the checks and lasts until [endSession], Stop,
 * or [ScreenOperator.SESSION_IDLE_MS] after its last call.
 */
internal class AccessibilityScreenOperator(
    private val apps: OperatorAppRepository,
    private val protectedPackages: ProtectedPackages,
    private val stopSwitch: StopSwitch,
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
    private val stateFlow = MutableStateFlow(ScreenServiceState.NOT_ENABLED)
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

    init {
        stopSwitch.onStop(::onOwnerStop)
        scope.launch {
            link.host.collect {
                if (started.get()) refreshState()
                applyIndicator()
            }
        }
    }

    /** Reads Android's settings again; the Screen control screen calls it when it comes back into view. */
    fun refreshState(): ScreenServiceState {
        val now = screenState(link.host.value != null, availability, sdkInt)
        stateFlow.value = now
        return now
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return
        refreshState()
        availability.watch { refreshState() }
    }

    override suspend fun activeWindow(): ActiveWindow? = withContext(io) {
        start()
        val host = link.host.value ?: return@withContext null
        val front = front(host) ?: return@withContext null
        val packageName = front.packageName ?: return@withContext null
        val titled = !protectedPackages.isProtected(packageName) && !device.keyguardLocked() && authorised(packageName) != null
        ActiveWindow(packageName, if (titled) ScreenReader.clean(front.title) else null)
    }

    override suspend fun launcherPackage(): String? = withContext(io) { device.launcherPackage() }

    override suspend fun read(agentName: String, packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> =
        call(agentName, packageName) { open ->
            ScreenResult.Ok(snapshots.record(readTree(open), maxElements).snapshot)
        }

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
            val entry = when (val found = refind(readTree(open), element)) {
                is Refound.Refused -> return@call found.result
                is Refound.Found -> found.entry
            }
            val node = entry.node
            if (!entry.element.editable) return@call ScreenResult.NotSupported("That element does not take text.")
            if (!entry.element.enabled) return@call ScreenResult.NotSupported("That field is switched off (disabled) right now.")
            if (!node.offers(AccessibilityNodeInfo.ACTION_SET_TEXT)) {
                return@call ScreenResult.NotSupported("That field does not accept text from the operator.")
            }
            val imeEnter = imeEnterAction()
            if (submit && (sdkInt < Build.VERSION_CODES.R || imeEnter == null)) {
                return@call ScreenResult.NotSupported("Pressing the keyboard's action key needs Android 11 or later.")
            }
            if (submit && !node.offers(imeEnter!!)) return@call ScreenResult.NotSupported("That field has no keyboard action to press.")
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val typed = act(open, entry, "The app did not accept the text.") { it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
            if (typed !is ScreenResult.Ok || !submit) return@call typed
            // Stop or a window change between the two steps leaves the text unsent.
            act(open, entry, "The text was entered, but the field's keyboard action did not run.") { it.performAction(imeEnter!!) }
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
            act(open, target, "It did not scroll; it may already be at the end.") { it.performAction(action) }
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
            if (session.calls > 0) {
                // Never hide the banner while this agent's call is still operating; end when it returns.
                session.ending = true
                return
            }
            session.idle?.cancel()
            sessions.remove(agentName)
        }
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
    }

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
        if (protectedPackages.isProtected(packageName)) return Gate.Shut(ScreenResult.Protected(packageName))
        val app = authorised(packageName) ?: return Gate.Shut(ScreenResult.NotAuthorised(packageName))
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
        if (device.keyguardLocked()) return ScreenResult.WrongWindow(open.packageName, null)
        val now = front(open.host)
        if (now == null || now.id != open.front.id || now.packageName != open.packageName || now.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            return ScreenResult.WrongWindow(open.packageName, now?.packageName)
        }
        return null
    }

    private suspend fun readTree(open: Passed): ScreenTree {
        val job = currentCoroutineContext().job
        return reader.read(open.root, open.packageName, open.front.title) { job.ensureActive() }
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

    /**
     * Re-checks what could have changed since the tree was read (Stop, the window in front, the
     * node itself), then runs [action] on the node.
     */
    private suspend fun act(open: Passed, entry: ScreenEntry, failure: String, action: (AccessibilityNodeInfo) -> Boolean): ScreenResult<Unit> {
        currentCoroutineContext().ensureActive()
        stillInFront(open)?.let { return it }
        val node = entry.node
        if (!node.refresh()) return ScreenResult.Stale
        if (ScreenReader.isWithheld(node)) return ScreenResult.Sensitive
        return if (action(node)) ScreenResult.Ok(Unit) else ScreenResult.Failed(failure)
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
            if (session.calls > 0) return
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
            if (sessions[agentName] !== session || session.calls > 0) return
            sessions.remove(agentName)
        }
        applyIndicator()
    }

    private fun onOwnerStop() {
        activeCall.get()?.cancel()
        synchronized(sessionLock) {
            sessions.values.forEach { it.idle?.cancel() }
            sessions.clear()
        }
        applyIndicator()
    }

    /** Shows the banner for the sessions as they are when it runs (so updates can never arrive out of order). */
    private fun applyIndicator() {
        scope.launch(main) {
            val host = link.host.value ?: return@launch
            val text = synchronized(sessionLock) {
                sessions.entries.joinToString("\n") { (agent, session) ->
                    "${PreviewText.exact(agent)} is operating ${PreviewText.exact(session.appLabel)}"
                }
            }
            if (text.isEmpty()) host.indicator.hide() else host.indicator.show(text) { stopSwitch.stop() }
        }
    }

    /** Timings and Android's screenshot error codes. */
    companion object {
        /** How often a wait looks again when no accessibility event arrives. */
        const val WAIT_POLL_MS = 500L

        /** Android allows about three screenshots a second. */
        const val SCREENSHOT_RETRY_MS = 350L

        /** `AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (API 30). */
        private const val ERROR_INTERVAL_TIME_SHORT = 3

        /** `AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (API 34). */
        private const val ERROR_SECURE_WINDOW = 6

        private const val COVERED =
            "Another window covers part of the app (the notification shade, a pop-up or another app), so a screenshot of the app alone is not possible right now."
    }
}
