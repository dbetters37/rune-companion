package com.aicontrolcenter.device.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP4 item 3 (D28): the phone's network as Android reports it, so providers that stopped answering are checked again
 * as soon as a network is back. Robolectric's connectivity shadow at SDK 35.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidNetworkStatusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun callback(): ConnectivityManager.NetworkCallback = shadowOf(connectivity).networkCallbacks.single()

    @Test
    fun `it starts from whether Android has a default network, and follows it`() {
        val status = AndroidNetworkStatus(context)
        assertEquals(connectivity.activeNetwork != null, status.available.value)
        val wifi: Network = ShadowNetwork.newInstance(101)

        callback().onAvailable(wifi)
        assertTrue(status.available.value)
        callback().onLost(wifi)
        assertFalse(status.available.value, "no network left")
        callback().onAvailable(wifi)
        assertTrue(status.available.value, "the network is back")
    }

    @Test
    fun `while Android switches networks the phone is never shown as without one`() {
        val status = AndroidNetworkStatus(context)
        val wifi = ShadowNetwork.newInstance(101)
        val mobile = ShadowNetwork.newInstance(102)

        callback().onAvailable(wifi)
        callback().onAvailable(mobile)
        callback().onLost(wifi)

        assertTrue(status.available.value)
        callback().onLost(mobile)
        assertFalse(status.available.value)
    }

    @Test
    fun `without a network at the start it says so until one comes`() {
        shadowOf(connectivity).setDefaultNetworkActive(false)
        val status = AndroidNetworkStatus(context)

        assertFalse(status.available.value)
        callback().onAvailable(ShadowNetwork.newInstance(7))
        assertTrue(status.available.value)
    }

    @Test
    fun `it registers one callback for the life of the process`() {
        AndroidNetworkStatus(context)

        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
    }
}
