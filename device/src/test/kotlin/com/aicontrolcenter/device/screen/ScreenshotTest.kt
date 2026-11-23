package com.aicontrolcenter.device.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenshotInfo
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.MAIL
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val workspace = TemporaryFolder()

    private fun target(path: String = "shots/notes.png"): WorkspaceFile =
        assertIs<DocResult.Ok<WorkspaceFile>>(WorkspaceFile.resolve(workspace.root, RelativePath.of(path))).value

    private fun picture(width: Int, height: Int, color: Int = Color.rgb(30, 120, 200)): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private val statusBar = systemWindow(id = 2, bounds = Rect(0, 0, 1080, 100))
    private val navigationBar = systemWindow(id = 3, bounds = Rect(0, 2300, 1080, 2400))
    private val banner = systemWindow(id = 4, bounds = Rect(300, 120, 780, 200), type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)

    @Test
    fun `Android 14 and later capture the app's window alone and write a new PNG`() = runTest {
        val h = OperatorHarness(this, sdkInt = 34)
        h.authorise()
        h.show(TestWindow(NOTES, id = 7), statusBar)
        h.host.windowCapture = { ScreenCapture.Image(picture(1080, 2400)) }
        val file = target()

        val info = assertIs<ScreenResult.Ok<ScreenshotInfo>>(h.operator.screenshot("Scout", NOTES, file)).value

        assertEquals(listOf("window 7"), h.host.captures)
        assertEquals(1080, info.widthPx)
        assertEquals(2400, info.heightPx)
        assertEquals(file.file.length(), info.sizeBytes)
        val decoded = BitmapFactory.decodeFile(file.file.path)
        assertEquals(1080 to 2400, decoded.width to decoded.height)
    }

    @Test
    fun `a screenshot never overwrites a file`() = runTest {
        val h = OperatorHarness(this, sdkInt = 34)
        h.authorise()
        h.show(TestWindow(NOTES))
        h.host.windowCapture = { ScreenCapture.Image(picture(10, 10)) }
        val file = target()
        file.file.parentFile!!.mkdirs()
        file.file.writeText("the owner's notes")

        assertEquals(ScreenResult.Failed("shots/notes.png already exists."), h.operator.screenshot("Scout", NOTES, file))
        assertEquals("the owner's notes", file.file.readText())
        assertTrue(h.host.captures.isEmpty(), "nothing captured")

        // A file that appears between the check and the write is not overwritten either.
        val late = target("late.png")
        h.host.windowCapture = {
            late.file.writeText("arrived first")
            ScreenCapture.Image(picture(10, 10))
        }
        assertEquals(ScreenResult.Failed("late.png already exists."), h.operator.screenshot("Scout", NOTES, late))
        assertEquals("arrived first", late.file.readText())
    }

    @Test
    fun `a window that forbids screenshots is refused, and a too-early request is retried once`() = runTest {
        val h = OperatorHarness(this, sdkInt = 35)
        h.authorise()
        h.show(TestWindow(NOTES))

        h.host.windowCapture = { ScreenCapture.Error(6) }
        assertEquals(
            ScreenResult.NotSupported("The app does not allow screenshots of this window."),
            h.operator.screenshot("Scout", NOTES, target("a.png")),
        )

        var attempts = 0
        h.host.windowCapture = { if (attempts++ == 0) ScreenCapture.Error(3) else ScreenCapture.Image(picture(10, 10)) }
        assertIs<ScreenResult.Ok<ScreenshotInfo>>(h.operator.screenshot("Scout", NOTES, target("b.png")))
        assertEquals(2, attempts)

        h.host.windowCapture = { ScreenCapture.Error(1) }
        assertIs<ScreenResult.Failed>(h.operator.screenshot("Scout", NOTES, target("c.png")))
        assertTrue(!target("a.png").file.exists() && !target("c.png").file.exists())
    }

    @Test
    fun `Android 11 to 13 cut the display shot to the window, without the system bars`() = runTest {
        val h = OperatorHarness(this, sdkInt = 31)
        h.authorise()
        h.show(TestWindow(NOTES, id = 7), statusBar, navigationBar, banner)
        h.host.displayCapture = {
            val shot = picture(1080, 2400)
            // The status bar's pixels, which must not be in the result.
            for (x in 0 until 1080) for (y in 0 until 100) shot.setPixel(x, y, Color.RED)
            ScreenCapture.Image(shot)
        }
        val file = target()

        val info = assertIs<ScreenResult.Ok<ScreenshotInfo>>(h.operator.screenshot("Scout", NOTES, file)).value

        assertEquals(listOf("display"), h.host.captures)
        assertEquals(1080 to 2200, info.widthPx to info.heightPx)
        val decoded = BitmapFactory.decodeFile(file.file.path)
        assertEquals(Color.rgb(30, 120, 200), decoded.getPixel(500, 0))
    }

    @Test
    fun `Android 11 to 13 refuse when another window covers the app, or the picture is blank`() = runTest {
        val h = OperatorHarness(this, sdkInt = 30)
        h.authorise()
        h.host.displayCapture = { ScreenCapture.Image(picture(1080, 2400)) }
        val popup = TestWindow(MAIL, id = 12, active = false, bounds = Rect(100, 800, 980, 1400))
        val shade = systemWindow(id = 13, bounds = Rect(0, 0, 1080, 2400))

        h.show(TestWindow(NOTES, id = 7), statusBar, popup)
        assertIs<ScreenResult.NotSupported>(h.operator.screenshot("Scout", NOTES, target("a.png")))
        h.show(TestWindow(NOTES, id = 7), shade)
        assertIs<ScreenResult.NotSupported>(h.operator.screenshot("Scout", NOTES, target("b.png")))
        assertTrue(h.host.captures.isEmpty())

        h.show(TestWindow(NOTES, id = 7), statusBar)
        h.host.displayCapture = { ScreenCapture.Image(picture(1080, 2400, Color.BLACK)) }
        assertEquals(
            ScreenResult.NotSupported("The window came out blank: the app does not allow screenshots of it."),
            h.operator.screenshot("Scout", NOTES, target("c.png")),
        )
        assertTrue(workspace.root.listFiles().orEmpty().none { it.name.endsWith(".png") })
    }

    @Test
    fun `before Android 11 there are no screenshots`() = runTest {
        val h = OperatorHarness(this, sdkInt = 29)
        h.authorise()
        h.show(TestWindow(NOTES))

        assertEquals(ScreenResult.NotSupported("Screenshots need Android 11 or later."), h.operator.screenshot("Scout", NOTES, target()))
        assertTrue(h.host.captures.isEmpty())
        assertEquals(ScreenResult.NotAuthorised(MAIL), h.operator.screenshot("Scout", MAIL, target()), "the checks still come first")
    }

    @Test
    fun `crop areas cut whole-edge bars and refuse anything else`() {
        val app = WindowArea(1, AccessibilityWindowInfo.TYPE_APPLICATION, Rect(0, 0, 1080, 2400))
        fun area(vararg others: WindowArea) = Screenshots.cropArea(app, listOf(app, *others))
        val system = AccessibilityWindowInfo.TYPE_SYSTEM

        assertEquals(Rect(0, 0, 1080, 2400), area())
        assertEquals(Rect(0, 100, 1080, 2400), area(WindowArea(2, system, Rect(0, 0, 1080, 100))))
        assertEquals(
            Rect(0, 100, 1000, 1600),
            area(
                WindowArea(2, system, Rect(0, 0, 1080, 100)),
                WindowArea(3, AccessibilityWindowInfo.TYPE_INPUT_METHOD, Rect(0, 1600, 1080, 2400)),
                WindowArea(4, system, Rect(1000, 0, 1080, 2400)),
            ),
            "status bar, keyboard and a side navigation bar",
        )
        assertEquals(Rect(0, 0, 1080, 2400), area(WindowArea(5, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, Rect(0, 0, 1080, 2400))))
        assertEquals(Rect(0, 0, 1080, 2400), area(WindowArea(6, system, Rect(0, 2500, 1080, 2600))), "off the window")
        assertNull(area(WindowArea(7, system, Rect(0, 0, 1080, 2400))), "the shade")
        assertNull(area(WindowArea(8, system, Rect(200, 0, 880, 300))), "a heads-up that does not span the window")
        assertNull(area(WindowArea(9, AccessibilityWindowInfo.TYPE_APPLICATION, Rect(0, 0, 1080, 100))), "another app, even along an edge")
    }
}
