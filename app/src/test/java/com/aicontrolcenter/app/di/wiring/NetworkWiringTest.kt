package com.aicontrolcenter.app.di.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.device.net.AndroidNetworkStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertIs

/** WP4 item 10: production follows Android's own network, so providers are re-checked when it comes back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NetworkWiringTest {
    @Test
    fun `the app's network status is Android's default network`() {
        assertIs<AndroidNetworkStatus>(ModelsWiring.network(ApplicationProvider.getApplicationContext<Application>()))
    }
}
