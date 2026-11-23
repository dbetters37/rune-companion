package com.aicontrolcenter.device.operator

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/**
 * A private return address for one request to another component (PackageInstaller, Termux):
 * a broadcast [PendingIntent] that names this app's package and an action no one else knows,
 * received by a receiver registered here and not exported. Only that PendingIntent can reach
 * it, so no other app can forge an answer. The PendingIntent is mutable because the sender
 * adds its result as extras; mutability is safe here because the intent is explicit.
 *
 * Messages are kept in arrival order until [receive] takes them. [close] unregisters the
 * receiver and cancels the PendingIntent; answers that arrive later are dropped.
 */
internal class ResultMailbox(context: Context, purpose: String, oneShot: Boolean) : AutoCloseable {
    private val appContext: Context = context.applicationContext ?: context
    private val inbox = Channel<Intent>(Channel.UNLIMITED)

    /** The unique action; tests use it to find the receiver. */
    val action: String = "${appContext.packageName}.operator.$purpose.${UUID.randomUUID()}"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == action) inbox.trySend(intent)
        }
    }

    /** Give this to the other component. */
    val pendingIntent: PendingIntent

    init {
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val once = if (oneShot) PendingIntent.FLAG_ONE_SHOT else 0
        pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(action).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable or once,
        )
    }

    /** The next answer, waiting for it; cancellable. */
    suspend fun receive(): Intent = inbox.receive()

    override fun close() {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        pendingIntent.cancel()
        inbox.close()
    }
}
