package com.aicontrolcenter.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The all-or-nothing file under the vault index and the sealed database key. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DurableFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.newFolder("store"), "nested/data.bin") }
    private val side: File get() = File(file.path + ".tmp")

    @Test
    fun `a file that was never written reads as absent, also when its directory is missing`() {
        assertNull(DurableFile(file).readOrNull())
        file.parentFile!!.mkdirs()
        assertNull(DurableFile(file).readOrNull())
    }

    @Test
    fun `replace creates missing directories, stores the exact bytes and leaves no side file`() {
        val durable = DurableFile(file)
        val content = ByteArray(100_000) { (it * 31).toByte() }

        durable.replace(content)
        assertContentEquals(content, durable.readOrNull())
        durable.replace(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), durable.readOrNull())
        durable.replace(ByteArray(0))
        assertContentEquals(ByteArray(0), durable.readOrNull())

        assertEquals(listOf("data.bin"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `something that is there but cannot be read is an error, not absence`() {
        assertTrue(file.mkdirs()) // a directory where the file should be
        assertFailsWith<IOException> { DurableFile(file).readOrNull() }

        val underAFile = File(folder.newFile("plain"), "data.bin")
        assertFailsWith<IOException> { DurableFile(underAFile).readOrNull() }
    }

    @Test
    fun `a write that cannot start changes nothing and removes nothing`() {
        val durable = DurableFile(file)
        durable.replace("old".toByteArray())
        assertTrue(side.mkdir()) // occupies the side file's name

        assertFailsWith<IOException> { durable.replace("new".toByteArray()) }

        assertContentEquals("old".toByteArray(), durable.readOrNull())
        assertTrue(side.isDirectory, "the write removed something it did not create")
    }

    @Test
    fun `a write that cannot be committed throws, keeps the target and cleans up after itself`() {
        assertTrue(file.mkdirs())
        File(file, "occupied").writeText("x") // a non-empty directory cannot be renamed over

        assertFailsWith<IOException> { DurableFile(file).replace("new".toByteArray()) }

        assertEquals(listOf("occupied"), file.list()!!.toList())
        assertEquals(listOf("data.bin"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a stale side file is ignored by reads and replaced by the next write`() {
        file.parentFile!!.mkdirs()
        side.writeText("left over from an interrupted write, longer than the next content")
        val durable = DurableFile(file)

        assertNull(durable.readOrNull())
        durable.replace("fresh".toByteArray())

        assertContentEquals("fresh".toByteArray(), durable.readOrNull())
        assertEquals(listOf("data.bin"), file.parentFile!!.list()!!.toList())
    }
}
