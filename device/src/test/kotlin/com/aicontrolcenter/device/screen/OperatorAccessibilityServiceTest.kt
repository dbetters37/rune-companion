package com.aicontrolcenter.device.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.CERT
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The real service: it connects to the operator, ticks on events, draws the banner and cleans up. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OperatorAccessibilityServiceTest {
    private val controller = Robolectric.buildService(OperatorAccessibilityService::class.java)
    private val service: OperatorAccessibilityService = controller.create().get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun disconnect() {
        service.onUnbind(Intent())
        scope.cancel()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the service hands itself to the operator when connected and is forgotten when unbound`() {
        assertNull(ServiceLink.process.host.value)
        service.onServiceConnected()
        val host = assertIs<ServiceScreenHost>(ServiceLink.process.host.value)

        val ticks = ServiceLink.process.changes.value
        service.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertEquals(ticks + 1, ServiceLink.process.changes.value)

        val window = TestWindow(NOTES, title = "Inbox") { button("Send") }.build()
        shadowOf(service).setWindows(listOf(window))
        assertEquals(1, host.windows().size)
        assertTrue(host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)

        service.onUnbind(Intent())
        assertNull(ServiceLink.process.host.value)
    }

    @Test
    fun `the operator reads and acts through the real service`() = runBlocking {
        service.onServiceConnected()
        val apps = InMemoryOperatorAppRepository()
        apps.upsert(OperatorApp(NOTES, "Notes", 1, CERT))
        val facts = FakeDeviceFacts().apply { certificates[NOTES] = CERT }
        val operator = AccessibilityScreenOperator(
            apps, ProtectedPackages("com.aicontrolcenter.app"), StopSwitch(InMemoryStopStore(), scope), ServiceLink.process,
            FakeAvailability(), facts, FakeClock(), scope, io = Dispatchers.Unconfined, main = Dispatchers.Main,
        )
        val notes = TestWindow(NOTES, title = "Inbox") { button("Send") }
        shadowOf(service).setWindows(listOf(notes.build()))

        val snapshot = assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read("Scout", NOTES)).value
        assertEquals(ScreenResult.Ok(Unit), operator.tap("Scout", snapshot.elements.single()))
        assertEquals(ScreenResult.Ok(Unit), operator.globalAction("Scout", NOTES, GlobalAction.BACK))
        assertEquals(listOf(Performed("Send", android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)), notes.log)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)

        idleMain()
        val host = assertIs<ServiceScreenHost>(ServiceLink.process.host.value)
        val banner = assertNotNull(host.indicator.view, "the banner shows while Scout operates")
        assertEquals("Scout is operating Notes", (banner as ViewGroup).getChildAt(0).let { (it as TextView).text.toString() })
        operator.endSession("Scout")
        idleMain()
        assertNull(host.indicator.view)
    }

    @Test
    fun `the banner is an accessibility overlay whose Stop button calls back, and leaves with the service`() {
        service.onServiceConnected()
        val host = assertIs<ServiceScreenHost>(ServiceLink.process.host.value)
        var stops = 0

        host.indicator.show("Scout is operating Notes") { stops++ }
        val banner = assertNotNull(host.indicator.view) as ViewGroup
        val params = banner.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, "the Stop button must be touchable")
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0, "it never takes the keyboard")
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width, "only the pill blocks touches")

        host.indicator.show("Scout is operating Notes\nWriter is operating Mail") { stops += 10 }
        assertSame(banner, host.indicator.view, "updated in place")
        assertEquals("Scout is operating Notes\nWriter is operating Mail", (banner.getChildAt(0) as TextView).text.toString())
        val stop = banner.getChildAt(1) as Button
        assertEquals("Stop", stop.text.toString())
        stop.performClick()
        assertEquals(10, stops)

        service.onUnbind(Intent())
        assertNull(host.indicator.view, "removed with the service")
        host.indicator.hide()
    }
}
