package com.aicontrolcenter.device.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.WorkspaceFile
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Copies a stream into a new workspace file, never replacing anything: the file is created with
 * `O_CREAT | O_EXCL` (which also refuses a symbolic link in its place), so a file that appears
 * meanwhile is not overwritten either. A copy that fails or passes the limit is removed.
 */
internal object WorkspaceCopy {
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Copies what [open] returns (null: the source is gone, [DocResult.NotFound] with
     * [sourceName]) into [target], at most [maxBytes]. [knownSize], when the source reported
     * one, makes an oversized source fail before anything is written.
     */
    fun copyNew(target: WorkspaceFile, maxBytes: Long, knownSize: Long?, sourceName: String, open: () -> InputStream?): DocResult<Long> {
        if (knownSize != null && knownSize > maxBytes) return DocResult.TooLarge(knownSize, maxBytes)
        val path = target.file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return DocResult.AlreadyExists(target.path.value)
        val parent = target.file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return DocResult.Failed("The folder for \"${target.path.value}\" could not be created in the workspace.")
        }
        val out = try {
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            return DocResult.AlreadyExists(target.path.value)
        } catch (e: IOException) {
            return DocResult.Failed("\"${target.path.value}\" could not be created in the workspace (${e.javaClass.simpleName}).")
        }
        var kept = false
        try {
            var copied = 0L
            out.use { sink ->
                val input = open() ?: return DocResult.NotFound(sourceName)
                input.use { source ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > maxBytes) return DocResult.TooLarge(maxOf(copied, knownSize ?: 0), maxBytes)
                        sink.write(buffer, 0, n)
                    }
                }
            }
            kept = true
            return DocResult.Ok(copied)
        } catch (e: IOException) {
            return DocResult.Failed("Copying $sourceName into the workspace failed (${e.javaClass.simpleName}).")
        } finally {
            if (!kept) Files.deleteIfExists(path)
        }
    }
}
