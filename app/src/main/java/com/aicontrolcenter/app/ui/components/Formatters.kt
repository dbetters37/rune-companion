package com.aicontrolcenter.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Pure text formatting shared by view models. Everything takes the zone and "now" explicitly
 * so results are deterministic in tests.
 */

private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
private val fullDateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH)

fun localDateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/** "14:05". */
fun formatTimeOfDay(epochMillis: Long, zone: ZoneId): String = timeOfDay.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** "14 Nov 2026, 14:05:09", for the event inspector. */
fun formatFullDateTime(epochMillis: Long, zone: ZoneId): String = fullDateTime.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** "Today", "Yesterday" or "Saturday, 14 November 2026". */
fun formatDayHeading(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> longDate.format(day)
}

/** A compact timestamp for a list row: the time today, otherwise the date and time. */
fun formatCompactTimestamp(epochMillis: Long, nowMillis: Long, zone: ZoneId): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = localDateOf(nowMillis, zone)
    return when (moment.toLocalDate()) {
        today -> timeOfDay.format(moment)
        today.minusDays(1) -> "Yesterday " + timeOfDay.format(moment)
        else -> shortDate.format(moment) + ", " + timeOfDay.format(moment)
    }
}

/**
 * "3.1 GB", "512 MB", "2.0 KB", "17 bytes". Binary units, as memory and storage are reported.
 * The number and its unit are joined by a non-breaking space, so a size never splits across lines.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return if (bytes == 1L) "1${NBSP}byte" else "$bytes${NBSP}bytes"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%.0f$NBSP%s".format(Locale.ROOT, value, units[unit]) else "%.1f$NBSP%s".format(Locale.ROOT, value, units[unit])
}

/** Joins a number to its unit so the two stay on one line. */
const val NBSP: Char = '\u00A0'

/**
 * A file's kind in words, from its media type: "Text (Markdown)", "Table (CSV)", "Image (PNG)".
 * Unknown types fall back to "Other file".
 */
fun fileKindOf(mimeType: String): String = when (mimeType) {
    "text/plain" -> "Text"
    "text/markdown" -> "Text (Markdown)"
    "text/csv" -> "Table (CSV)"
    "application/json" -> "Data (JSON)"
    "text/html" -> "Web page (HTML)"
    "text/xml" -> "Data (XML)"
    "text/javascript" -> "Script (JavaScript)"
    "application/pdf" -> "Document (PDF)"
    "image/png" -> "Image (PNG)"
    "image/jpeg" -> "Image (JPEG)"
    "image/webp" -> "Image (WebP)"
    "image/gif" -> "Image (GIF)"
    "video/mp4" -> "Video (MP4)"
    "application/zip" -> "Archive (ZIP)"
    "application/vnd.android.package-archive" -> "Android app (APK)"
    else -> "Other file"
}

/** "42 s", "3 min", "1 h 05 min". */
fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "%d h %02d min".format(Locale.ROOT, seconds / 3600, (seconds % 3600) / 60)
    }
}

/** "182,400": a large count with thousands separators. */
fun formatCount(count: Long): String = "%,d".format(Locale.ENGLISH, count)

/** "1 agent" / "3 agents". */
fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
