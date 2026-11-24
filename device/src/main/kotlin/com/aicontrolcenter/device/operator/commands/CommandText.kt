package com.aicontrolcenter.device.operator.commands

/**
 * Why [command] would not reach a shell as the text it is, or null when it would.
 *
 * A command goes to a process as UTF-8 bytes. Text in this app is UTF-16, where a character
 * outside the basic plane is a pair of two code units (surrogates). Half of such a pair alone,
 * which a model can send as `\ud800` in its JSON, is no character: it cannot be encoded, and
 * Java puts a question mark in its place without a word. A shell reads that question mark as a
 * pattern for any one character, so `rm keep<half>` would remove `keep1` and `keepX`: not the
 * text the owner was shown. Such a command is refused here, as the last check before a process
 * gets it; the tools refuse it earlier, before anyone is asked.
 */
internal fun notExactProblem(command: String): String? {
    var i = 0
    while (i < command.length) {
        val c = command[i]
        if (Character.isHighSurrogate(c) && i + 1 < command.length && Character.isLowSurrogate(command[i + 1])) {
            i += 2
            continue
        }
        if (Character.isSurrogate(c)) return notExact(" (U+%04X, half of a two-part character)".format(c.code))
        i++
    }
    // The same question asked of the encoder itself, whatever else it may not carry over unchanged.
    return if (String(command.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == command) null else notExact("")
}

private fun notExact(which: String): String =
    "The command contains a character that cannot be passed on as it is$which. " +
        "A shell would get a different text than the one that was shown, so the command was not run."
