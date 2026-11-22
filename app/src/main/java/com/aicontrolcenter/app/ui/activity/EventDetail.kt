package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.explanation
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.app.ui.components.formatFullDateTime
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** One labelled value in the inspector. [technical] values are shown in monospace. */
@Immutable
data class DetailField(val label: String, val value: String, val technical: Boolean = false)

/**
 * Everything recorded about one event, laid out for reading: the event's own fields, its
 * structured data, and where information went if the event moved any off the device.
 */
@Immutable
data class EventDetailUi(
    val event: ActivityEvent,
    val fields: List<DetailField>,
    val data: List<DetailField>,
    /** Null when nothing left the device. */
    val egress: List<DetailField>?,
)

internal fun ActivityEvent.toDetail(snapshot: RegistrySnapshot, zone: ZoneId): EventDetailUi {
    val agentName = agentId?.let { snapshot.agentsById[it]?.profile?.name }
    val fields = buildList {
        add(DetailField("When", formatFullDateTime(at, zone)))
        add(DetailField("Type", type.label))
        add(DetailField("Severity", severity.label))
        add(
            DetailField(
                "Caused by",
                when {
                    agentId == null -> "You or the system"
                    agentName != null -> "$agentName (agent)"
                    else -> "An agent that no longer exists"
                },
            ),
        )
        agentId?.let { add(DetailField("Agent id", it.value, technical = true)) }
        add(DetailField("Task", taskId?.value ?: "None", technical = taskId != null))
        add(DetailField("Objective", rootTaskId?.value ?: "None", technical = rootTaskId != null))
        add(DetailField("Event id", id.value, technical = true))
    }
    val dataFields = data?.entries.orEmpty().map { (key, value) ->
        val text = when (value) {
            is JsonNull -> "null"
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
        DetailField(key, text, technical = true)
    }
    val egressFields = egress?.let { info ->
        buildList {
            add(DetailField("Destination", info.destinationHost, technical = true))
            add(DetailField("Where that is", "${info.locality.label}. ${info.locality.explanation}"))
            info.providerId?.let { providerId ->
                val provider = snapshot.providersById[providerId]
                add(DetailField("Provider", provider?.name ?: providerId.value, technical = provider == null))
            }
            add(DetailField("What was sent", info.purpose))
            add(DetailField("Approximate size", info.approxBytes?.let(::formatBytes) ?: "Not measured"))
        }
    }
    return EventDetailUi(event = this, fields = fields, data = dataFields, egress = egressFields)
}
