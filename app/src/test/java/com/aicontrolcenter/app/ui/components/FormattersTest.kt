package com.aicontrolcenter.app.ui.components

import org.junit.Test
import kotlin.test.assertEquals

/** Sizes and file kinds in the owner's words. */
class FormattersTest {
    @Test
    fun `small sizes are written out, and a size never splits from its unit`() {
        assertEquals("1 byte", formatBytes(1))
        assertEquals("15 bytes", formatBytes(15))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
    }

    @Test
    fun `a file's kind is named in words, with its format in brackets`() {
        assertEquals("Text (Markdown)", fileKindOf("text/markdown"))
        assertEquals("Table (CSV)", fileKindOf("text/csv"))
        assertEquals("Android app (APK)", fileKindOf("application/vnd.android.package-archive"))
        assertEquals("Other file", fileKindOf("application/octet-stream"))
    }
}
