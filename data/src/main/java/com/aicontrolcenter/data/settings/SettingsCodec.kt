package com.aicontrolcenter.data.settings

import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.data.DataJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Turns [FrameworkSettings] into the JSON text that is stored, and back.
 *
 * Reading is lenient per field. A value another build wrote may contain a field this build
 * cannot read (its type changed, an enum constant is unknown). Such a field falls back to its
 * default on its own; every other field keeps its stored value. Resetting everything because
 * of one field would, among other things, switch local-only mode off behind the owner's back.
 *
 * One field is treated specially: when [FrameworkSettings.localOnlyMode] itself cannot be
 * read, alone or because the whole value is unreadable, it comes back **on**. Nobody can say
 * what the owner had chosen, and wrongly blocking cloud providers is an inconvenience that is
 * visible and undone with one switch, while wrongly allowing them sends data off the device.
 */
internal object SettingsCodec {

    /**
     * @property settings the settings to use.
     * @property unreadableFields names of stored fields that could not be read and were
     *   replaced by a default; [WHOLE_VALUE] when nothing could be read at all.
     */
    data class Decoded(val settings: FrameworkSettings, val unreadableFields: Set<String>) {
        /** False when re-encoding [settings] would drop something that is stored. */
        val complete: Boolean get() = unreadableFields.isEmpty()
    }

    /** What is used when stored settings existed but nothing of them could be read. */
    val AFTER_DATA_LOSS = FrameworkSettings(localOnlyMode = true)

    /** Stands in for the field names when the stored value as a whole is unreadable. */
    const val WHOLE_VALUE = "*"

    private val serializer = FrameworkSettings.serializer()

    @OptIn(ExperimentalSerializationApi::class)
    private val fieldNames: Set<String> = serializer.descriptor.elementNames.toSet()

    private val localOnlyField = FrameworkSettings::localOnlyMode.name

    fun encode(settings: FrameworkSettings): String = DataJson.encodeToString(serializer, settings)

    /** @param stored the stored text, or null when settings were never saved. */
    fun decode(stored: String?): Decoded {
        if (stored == null) return Decoded(FrameworkSettings(), emptySet())
        val root = try {
            DataJson.parseToJsonElement(stored) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Decoded(AFTER_DATA_LOSS, setOf(WHOLE_VALUE))

        decodeOrNull(root)?.let { return Decoded(it, emptySet()) }

        val unreadable = root.filter { (name, value) -> name in fieldNames && !readable(name, value) }.keys
        val settings = decodeOrNull(JsonObject(root - unreadable))
            ?: return Decoded(AFTER_DATA_LOSS, setOf(WHOLE_VALUE))
        return Decoded(
            settings = if (localOnlyField in unreadable) settings.copy(localOnlyMode = true) else settings,
            unreadableFields = unreadable,
        )
    }

    private fun readable(name: String, value: JsonElement): Boolean =
        decodeOrNull(JsonObject(mapOf(name to value))) != null

    /** kotlinx.serialization reports every unusable input as an [IllegalArgumentException]. */
    private fun decodeOrNull(fields: JsonObject): FrameworkSettings? =
        try {
            DataJson.decodeFromJsonElement(serializer, fields)
        } catch (_: IllegalArgumentException) {
            null
        }
}
