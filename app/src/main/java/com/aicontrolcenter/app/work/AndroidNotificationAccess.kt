package com.aicontrolcenter.app.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [NotificationAccess] backed by Android: the system's own answer (which covers both the
 * runtime permission and the owner switching the app's notifications off in settings), and a
 * one-line preference that remembers the owner was asked, so a refusal is never re-asked.
 */
class AndroidNotificationAccess(context: Context) : NotificationAccess {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(readEnabled())

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override val permission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    override fun shouldAsk(): Boolean = shouldAsk(KEY_ASKED)

    override fun markAsked() = markAsked(KEY_ASKED)

    override fun shouldAskForOperator(): Boolean = shouldAsk(KEY_ASKED_OPERATOR)

    override fun markAskedForOperator() = markAsked(KEY_ASKED_OPERATOR)

    private fun shouldAsk(key: String): Boolean {
        val needed = permission ?: return false
        val granted = ContextCompat.checkSelfPermission(appContext, needed) == PackageManager.PERMISSION_GRANTED
        return !granted && !preferences.getBoolean(key, false)
    }

    private fun markAsked(key: String) {
        preferences.edit().putBoolean(key, true).apply()
        refresh()
    }

    override fun refresh() {
        state.value = readEnabled()
    }

    private fun readEnabled(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    private companion object {
        const val PREFERENCES = "notifications"
        const val KEY_ASKED = "asked_for_objectives"

        /** Asked separately: an earlier "no" for objectives' progress is no answer about approvals. */
        const val KEY_ASKED_OPERATOR = "asked_for_operator"
    }
}
