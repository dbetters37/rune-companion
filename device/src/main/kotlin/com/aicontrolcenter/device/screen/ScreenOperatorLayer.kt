package com.aicontrolcenter.device.screen

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The screen operator for the composition root. Create exactly one per process (it shares the
 * process-wide Stop and the one accessibility service).
 *
 * Hand [operator] to the screen tools and the broker (read-only `stopped`), and [control] only to
 * the owner's UI (Tools › Screen control, the banner, a notification's Stop): two separate
 * objects, so nothing holding the operator can reach Stop or Resume. Construction touches no
 * system service.
 *
 * @param operatorApps `operator_apps`, re-read on every call.
 * @param protectedPackages from [ProtectedPackagesResolver.startResolving] at start-up, read on
 *   every check; the same set the broker and the owner ports use.
 * @param scope lives as long as the process: session timers, the Stop file, state updates.
 */
class ScreenOperatorLayer(
    context: Context,
    operatorApps: OperatorAppRepository,
    protectedPackages: () -> ProtectedPackages,
    clock: Clock = WallClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** For a set known at once (tests). */
    constructor(context: Context, operatorApps: OperatorAppRepository, protectedPackages: ProtectedPackages) :
        this(context, operatorApps, { protectedPackages })

    private val appContext: Context = context.applicationContext ?: context
    private val switch = StopSwitch.process(appContext)
    private val impl = AccessibilityScreenOperator(
        // Read-only views: the operator can neither authorise an app nor resume after the owner's Stop.
        apps = AuthorisedAppLookup.of(operatorApps),
        protectedPackages = protectedPackages,
        stopSwitch = StopSignal(switch),
        link = ServiceLink.process,
        availability = AndroidScreenAvailability(appContext),
        device = AndroidDeviceFacts(appContext),
        clock = clock,
        scope = scope,
    )

    /** Agent-facing: reads and drives authorised apps' windows. */
    val operator: ScreenOperator = impl

    /** Owner-only: Stop and Resume, persisted across restarts. */
    val control: ScreenControl = SwitchScreenControl(switch)

    init {
        // The first read of Android's settings and PackageManager happens here, off the main thread, rather than on
        // whichever thread first looks at the state (often the main thread, for the Screen control screen).
        scope.launch(Dispatchers.IO) { impl.state }
    }

    /** Reads Android's settings again (Screen control calls it when it comes back into view: Android 9–12 send no change signal). */
    fun refreshState(): ScreenServiceState = impl.refreshState()

    /** [refreshState] off the caller's thread: the app calls it from the main thread, and the state updates when the read is done. */
    fun refreshStateInBackground() {
        scope.launch(Dispatchers.IO) { impl.refreshState() }
    }

    /** What the owner's Screen control screen needs to send them to Android's settings. */
    companion object {
        /** The service as Android's settings list it (also for `adb shell settings put secure enabled_accessibility_services …`). */
        fun serviceComponent(context: Context): ComponentName = OperatorAccessibilityService.componentName(context)

        /** Android's accessibility settings, where the owner switches the service on. */
        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** This app's info page, whose ⋮ menu has "Allow restricted settings" ([ScreenServiceState.RESTRICTED]). */
        fun appInfoIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** For the Stop action of a notification shown while agents operate the screen. */
        fun stopPendingIntent(context: Context): PendingIntent = ScreenStopReceiver.pendingIntent(context)
    }
}
