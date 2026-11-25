package com.aicontrolcenter.app.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The launcher icon, drawn from the real resources: the manifest names it, it is an adaptive icon
 * with a one-colour layer for themed icons, and nothing in its foreground or one-colour layer
 * reaches outside the 66dp safe zone that every launcher mask keeps. Recorded into
 * `build/outputs/roborazzi`: the full canvas, the icon in a circle as launchers show it, and the
 * themed version.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 4 pixels per dp of the 108dp canvas. */
    private val size = 432

    private fun icon(): AdaptiveIconDrawable = assertIs(context.getDrawable(R.mipmap.ic_launcher))

    private fun draw(drawable: Drawable, tint: Int? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        if (tint != null) drawable.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** What a launcher shows: the middle 72dp of the canvas, cut to a circle. */
    private fun inCircle(vararg layers: Bitmap): Bitmap {
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        layers.forEach { canvas.drawBitmap(it, 0f, 0f, null) }
        val visible = size * 72 / 108
        val offset = (size - visible) / 2
        val out = Bitmap.createBitmap(visible, visible, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(Bitmap.createBitmap(full, offset, offset, visible, visible), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawCircle(visible / 2f, visible / 2f, visible / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return out
    }

    private fun assertInsideSafeZone(layer: Bitmap, name: String) {
        val centre = size / 2.0
        val safeRadius = 33.0 * size / 108
        var drawn = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(layer.getPixel(x, y)) == 0) continue
            drawn++
            val distance = hypot(x + 0.5 - centre, y + 0.5 - centre)
            assertTrue(distance <= safeRadius + 1, "$name draws at ($x, $y), ${"%.1f".format(distance * 108 / size)}dp from the centre, outside the 33dp safe zone")
        }
        assertTrue(drawn > size * size / 20, "$name draws almost nothing ($drawn pixels)")
    }

    @Test
    fun `the manifest names the adaptive icon, round icon included`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
        assertIs<AdaptiveIconDrawable>(context.getDrawable(R.mipmap.ic_launcher_round))
    }

    @Test
    fun `the icon has a background, a foreground and a one-colour layer for themed icons`() {
        val icon = icon()
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
    }

    @Test
    fun `the foreground and the one-colour layer stay inside the safe zone`() {
        val icon = icon()
        assertInsideSafeZone(draw(icon.foreground), "The foreground")
        assertInsideSafeZone(draw(assertNotNull(icon.monochrome)), "The one-colour layer")
    }

    @Test
    fun `pictures of the icon`() {
        val icon = icon()
        val background = draw(icon.background)
        val foreground = draw(icon.foreground)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { full ->
            Canvas(full).apply {
                drawBitmap(background, 0f, 0f, null)
                drawBitmap(foreground, 0f, 0f, null)
            }
        }.captureRoboImage("build/outputs/roborazzi/launcher_icon_canvas.png")
        inCircle(background, foreground).captureRoboImage("build/outputs/roborazzi/launcher_icon_circle.png")
        // Themed icons: the launcher fills the background with a pale tint and draws the one-colour layer dark.
        val themedBackground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFD3E8E4.toInt()) }
        inCircle(themedBackground, draw(assertNotNull(icon.monochrome), tint = 0xFF0E3B36.toInt()))
            .captureRoboImage("build/outputs/roborazzi/launcher_icon_themed.png")
    }
}
