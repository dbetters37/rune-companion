package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tells an agent the current date and time. Models have no clock, and guess when asked.
 * Output is always written in English so that it reads the same to every model whatever the
 * phone's language is.
 */
class ClockTool(
    private val clock: Clock,
    /** Read on every call: the phone may change time zone while the app runs. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : Tool {
    override val spec = ToolSpec(
        id = ID,
        functionName = "clock_now",
        title = "Current time",
        description = "Returns the current date, time, weekday and time zone of the owner's phone. " +
            "Use it whenever the answer depends on today's date or the time now; never guess them.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("timezone") {
                    put("type", "string")
                    put("description", "Optional IANA time zone such as \"Europe/Paris\" to get the time there instead of on the phone.")
                }
            }
            put("additionalProperties", false)
        },
        category = ToolCategory.SYSTEM,
        capability = Capabilities.BASIC_INFO,
        risk = RiskLevel.SAFE,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val requested = (arguments["timezone"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val zoneId = if (requested.isEmpty()) {
            zone()
        } else {
            try {
                ZoneId.of(requested)
            } catch (e: DateTimeException) {
                return ToolOutcome.failure("\"${requested.take(60)}\" is not a known time zone. Use an IANA name such as Europe/Paris or America/New_York.")
            }
        }
        val now = Instant.ofEpochMilli(clock.now()).atZone(zoneId)
        val where = if (requested.isEmpty()) "on the owner's phone" else "in $zoneId"
        val text = "It is ${READABLE.format(now)} $where (time zone $zoneId, UTC${OFFSET.format(now)}). ISO 8601: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)}."
        return ToolOutcome.success(
            text,
            buildJsonObject {
                put("iso", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now))
                put("epochMillis", now.toInstant().toEpochMilli())
                put("timeZone", zoneId.id)
                put("weekday", WEEKDAY.format(now))
            },
        )
    }

    companion object {
        val ID = ToolId("clock.now")
        private val READABLE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss", Locale.ENGLISH)
        private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
        private val OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ENGLISH)
    }
}
