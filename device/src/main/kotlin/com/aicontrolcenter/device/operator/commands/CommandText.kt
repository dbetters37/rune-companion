package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.ExactText

/**
 * Why [command] would not reach a shell as the text it is, or null when it would.
 *
 * A command goes to a process as UTF-8 bytes. Text in this app is UTF-16, where a character
 * outside the basic plane is a pair of two code units (surrogates). Half of such a pair alone,
 * which a model can send as `\ud800` in its JSON, is no character: it cannot be encoded, and
 * Java puts a question mark in its place without a word. A shell reads that question mark as a
 * pattern for any one character, so `rm keep<half>` would remove `keep1` and `keepX`: not the
 * text the owner was shown. A NUL ends the text for the process. Such a command is refused
 * here, as the last check before a process gets it; the tools refuse it earlier, before anyone
 * is asked. The rule and its words are [ExactText]'s, the same in both places.
 */
internal fun notExactProblem(command: String): String? =
    ExactText.problem(command)?.let { "The command contains $it, so the command was not run." }
