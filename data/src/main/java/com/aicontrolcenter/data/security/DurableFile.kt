package com.aicontrolcenter.data.security

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * One small file that is always replaced as a whole and must never be lost or half-written:
 * the vault index and the sealed database key.
 *
 * The platform's `AtomicFile` is deliberately not used. It only logs a failed sync or rename,
 * so a caller is told "saved" for content that never reached the disk. Here every step of the
 * commit either succeeds or throws.
 *
 * Not thread-safe; the owner serialises access.
 */
internal class DurableFile(private val file: File) {

    /**
     * Returns the content, or null when the file does not exist.
     *
     * "Does not exist" means exactly that: the directory entry is missing. A file that is there
     * but cannot be opened (no permission, too many open files, an I/O error) throws, because
     * treating it as absent would let the next write replace content that is still intact.
     * `java.io.FileInputStream` cannot tell these cases apart; `java.nio.file` can.
     */
    @Throws(IOException::class)
    fun readOrNull(): ByteArray? =
        try {
            Files.readAllBytes(file.toPath())
        } catch (_: NoSuchFileException) {
            null
        }

    /**
     * Replaces the content with [bytes]. The new content is written to a side file, forced to
     * disk and renamed over the old file in one atomic step, so a crash leaves either the old
     * content or the new, never a mixture.
     *
     * @throws IOException if any step fails. The previous content is then still in place.
     */
    @Throws(IOException::class)
    fun replace(bytes: ByteArray) {
        val target = file.toPath()
        val side = target.resolveSibling(file.name + SIDE_SUFFIX)
        target.parent?.let(Files::createDirectories)
        var sideCreated = false
        try {
            FileChannel.open(side, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .use { channel ->
                    sideCreated = true
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            Files.move(side, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            if (sideCreated) discard(side)
            throw failure
        }
        syncDirectory(target.parent)
    }

    private fun discard(side: Path) {
        try {
            Files.deleteIfExists(side)
        } catch (_: IOException) {
            // A leftover side file is harmless: it is never read and the next write truncates it.
        }
    }

    /**
     * Makes the rename itself survive a power cut. This step alone is best effort: once the
     * rename has happened the new content is what every later read sees, so reporting the write
     * as failed would be wrong, and the platform's own atomic files give no stronger guarantee.
     */
    private fun syncDirectory(directory: Path?) {
        if (directory == null) return
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (failure: IOException) {
            Log.w(TAG, "Could not sync the directory of ${file.name} (${failure.javaClass.simpleName}).")
        }
    }

    companion object {
        /** Suffix of the side file a write creates next to the real one. */
        const val SIDE_SUFFIX = ".tmp"
        private const val TAG = "AiccStorage"
    }
}
