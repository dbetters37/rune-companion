package com.aicontrolcenter.app.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.device.screen.ResolvingProtectedPackages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The production ports are built on the main thread, when the first screen asks for the graph.
 * Review 5, A7: the protected-app list (a dozen PackageManager queries and an autofill query) is
 * not resolved there. (The screen operator's own reads are covered in `:device`, whose layer only
 * offers a background re-read of Android's settings.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProductionPortsTest {
    @Test
    fun `the protected apps are resolved on their own thread, and every reader still gets the full set`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val ports = createProductionPorts(context, DataLayer(context))

        // Not a set resolved by the calling (main) thread: the resolver that works on its own thread.
        val protectedPackages = assertIs<ResolvingProtectedPackages>(ports.operator.protectedPackages)
        // A reader waits for the whole set rather than checking against less.
        assertTrue(protectedPackages().isProtected(context.packageName), "this app itself is never operated")
        assertTrue(protectedPackages().isProtected("com.android.settings"))
        assertTrue(protectedPackages.isResolved)
    }
}
