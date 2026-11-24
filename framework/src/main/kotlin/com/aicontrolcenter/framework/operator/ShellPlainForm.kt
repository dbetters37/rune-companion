package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.Workspace
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The one form of shell command for which the approval says which script runs (R1 of round 4):
 * `sh file [arguments]`, written in plain characters. For every other command the scripts the
 * reader finds ([ShellCommands]) are shown as a reading, never as a fact.
 *
 * This is an allow-list. A command has the plain form only when ALL of this holds:
 *
 *  1. After spaces, tabs and line breaks at its two ends are taken off, the whole text consists
 *     of words made of `A-Z a-z 0-9 . _ / -` only, with exactly one space or tab between two
 *     words. So there is no quote, backslash, `$`, backquote, `=`, `~`, brace, bracket, `*`,
 *     `?`, `#`, `;`, `&`, `|`, `<`, `>`, parenthesis or line break in it, and no character
 *     outside ASCII: nothing the shell expands, splits, assigns, redirects or reads as a second
 *     command. Each word reaches the program exactly as it is written.
 *  2. The first word is exactly `sh`.
 *  3. The second word is the script: it does not start with `-` (it would be an option of the
 *     shell), does not start with `/`, has no `..` part, and does not end in `/` or `/.`.
 *  4. From the folder the command runs in (the tool's `directory` argument) it names an existing
 *     regular file inside the workspace that is not a link and is not reached through a link:
 *     every folder on the way is a real folder ([Workspace.resolve] refuses a link in any
 *     part, and the trash).
 *  5. Whatever follows are arguments to that script. They may start with `-`.
 *
 * Why that file, and no other, is then what runs, on Android's shell (mksh, as `/system/bin/sh`):
 *
 *  - The runner starts `/system/bin/sh -c <command>` in that folder with `PATH=/system/bin`, a
 *    `HOME` outside the workspace and no `ENV` (`SystemShellRunner`). That outer shell reads no
 *    start-up file (it is neither a login nor an interactive shell), so no alias and no function
 *    is defined when it reads the command, and none of mksh's built-in aliases is called `sh`.
 *    `sh` is no reserved word and no built-in, so it is looked up in `PATH`: `/system/bin/sh`.
 *  - mksh reads its own arguments as options only up to the first word that does not start with
 *    `-` or `+`; that word is the script, and the words after it are the script's arguments,
 *    whatever they look like. By rule 3 the second word is that first word.
 *  - mksh opens the script by the name as it is written, from the folder it runs in. It does
 *    not search `PATH` for it (its `main.c` opens that argument as a file and gives up with
 *    exit code 127 when that fails). A shell that does search `PATH` (bash, and POSIX allows
 *    it) only does so when there is no such file in the folder, and rule 4 says there is one.
 *  - A shell started with a script is neither a login shell nor interactive, so it reads no
 *    profile, and `$ENV` only counts for interactive shells (and is not set). A `#!` line in the
 *    script is a comment to it.
 *  - Between the moment the owner is asked and the end of the command nothing else changes the
 *    workspace through this app ([Workspace.runCommand]), and the file's content is compared
 *    once more before running, so the text that was shown is the text that is read.
 *
 * What was seen on a real mksh (R59, the Android 14 emulator, in the second verification's
 * runs): after `cd sub`, `sh tidy.sh` read `sub/tidy.sh`, the file in the folder the shell
 * stood in; `sh - tidy.sh` read `tidy.sh`; neither read the `.profile` that lay in `HOME`,
 * and `sh -l` did. The rest stands on the points above, taken from mksh's manual and source,
 * and on dash and bash on the build machine, where the tests run a real shell behind the tool
 * (ShellRound4Test). It was not run on a phone in this round.
 *
 * What the script does when it runs, the scripts it starts in turn included, is not covered:
 * those are read like any other shell text and shown as a reading.
 */
internal object ShellPlainForm {
    private const val WORD = "[A-Za-z0-9._/-]+"

    /** Rules 1, 2 and 5: `sh`, one blank, the script, then any number of arguments with one blank before each. */
    private val FORM = Regex("sh[ \\t]($WORD)(?:[ \\t]$WORD)*")

    /**
     * The script's word as it is written when [command] has the plain form as far as its text
     * goes (rules 1, 2, 3 and 5); null when it has not.
     */
    fun scriptWord(command: String): String? {
        val text = command.trim(' ', '\t', '\n')
        val script = FORM.matchEntire(text)?.groupValues?.get(1) ?: return null
        if (script.startsWith("-") || script.startsWith("/")) return null
        val names = script.split('/')
        if (names.any { it == ".." } || names.last().isEmpty() || names.last() == ".") return null
        return script
    }

    /**
     * The workspace file [command] runs when it has the plain form (all five rules), from
     * [directory]; null for every other command. Looks at the file system now: the caller pins
     * what it found.
     */
    suspend fun script(command: String, directory: RelativePath, workspace: Workspace): RelativePath? {
        val word = scriptWord(command) ?: return null
        // "./x" and "a//x" name what "x" and "a/x" name; ".." was ruled out.
        val names = directory.segments + word.split('/').filter { it.isNotEmpty() && it != "." }
        val path = (RelativePath.parse(names.joinToString("/")) as? RelativePath.Check.Valid)?.path ?: return null
        if (path.segments != names) return null
        // Rule 4. The sandbox check refuses a link anywhere on the way, the trash and what is outside the root.
        val file = (workspace.resolve(path) as? DocResult.Ok)?.value?.file ?: return null
        return path.takeIf { Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
    }
}
