package com.aicontrolcenter.data.security

import android.util.Log
import androidx.core.util.AtomicFile
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.data.DataJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Reads and writes the vault index: one JSON file mapping each alias to its sealed value
 * (Base64). Aliases are stored in the clear, since they are names like `provider/prv_1`, not
 * secrets; the values are opaque without the cipher's key.
 *
 * Writes go through [AtomicFile]: the new content is written to a side file, synced to disk
 * and renamed over the old one, so a crash or power loss leaves either the previous index or
 * the new one, never a partial file.
 *
 * Not thread-safe; the vault serialises access.
 */
internal class VaultFileStore(private val file: File) {
    private val atomicFile = AtomicFile(file)

    /**
     * Returns the stored index, or an empty one if the vault has never been written.
     *
     * A file that exists but is not a vault index (truncated or damaged outside the app) cannot
     * be repaired, and leaving it in place would block every later write. It is moved aside to
     * `<name>.corrupt` and the vault starts empty; the affected secrets have to be entered again.
     *
     * @throws SecretVaultException if the file cannot be read, or was written by a newer
     *   format version (in which case it is left untouched).
     */
    fun read(): Map<String, String> {
        val bytes = try {
            atomicFile.readFully()
        } catch (_: FileNotFoundException) {
            return emptyMap()
        } catch (failure: IOException) {
            throw SecretVaultException("The secret vault could not be read.", failure)
        }
        val index = try {
            DataJson.decodeFromString(VaultIndex.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            quarantine()
            return emptyMap()
        }
        if (index.version != FORMAT_VERSION) {
            throw SecretVaultException(
                "The secret vault uses format ${index.version}, which this version of the app cannot read.",
            )
        }
        return index.entries
    }

    /** @throws SecretVaultException if the index cannot be written; the previous one stays in place. */
    fun write(entries: Map<String, String>) {
        val bytes = DataJson.encodeToString(VaultIndex.serializer(), VaultIndex(FORMAT_VERSION, entries))
            .toByteArray(Charsets.UTF_8)
        try {
            file.parentFile?.mkdirs()
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (failure: IOException) {
                atomicFile.failWrite(stream)
                throw failure
            }
        } catch (failure: IOException) {
            throw SecretVaultException("The secret vault could not be saved.", failure)
        }
    }

    private fun quarantine() {
        val target = File(file.path + CORRUPT_SUFFIX)
        target.delete()
        val moved = file.renameTo(target)
        // Nothing from the file is logged: even damaged, it holds ciphertext and aliases.
        Log.w(TAG, "The secret vault file was unreadable and has been reset (kept aside: $moved).")
        if (!moved && !file.delete()) {
            throw SecretVaultException("The secret vault file is damaged and could not be replaced.")
        }
    }

    @Serializable
    private data class VaultIndex(val version: Int, val entries: Map<String, String> = emptyMap())

    companion object {
        const val FORMAT_VERSION = 1
        const val CORRUPT_SUFFIX = ".corrupt"
        private const val TAG = "AiccVault"
    }
}
