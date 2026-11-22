package com.aicontrolcenter.device.launch

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.TransactionTooLargeException
import androidx.core.net.toUri
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.core.registry.LaunchResult.NotPossible
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.device.catalog.PromptLinkTemplate
import com.aicontrolcenter.device.platform.activitiesFor
import com.aicontrolcenter.device.platform.packageInfoOrNull
import com.aicontrolcenter.device.platform.startableBy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens registered AI apps and hands text to them, using only intents every app may send:
 * the package's launch intent, ACTION_SEND with text/plain, and ACTION_VIEW on the app's prompt
 * link. All of them open the other app's own UI; nothing the app answers comes back.
 *
 * Order of checks, each ending in a [NotPossible] with a sentence for the user rather than an
 * exception:
 *  1. the registry entry must permit the channel ([controlChannels]);
 *  2. the device is asked again, because the entry may be stale (app removed or changed since
 *     the last scan);
 *  3. this app must be allowed to start activities right now ([ActivityStartGate]).
 *
 * Every intent names the target package, so a hand-off can never land in a different app (for
 * example a browser picking up a prompt link). Text goes by share intent when the app accepts
 * one, which is the dependable route; the prompt link is used only when sharing is unavailable.
 */
class AndroidAiAppLauncher(
    context: Context,
    private val gate: ActivityStartGate = VisibleProcessGate,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AiAppLauncher {

    private val appContext: Context = context.applicationContext ?: context
    private val pm: PackageManager = appContext.packageManager

    override suspend fun launch(app: AiApp): LaunchResult {
        refusal(app, ControlChannel.LAUNCH)?.let { return it }
        return withContext(dispatcher) {
            val intent = pm.getLaunchIntentForPackage(app.packageName)
                ?: return@withContext if (isInstalled(app)) {
                    NotPossible("${app.displayName} has no screen that can be opened right now.")
                } else {
                    notInstalledAnyMore(app)
                }
            start(app, intent)
        }
    }

    override suspend fun handOffText(app: AiApp, text: String): LaunchResult {
        refusal(app, ControlChannel.INTENT_HANDOFF)?.let { return it }
        if (text.isBlank()) return NotPossible("There is no text to hand to ${app.displayName}.")
        return withContext(dispatcher) {
            if (!isInstalled(app)) return@withContext notInstalledAnyMore(app)
            val sharesText = AiAppCapability.SHARE_TEXT in app.capabilities
            val shareTargets = if (sharesText) shareTargets(app.packageName) else emptyList()
            val promptLink = app.promptDeepLink?.takeIf { AiAppCapability.PROMPT_DEEP_LINK in app.capabilities }
            when {
                shareTargets.isNotEmpty() -> share(app, text, shareTargets)
                promptLink != null -> openPromptLink(app, promptLink, text)
                sharesText -> NotPossible("${app.displayName} no longer accepts shared text. Scan for apps again to refresh it.")
                AiAppCapability.SHARE_IMAGE in app.capabilities ->
                    NotPossible("${app.displayName} accepts shared images but offers no way to receive text.")
                else -> NotPossible("No prompt link is stored for ${app.displayName}, so text cannot be handed to it.")
            }
        }
    }

    /** Why the registry entry itself forbids [channel], or null when it permits it. */
    private fun refusal(app: AiApp, channel: ControlChannel): NotPossible? {
        if (app.packageName == appContext.packageName) {
            return NotPossible("${app.displayName} is this app itself, so there is nothing to open or hand off.")
        }
        if (channel in app.controlChannels()) return null
        val name = app.displayName
        return NotPossible(
            when {
                !app.enabled -> "$name is switched off in the registry. Switch it on to use it."
                app.status == AiAppStatus.NOT_INSTALLED -> "$name is not installed on this device."
                app.status == AiAppStatus.DISABLED_BY_SYSTEM -> "$name is disabled in Android settings."
                channel == ControlChannel.LAUNCH -> "$name has no launcher screen to open."
                else -> "$name does not accept text from other apps."
            },
        )
    }

    private fun share(app: AiApp, text: String, targets: List<ComponentName>): LaunchResult {
        if (text.length > MAX_SHARED_TEXT_CHARS) {
            return NotPossible(
                "The text is too long to hand over in one go (${text.length} characters; the limit is " +
                    "$MAX_SHARED_TEXT_CHARS). Shorten it or split it up.",
            )
        }
        val intent = shareIntent().putExtra(Intent.EXTRA_TEXT, text)
        // One target: address it directly. Several: leave the choice to Android's chooser, which
        // setPackage limits to this app's own entry points.
        val onlyTarget = targets.singleOrNull()
        if (onlyTarget != null) intent.setComponent(onlyTarget) else intent.setPackage(app.packageName)
        return start(app, intent)
    }

    private fun openPromptLink(app: AiApp, template: String, text: String): LaunchResult {
        if (!PromptLinkTemplate.isUsable(template)) {
            return NotPossible("The prompt link stored for ${app.displayName} is not a valid https link.")
        }
        val url = PromptLinkTemplate.fill(template, text)
        if (url.length > MAX_PROMPT_LINK_CHARS) {
            return NotPossible(
                "The text is too long to pass to ${app.displayName} in a link " +
                    "(the link may be at most $MAX_PROMPT_LINK_CHARS characters). Shorten it.",
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(app.packageName)
        if (pm.activitiesFor(intent, PackageManager.MATCH_DEFAULT_ONLY.toLong()).startableBy(pm, appContext.packageName).isEmpty()) {
            return NotPossible("${app.displayName} does not open its prompt link on this device.")
        }
        return start(app, intent)
    }

    private fun start(app: AiApp, intent: Intent): LaunchResult {
        if (!gate.mayStartActivities()) {
            return NotPossible(
                "Android only lets an app that is on screen open another app. " +
                    "Bring this app to the front and try again.",
            )
        }
        return try {
            // Required because the start comes from the application context, not an activity.
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LaunchResult.Started
        } catch (_: ActivityNotFoundException) {
            NotPossible("Android found no screen in ${app.displayName} for this request. Scan for apps again to refresh it.")
        } catch (_: SecurityException) {
            NotPossible("Android did not allow this app to open ${app.displayName}.")
        } catch (e: RuntimeException) {
            // startActivity is a call into the system server. Whatever else it rejects (a binder
            // overflow arrives wrapped in a bare RuntimeException) means the hand-off did not
            // happen, which is an answer for the user and not a reason to crash.
            if (e.cause is TransactionTooLargeException) {
                NotPossible("The text is too large for Android to pass to ${app.displayName}. Shorten it.")
            } else {
                NotPossible("Android could not open ${app.displayName} (${e.javaClass.simpleName}).")
            }
        }
    }

    private fun shareIntent(): Intent = Intent(Intent.ACTION_SEND).setType("text/plain")

    private fun shareTargets(packageName: String): List<ComponentName> =
        pm.activitiesFor(shareIntent().setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY.toLong())
            .startableBy(pm, appContext.packageName)
            .map { ComponentName(it.packageName, it.name) }

    private fun isInstalled(app: AiApp): Boolean = pm.packageInfoOrNull(app.packageName) != null

    private fun notInstalledAnyMore(app: AiApp) =
        NotPossible("${app.displayName} is no longer installed. Scan for apps again to refresh the registry.")

    private val AiApp.displayName: String get() = label.ifBlank { packageName }

    companion object {
        /**
         * Intent extras travel through a binder buffer of about 1 MB that is shared with every
         * other call in flight, and a Java string costs two bytes per character. 100 000
         * characters (200 KB) leaves a wide margin.
         */
        const val MAX_SHARED_TEXT_CHARS = 100_000

        /** Upper bound for a filled prompt link; long URLs are truncated or rejected by receivers. */
        const val MAX_PROMPT_LINK_CHARS = 8_000
    }
}
