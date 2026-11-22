package com.aicontrolcenter.device.platform

/**
 * Turns text supplied by another app (its label or version name) into one bounded line.
 *
 * Such text is untrusted: an app may put line breaks, control characters or bidirectional
 * overrides into its label to imitate another app or to break a layout, and later phases show
 * these strings to models as well as to the user. Whitespace and control characters collapse to
 * single spaces, bidi overrides are dropped, and the result is cut at [maxLength] characters.
 */
internal fun CharSequence?.toSingleLine(maxLength: Int): String {
    if (this == null) return ""
    val out = StringBuilder(minOf(length, maxLength))
    var pendingSpace = false
    for (char in this) {
        when {
            char.isWhitespace() || char.isISOControl() -> pendingSpace = out.isNotEmpty()
            char in BIDI_EMBEDDINGS || char in BIDI_ISOLATES -> Unit
            else -> {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(char)
            }
        }
        if (out.length >= maxLength) break
    }
    if (out.length > maxLength) out.setLength(maxLength)
    // Never end on a separator or on half of a surrogate pair.
    while (out.isNotEmpty() && (out.last() == ' ' || out.last().isHighSurrogate())) out.setLength(out.length - 1)
    return out.toString()
}

private val BIDI_EMBEDDINGS = '\u202A'..'\u202E'
private val BIDI_ISOLATES = '\u2066'..'\u2069'
