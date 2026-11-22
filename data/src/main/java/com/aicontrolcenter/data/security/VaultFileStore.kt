package com.aicontrolcenter.data.security

import android.util.Log
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.data.DataJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and writes the vault index: one JSON file mapping each alias to its sealed value
 * (Base64). Aliases are stored in the clear, since they are names like `provider/prv_1`, not
 * secrets; the values are opaque without the cipher's key.
 *
 * Reading distinguishes four situations, because confusing them loses secrets:
 * - no file: the vault was never written and is empty;
 * - a file that cannot be opened or read: an error, and nothing is touched (see [DurableFile]);
 * - a file written by a newer format version: an error, and nothing is touched;
 * - a file that is not a vault index at all: moved aside under a unique name, vault empty.
 *
 * Not thread-safe; the vault serialises access.
 */
internal class VaultFileStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val durable = DurableFile(file)

    /**
     * Returns the stored index, or an empty one if the vault has never been written.
     *
     * A file that exists but is not a vault index (truncated or damaged outside the app) cannot
     * be repaired, and leaving it in place would block every later write. It is moved aside to
     * `<name>.corrupt-<time>` and the vault starts empty; the affected secrets have to be
     * entered again. Earlier set-aside copies are never overwritten.
     *
     * @throws SecretVaultException if the file cannot be read, cannot be set aside, or was
     *   written by a newer format version. In each case the file is left exactly as it was.
     */
    fun read(): Map<String, String> {
        val bytes = try {
            durable.readOrNull()
        } catch (failure: IOException) {
            throw SecretVaultException("The secret vault could not be read.", failure)
        } ?: return emptyMap()
        return when (val content = parse(bytes)) {
            is Content.Index -> content.entries
            is Content.NewerFormat -> throw SecretVaultException(
                "The secret vault uses format ${content.version}, which this version of the app cannot read.",
            )
            Content.Damaged -> {
                setAside()
                emptyMap()
            }
        }
    }

    /**
     * @throws SecretVaultException if the index did not reach the disk; the previous one is
     *   then still in place.
     */
    fun write(entries: Map<String, String>) {
        val bytes = DataJson.encodeToString(VaultIndex.serializer(), VaultIndex(FORMAT_VERSION, entries))
            .toByteArray(Charsets.UTF_8)
        try {
            durable.replace(bytes)
        } catch (failure: IOException) {
            throw SecretVaultException("The secret vault could not be saved.", failure)
        }
    }

    /**
     * The version is read before anything else is interpreted: a newer format may well change
     * the shape of the entries, and it must be refused, not mistaken for damage.
     */
    private fun parse(bytes: ByteArray): Content {
        val root = try {
            DataJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return Content.Damaged
        val version = (root[VERSION_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: return Content.Damaged
        if (version > FORMAT_VERSION) return Content.NewerFormat(version)
        if (version != FORMAT_VERSION.toLong()) return Content.Damaged
        return try {
            Content.Index(DataJson.decodeFromJsonElement(VaultIndex.serializer(), root).entries)
        } catch (_: SerializationException) {
            Content.Damaged
        }
    }

    private fun setAside() {
        val target = generateSequence(0) { it + 1 }
            .map { n -> File(file.path + CORRUPT_SUFFIX + now() + if (n == 0) "" else "-$n") }
            .first { !it.exists() }
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            throw SecretVaultException("The secret vault file is damaged and could not be set aside.", failure)
        }
        // Nothing from the file is logged: even damaged, it holds ciphertext and aliases.
        Log.w(TAG, "The secret vault file was not a vault index. It was kept as ${target.name} and the vault is empty.")
    }

    private sealed interface Content {
        class Index(val entries: Map<String, String>) : Content
        class NewerFormat(val version: Long) : Content
        data object Damaged : Content
    }

    /** The file's JSON shape. Its key names are part of the stored format. */
    @Serializable
    internal data class VaultIndex(val version: Int, val entries: Map<String, String> = emptyMap())

    companion object {
        const val FORMAT_VERSION = 1
        const val CORRUPT_SUFFIX = ".corrupt-"
        private const val VERSION_KEY = "version"
        private const val TAG = "AiccVault"
    }
}
