package com.aicontrolcenter.device.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** A window's place on screen, read once from [AccessibilityWindowInfo]. */
internal data class WindowArea(val id: Int, val type: Int, val bounds: Rect) {
    /** Reads it. */
    companion object {
        fun of(window: AccessibilityWindowInfo): WindowArea = WindowArea(window.id, window.type, Rect().also(window::getBoundsInScreen))
    }
}

/** Screenshot helpers for Android 11–13, where only the whole display can be captured. */
internal object Screenshots {
    /**
     * The part of [target] that shows nothing but that window, or null when another window covers
     * part of it. Accessibility overlays (such as the operating banner) are allowed. A system bar or
     * keyboard that runs along a whole edge of the window is cut off rather than refused, since
     * nearly every app window reaches under the status and navigation bars; anything else that
     * overlaps (the notification shade, a pop-up, another app in split screen, a heads-up that
     * does not span the window) is refused.
     */
    fun cropArea(target: WindowArea, windows: List<WindowArea>): Rect? {
        val area = Rect(target.bounds)
        val others = windows.filter { it.id != target.id && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        // Each cut can make another window stop overlapping, so repeat until nothing changes.
        var changed = true
        while (changed) {
            changed = false
            for (other in others) {
                val b = other.bounds
                if (area.isEmpty || !Rect.intersects(area, b)) continue
                if (!trimmable(other.type)) return null
                val spansWidth = b.left <= area.left && b.right >= area.right
                val spansHeight = b.top <= area.top && b.bottom >= area.bottom
                when {
                    spansWidth && b.top <= area.top && b.bottom < area.bottom -> area.top = b.bottom
                    spansWidth && b.bottom >= area.bottom && b.top > area.top -> area.bottom = b.top
                    spansHeight && b.left <= area.left && b.right < area.right -> area.left = b.right
                    spansHeight && b.right >= area.right && b.left > area.left -> area.right = b.left
                    else -> return null
                }
                changed = true
            }
        }
        return area.takeUnless { it.isEmpty }
    }

    /** System bars and the keyboard can be cut away along an edge; other windows cannot. */
    private fun trimmable(type: Int): Boolean =
        type == AccessibilityWindowInfo.TYPE_SYSTEM || type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER

    /** [bitmap] cut to [area] (screen pixels); null when they do not overlap. */
    fun crop(bitmap: Bitmap, area: Rect): Bitmap? {
        val inside = Rect(area)
        if (!inside.intersect(0, 0, bitmap.width, bitmap.height)) return null
        return Bitmap.createBitmap(bitmap, inside.left, inside.top, inside.width(), inside.height())
    }

    /**
     * True when every sampled pixel is black or transparent: on Android 11–13 a window that
     * forbids screenshots (`FLAG_SECURE`) comes out like that, and no error says so.
     */
    fun looksBlank(bitmap: Bitmap): Boolean {
        val steps = 24
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (bitmap.width - 1) * i / (steps - 1).coerceAtLeast(1)
                val y = (bitmap.height - 1) * j / (steps - 1).coerceAtLeast(1)
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) != 0 && (Color.red(pixel) or Color.green(pixel) or Color.blue(pixel)) != 0) return false
            }
        }
        return true
    }

    /**
     * Writes [bitmap] as a PNG to [file], which must not exist yet: creating it fails if it does,
     * even if it appeared a moment ago, so nothing is ever overwritten. Returns the size written.
     *
     * @throws java.nio.file.FileAlreadyExistsException if [file] exists.
     * @throws IOException if it cannot be written; nothing is left behind.
     */
    fun writeNewPng(bitmap: Bitmap, file: File): Long {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        // Throws FileAlreadyExistsException before anything is created.
        val out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        var written = false
        try {
            out.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("The picture could not be encoded.")
            }
            written = true
        } finally {
            // Only the file this call created is removed.
            if (!written) runCatching { Files.deleteIfExists(path) }
        }
        return Files.size(path)
    }
}
