package com.aicontrolcenter.device.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.aicontrolcenter.core.health.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a network now, as Android reports it (Phase 6, WP4 item 3, D28): providers that stopped
 * answering are checked again at once when a network comes back.
 *
 * It follows Android's default network with `ConnectivityManager.registerDefaultNetworkCallback` (API 24; needs the
 * normal permission `ACCESS_NETWORK_STATE`, which the app declares): [available] is true from `onAvailable` until
 * `onLost`. A network the owner's own server sits on counts even when it has no internet, because providers on the
 * owner's network are re-checked too. Built once per process; the callback stays registered for the life of the
 * process. When Android refuses the registration (a missing permission, too many callbacks), [available] stays true,
 * which only means checks are not held back: it never stops anything from working.
 */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(true)
    private val lock = Any()

    /** The default networks Android reported and has not lost yet (usually one; two for a moment during a switch). */
    private val networks = HashSet<Network>()

    override val available: StateFlow<Boolean> = state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                networks += network
                state.value = true
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                networks -= network
                state.value = networks.isNotEmpty()
            }
        }
    }

    init {
        val manager = connectivity
        if (manager != null) {
            try {
                state.value = manager.activeNetwork != null
                manager.registerDefaultNetworkCallback(callback)
            } catch (e: RuntimeException) {
                // SecurityException without ACCESS_NETWORK_STATE, or too many callbacks: never hold checks back.
                state.value = true
            }
        }
    }
}
