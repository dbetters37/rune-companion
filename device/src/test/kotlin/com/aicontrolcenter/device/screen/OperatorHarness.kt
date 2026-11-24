package com.aicontrolcenter.device.screen

import android.view.accessibility.AccessibilityWindowInfo
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CopyOnWriteArrayList

/** The connected service as tests script it. */
internal class FakeScreenHost : ScreenHost {
    @Volatile var windows: List<AccessibilityWindowInfo> = emptyList()

    /** How many times anything asked which windows are on screen. */
    @Volatile var windowReads = 0

    val globalActions = CopyOnWriteArrayList<Int>()
    var globalResult = true
    var windowCapture: (Int) -> ScreenCapture = { ScreenCapture.Error(1) }
    var displayCapture: () -> ScreenCapture = { ScreenCapture.Error(1) }
    val captures = CopyOnWriteArrayList<String>()

    override val indicator = FakeIndicator()

    override fun windows(): List<AccessibilityWindowInfo> {
        windowReads++
        return windows
    }

    override fun performGlobalAction(action: Int): Boolean {
        globalActions += action
        return globalResult
    }

    override suspend fun captureWindow(windowId: Int): ScreenCapture {
        captures += "window $windowId"
        return windowCapture(windowId)
    }

    override suspend fun captureDisplay(): ScreenCapture {
        captures += "display"
        return displayCapture()
    }
}

/** Records what the banner showed. */
internal class FakeIndicator : OperatorIndicator {
    /** The text showing now; null when hidden. */
    @Volatile var text: String? = null
    var onStop: (() -> Unit)? = null

    /** Every change, in order: a text when shown, null when hidden. */
    val history = CopyOnWriteArrayList<String?>()

    override fun show(text: String, onStop: () -> Unit) {
        this.text = text
        this.onStop = onStop
        history += text
    }

    override fun hide() {
        if (text != null) history += null
        text = null
        onStop = null
    }
}

internal class FakeAvailability(
    @Volatile var enabled: Boolean = false,
    @Volatile var advancedProtection: Boolean = false,
    @Volatile var restricted: Boolean = false,
) : ScreenAvailability {
    var onChange: (() -> Unit)? = null

    /** How many times Android's settings were asked. */
    @Volatile var reads = 0

    override fun enabledInSettings(): Boolean {
        reads++
        return enabled
    }

    override fun advancedProtectionOn() = advancedProtection

    override fun restrictedSettingsLikely() = restricted

    override fun watch(onChange: () -> Unit) {
        this.onChange = onChange
    }
}

internal class FakeDeviceFacts : DeviceFacts {
    val certificates = HashMap<String, String>()
    @Volatile var locked = false
    var launcher: String? = "com.android.launcher3"

    override fun signingCertSha256(packageName: String): String? = certificates[packageName]

    override fun keyguardLocked() = locked

    override fun launcherPackage() = launcher
}

/** A [StopStore] in memory; [readGate] holds the start-up read back until completed. */
internal class InMemoryStopStore(@Volatile var value: Boolean = false) : StopStore {
    val readGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    val writes = CopyOnWriteArrayList<Boolean>()

    override suspend fun read(): Boolean {
        readGate.await()
        return value
    }

    override suspend fun write(stopped: Boolean) {
        value = stopped
        writes += stopped
    }
}

/**
 * An [AccessibilityScreenOperator] wired to fakes in virtual time: the service is connected, Notes
 * (`com.example.notes`) is authorised, and nothing is on screen until a test shows a window.
 */
internal class OperatorHarness(
    scope: TestScope,
    sdkInt: Int = 35,
    connected: Boolean = true,
    val store: InMemoryStopStore = InMemoryStopStore(),
    reader: ScreenReader = ScreenReader(),
) {
    val apps = InMemoryOperatorAppRepository()
    val host = FakeScreenHost()
    val link = ServiceLink().also { if (connected) it.connected(host) }
    val facts = FakeDeviceFacts()
    val availability = FakeAvailability()
    val clock = FakeClock(current = 1_800_000_000_000L)
    val switch = StopSwitch(store, scope.backgroundScope)
    val control = SwitchScreenControl(switch)
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val operator = AccessibilityScreenOperator(
        apps = AuthorisedAppLookup.of(apps),
        protectedPackages = ProtectedPackages(OWN, resolved = setOf(OEM_SETTINGS)).let { fixed -> { fixed } },
        stopSwitch = StopSignal(switch),
        link = link,
        availability = availability,
        device = facts,
        clock = clock,
        scope = scope.backgroundScope,
        io = dispatcher,
        main = dispatcher,
        sdkInt = sdkInt,
        reader = reader,
    )

    /** Authorises [packageName] as the owner would, recording [certificate], which is also what is installed. */
    suspend fun authorise(packageName: String = NOTES, label: String = "Notes", certificate: String = CERT) {
        apps.upsert(OperatorApp(packageName, label, authorisedAt = 1, signingCertSha256 = certificate))
        facts.certificates[packageName] = certificate
    }

    /** Puts [windows] on screen, in front first. */
    fun show(vararg windows: TestWindow) {
        host.windows = windows.map { it.build() }
    }

    companion object {
        const val OWN = "com.aicontrolcenter.app"
        const val NOTES = "com.example.notes"
        const val MAIL = "com.example.mail"
        const val OEM_SETTINGS = "com.oem.settings"
        val CERT = "ab".repeat(32)
    }
}
