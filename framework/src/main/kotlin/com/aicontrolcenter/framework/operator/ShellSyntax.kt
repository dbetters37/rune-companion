package com.aicontrolcenter.framework.operator

/**
 * A reading of shell text, as far as it can be read without running it: words with their quotes
 * and backslashes taken off, the operators between them, and the simple commands they form.
 * [ShellCommands] decides on top of this. It follows the POSIX shell grammar for what Android's
 * `sh` (mksh) does with static text; whatever a command builds only when it runs (`$x`, `$(…)`)
 * stays marked as such, never guessed.
 *
 * It is not a shell, and it says so: wherever it is not sure that it splits the text the way
 * mksh does (a here-document whose end is in doubt, a `case` inside `$(…)`, a command hidden in
 * `${…}`, a quote that is not closed …), it records a doubt in [Script.doubts], and a text with
 * a doubt is refused rather than read approximately.
 */
internal object ShellSyntax {
    /** What a part of a word is. */
    enum class Kind {
        /** Literal text. */
        LIT,

        /** `$NAME` or `${NAME}`; [Piece.text] is the name. Also `~` at the start of a word, as `HOME`. */
        VAR,

        /** `$(…)` or backticks; [Piece.text] is the command inside. */
        SUB,

        /** Anything else that is only known when the command runs (`$1`, `${x:-y}`, `$((…))`, `~user`). */
        DYN,

        /** A ksh pattern list, which mksh always understands: `@(a|b)`, `+(…)`, `?(…)`, `*(…)`, `!(…)`; [Piece.text] is how it was written. */
        PAT,
    }

    /** One part of a word. [quoted]: it was inside quotes or after a backslash, so the shell neither splits nor globs it. */
    data class Piece(val kind: Kind, val text: String, val quoted: Boolean)

    /** One shell word. [raw] is how it was written. */
    class Word(val pieces: List<Piece>, val raw: String) {
        /** The word's text when it is all literal, quotes and backslashes removed; null when part of it is only known at run time. */
        val plain: String? get() = if (pieces.all { it.kind == Kind.LIT }) pieces.joinToString("") { it.text } else null

        /** True when the word was written without any quote, backslash or expansion. */
        val bare: Boolean get() = pieces.all { it.kind == Kind.LIT && !it.quoted }

        /** `NAME=value` at the start, unquoted: the name and the value as a word of its own; else null. */
        fun assignment(): Pair<String, Word>? {
            val first = pieces.firstOrNull() ?: return null
            if (first.kind != Kind.LIT || first.quoted) return null
            val match = ASSIGNMENT.find(first.text) ?: return null
            val rest = first.text.substring(match.value.length)
            val value = (if (rest.isEmpty()) emptyList() else listOf(first.copy(text = rest))) + pieces.drop(1)
            return match.groupValues[1] to Word(tilde(value), raw.substringAfter('=', ""))
        }

        override fun toString(): String = raw
    }

    sealed interface Token

    data class WordToken(val word: Word) : Token

    /** A control operator: `;` `;;` `&` `&&` `||` `|` `|&` `(` `)` or a line break (`"\n"`). */
    data class Op(val text: String) : Token

    /** A redirection: [op] is `<` `>` `>>` `<<` `<<-` `<<<` `<&` `>&` `<>` `>|` or `&>`; [body] is a here-document's text. */
    class Redirect(val op: String, val target: Word?) : Token {
        var body: String? = null

        /** For a here-document: its end word was quoted (`<<'EOF'`), so the shell passes the text on exactly as written. */
        var quotedEnd: Boolean = false

        /**
         * For a here-document with an end word that is not quoted: what the shell expands in its
         * text before anyone reads it (`$x`, `$(…)`, backquotes). A `$(…)` among them is a command
         * that runs, whoever the document is for.
         */
        var expansions: List<Piece> = emptyList()
    }

    /** One simple command: what is assigned, the command word and its arguments, and its redirections. */
    class Simple(
        val assignments: List<Pair<String, Word>>,
        val words: List<Word>,
        val redirects: List<Redirect>,
        /** Its input comes from the command before it (`|`). */
        val pipedInto: Boolean,
        /** Its output goes to the command after it (`|`). */
        var pipesOn: Boolean = false,
    )

    /** `for NAME in words`: the values the loop gives [name]. */
    class Loop(val name: String, val values: List<Word>)

    /**
     * @property commands the simple commands, in order
     * @property loops the `for` loops
     * @property extras words that are neither commands nor arguments but may name something (the subject of a `case`)
     * @property operators every control operator and redirection operator, in order
     * @property doubts why the text may mean something else to the shell than what was read here; each completes "The command …"
     * @property openCase a `case` was left without its `esac`
     */
    class Script(
        val commands: List<Simple>,
        val loops: List<Loop>,
        val extras: List<Word>,
        val operators: List<String>,
        val doubts: List<String> = emptyList(),
        val openCase: Boolean = false,
    )

    /** The end word of a here-document that is beyond doubt: a plain word, bare or in one pair of quotes. */
    private val PLAIN_END = Regex("""^(?:[A-Za-z0-9_.+-]+|'[A-Za-z0-9_.+-]+'|"[A-Za-z0-9_.+-]+")$""")

    const val DOUBT_CARRIAGE_RETURN = "contains a carriage return character, which the shell reads as part of a word"
    const val DOUBT_HERE_END = "has a here-document whose end word is not a plain word (write <<EOF or <<'EOF')"
    const val DOUBT_HERE_CONTINUED = "has a here-document with a line that ends in a backslash, which the shell joins to the next line " +
        "(quote the end word, <<'EOF', to pass the text on as it is)"
    const val DOUBT_HERE_OPEN = "has a here-document that is not closed by its end word"
    const val DOUBT_HERE_NESTED = "has a here-document inside \$(…) or backquotes"
    const val DOUBT_CASE = "has a case statement inside \$(…) whose end cannot be told with certainty (put the patterns in parentheses, \"(a) …\", or write the case outside)"
    const val DOUBT_HIDDEN_COMMAND = "runs a command inside \${…} or \$((…))"
    const val DOUBT_PROCESS_SUBSTITUTION = "uses <(…) or >(…), which Android's shell does not read as other shells do"
    const val DOUBT_UNCLOSED = "has a quote, a \$(…), a \${…} or backquotes that are not closed"
    const val DOUBT_BACKQUOTES = "has backquotes with a backslash before a quote inside double quotes (write \$(…) instead)"
    private val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=")
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val CONTROL = setOf(";", ";;", "&", "&&", "||", "|", "|&", "(", ")", "\n")

    /** Words that, where a command starts, are followed by another command start. */
    private val TRANSPARENT = setOf("if", "then", "else", "elif", "fi", "do", "done", "while", "until", "!", "{", "}", "time")

    /** Reads [text] into simple commands. Never throws: unfinished quotes run to the end. */
    fun parse(text: String): Script {
        val lexer = Lexer(text)
        val tokens = lexer.run()
        val commands = ArrayList<Simple>()
        val loops = ArrayList<Loop>()
        val extras = ArrayList<Word>()
        var assignments = ArrayList<Pair<String, Word>>()
        var words = ArrayList<Word>()
        var redirects = ArrayList<Redirect>()
        var piped = false
        var cases = 0
        var patterns = false

        fun finish(pipesOn: Boolean) {
            if (assignments.isNotEmpty() || words.isNotEmpty() || redirects.isNotEmpty()) {
                commands += Simple(assignments, words, redirects, piped, pipesOn)
                assignments = ArrayList()
                words = ArrayList()
                redirects = ArrayList()
            }
            piped = pipesOn
        }

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (patterns) {
                // Inside "case … in", up to the ")" that ends a pattern list: patterns are not commands.
                if (token is Op && token.text == ")") patterns = false
                else if (token is WordToken && token.word.bare && token.word.plain == "esac") {
                    patterns = false
                    cases--
                }
                // Not commands, but a "$(…)" in a pattern is one, and it runs.
                else if (token is WordToken) extras += token.word
                continue
            }
            when (token) {
                is Op -> {
                    finish(pipesOn = token.text == "|" || token.text == "|&")
                    if (token.text == ";;" && cases > 0) patterns = true
                }
                is Redirect -> redirects += token
                is WordToken -> {
                    val word = token.word
                    val starts = words.isEmpty()
                    val reserved = if (starts && word.bare) word.plain else null
                    when {
                        reserved == "for" || reserved == "select" -> {
                            val name = (tokens.getOrNull(i) as? WordToken)?.word?.plain
                            if (name != null) i++
                            val values = ArrayList<Word>()
                            if ((tokens.getOrNull(i) as? WordToken)?.word?.plain == "in") {
                                i++
                                while (i < tokens.size && tokens[i] is WordToken) values += (tokens[i++] as WordToken).word
                            }
                            if (name != null) loops += Loop(name, values)
                        }
                        reserved == "case" -> {
                            // The subject is no command, but a name in it is still seen.
                            (tokens.getOrNull(i) as? WordToken)?.let {
                                extras += it.word
                                i++
                            }
                            if ((tokens.getOrNull(i) as? WordToken)?.word?.plain == "in") i++
                            cases++
                            patterns = true
                        }
                        reserved == "esac" -> if (cases > 0) cases--
                        reserved == "function" -> if (tokens.getOrNull(i) is WordToken) i++
                        // "time -p command": its options are not the command.
                        reserved == "time" -> while ((tokens.getOrNull(i) as? WordToken)?.word?.let { it.bare && it.plain.orEmpty().startsWith("-") } == true) i++
                        reserved != null && reserved in TRANSPARENT -> Unit
                        starts && assignmentOf(word) != null -> assignments += assignmentOf(word)!!
                        else -> words += word
                    }
                }
            }
        }
        finish(pipesOn = false)
        return Script(commands, loops, extras, tokens.mapNotNull { (it as? Op)?.text ?: (it as? Redirect)?.op }, lexer.doubts.distinct(), openCase = cases > 0)
    }

    private fun assignmentOf(word: Word): Pair<String, Word>? = word.assignment()

    /** One word, as the lexer reads it from [text] (which holds nothing else). */
    fun word(text: String): Word? = (Lexer(text).run().singleOrNull() as? WordToken)?.word

    /** A leading unquoted `~` or `~/…` is the home folder; `~name` is only known at run time. */
    private fun tilde(pieces: List<Piece>): List<Piece> {
        val first = pieces.firstOrNull() ?: return pieces
        if (first.kind != Kind.LIT || first.quoted || !first.text.startsWith("~")) return pieces
        val rest = first.text.substring(1)
        val tail = pieces.drop(1)
        return when {
            rest.isEmpty() && tail.isEmpty() -> listOf(Piece(Kind.VAR, "HOME", false))
            rest.startsWith("/") -> listOf(Piece(Kind.VAR, "HOME", false), first.copy(text = rest)) + tail
            else -> listOf(Piece(Kind.DYN, first.text, false)) + tail
        }
    }

    private class Lexer(private val s: String) {
        private var i = 0
        private val out = ArrayList<Token>()
        private val pending = ArrayList<Redirect>()
        val doubts = ArrayList<String>()

        fun run(): List<Token> {
            if ('\r' in s) doubts += DOUBT_CARRIAGE_RETURN
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\n' -> {
                        out += Op("\n")
                        i++
                        hereDocuments()
                    }
                    c == ' ' || c == '\t' || c == '\r' -> i++
                    c == '\\' && s.getOrNull(i + 1) == '\n' -> i += 2
                    c == '#' -> while (i < s.length && s[i] != '\n') i++
                    c == ';' -> operator(if (s.getOrNull(i + 1) == ';') ";;" else ";")
                    c == '&' -> when (s.getOrNull(i + 1)) {
                        '&' -> operator("&&")
                        '>' -> {
                            i += if (s.getOrNull(i + 2) == '>') 3 else 2
                            redirect("&>")
                        }
                        else -> operator("&")
                    }
                    c == '|' -> when (s.getOrNull(i + 1)) {
                        '|' -> operator("||")
                        '&' -> operator("|&")
                        else -> operator("|")
                    }
                    c == '(' || c == ')' -> operator(c.toString())
                    c == '<' || c == '>' -> redirection()
                    else -> word()
                }
            }
            // A here-document announced on the last line has no text at all.
            if (pending.isNotEmpty()) doubts += DOUBT_HERE_OPEN
            return out
        }

        /** [s] as the text of a here-document whose end word is not quoted: literal text and what the shell expands in it. */
        fun expansionsOnly(): List<Piece> {
            val pieces = ArrayList<Piece>()
            val add = { kind: Kind, text: String, quoted: Boolean -> if (kind != Kind.LIT) pieces += Piece(kind, text, quoted) }
            while (i < s.length) {
                val d = s[i]
                when {
                    d == '\\' && i + 1 < s.length -> i += 2
                    d == '$' -> dollar(true, add)
                    d == '`' -> backticks(true, add)
                    else -> i++
                }
            }
            return pieces
        }

        private fun operator(text: String) {
            out += Op(text)
            i += text.length
        }

        private fun redirection() {
            val op = listOf("<<<", "<<-", "<<", "<&", "<>", "<", ">>", ">&", ">|", ">").first { s.startsWith(it, i) }
            i += op.length
            // "<(…)" and ">(…)": mksh has no such thing, and what other shells make of it is not what is read here.
            if ((op == "<" || op == ">") && s.getOrNull(i) == '(') doubts += DOUBT_PROCESS_SUBSTITUTION
            redirect(op)
        }

        /** The operator has been read; its target follows, after blanks. */
        private fun redirect(op: String) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            val target = if (i < s.length && s[i] !in "\n;&|()<>") readWord() else null
            val redirect = Redirect(op, target)
            out += redirect
            if (op == "<<" || op == "<<-") pending += redirect
        }

        /**
         * After a line break: the text of every here-document started on the line before, up to
         * its end word. Only what is beyond doubt is read: the end word must be a plain word, and
         * when it is not quoted, no line of the text may end in a backslash, because the shell
         * joins such a line to the next one before it looks for the end word (`EO\` + `F` ends a
         * document at `EOF`).
         */
        private fun hereDocuments() {
            for (redirect in pending) {
                val raw = redirect.target?.raw
                if (raw == null || !PLAIN_END.matches(raw)) {
                    doubts += DOUBT_HERE_END
                    continue
                }
                val quoted = raw[0] == '\'' || raw[0] == '"'
                val end = raw.trim('\'', '"')
                val body = StringBuilder()
                var closed = false
                while (i < s.length) {
                    val lineEnd = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    val line = s.substring(i, lineEnd)
                    i = minOf(s.length, lineEnd + 1)
                    if ((if (redirect.op == "<<-") line.trimStart('\t') else line) == end) {
                        closed = true
                        break
                    }
                    if (!quoted && line.endsWith("\\")) doubts += DOUBT_HERE_CONTINUED
                    body.append(line).append('\n')
                }
                if (!closed) doubts += DOUBT_HERE_OPEN
                redirect.body = body.toString()
                redirect.quotedEnd = quoted
                if (!quoted) {
                    val inner = Lexer(body.toString())
                    redirect.expansions = inner.expansionsOnly()
                    doubts += inner.doubts
                }
            }
            pending.clear()
        }

        private fun word() {
            val word = readWord()
            // "2>file": the digits are the redirection's file number, not a word.
            val number = word.bare && word.plain.orEmpty().let { it.isNotEmpty() && it.all(Char::isDigit) }
            if (number && i < s.length && (s[i] == '<' || s[i] == '>')) redirection() else out += WordToken(word)
        }

        private fun readWord(): Word {
            val start = i
            val pieces = ArrayList<Piece>()

            fun add(kind: Kind, text: String, quoted: Boolean) {
                val last = pieces.lastOrNull()
                if (kind == Kind.LIT && last != null && last.kind == Kind.LIT && last.quoted == quoted) pieces[pieces.size - 1] = last.copy(text = last.text + text)
                else pieces += Piece(kind, text, quoted)
            }

            while (i < s.length) {
                val c = s[i]
                when {
                    // "@(a|b)" and its relatives are one pattern to mksh, part of the word, not a subshell.
                    c == '(' && pieces.lastOrNull()?.let { it.kind == Kind.LIT && !it.quoted && it.text.last() in "@+?*!" } == true -> {
                        val end = closing(i)
                        add(Kind.PAT, s.substring(i, minOf(s.length, end + 1)), false)
                        i = minOf(s.length, end + 1)
                    }
                    c in " \t\r\n;&|()<>" -> break
                    c == '\\' -> {
                        when {
                            i + 1 >= s.length -> add(Kind.LIT, "\\", true)
                            s[i + 1] == '\n' -> Unit
                            else -> add(Kind.LIT, s[i + 1].toString(), true)
                        }
                        i += 2
                    }
                    c == '\'' -> {
                        val end = s.indexOf('\'', i + 1)
                        if (end < 0) doubts += DOUBT_UNCLOSED
                        add(Kind.LIT, s.substring(i + 1, if (end < 0) s.length else end), true)
                        i = if (end < 0) s.length else end + 1
                    }
                    c == '"' -> {
                        i++
                        add(Kind.LIT, "", true)
                        while (i < s.length && s[i] != '"') {
                            val d = s[i]
                            when {
                                d == '\\' && i + 1 < s.length && s[i + 1] in "$`\"\\" -> {
                                    add(Kind.LIT, s[i + 1].toString(), true)
                                    i += 2
                                }
                                d == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> i += 2
                                d == '$' -> dollar(true, ::add)
                                d == '`' -> backticks(true, ::add)
                                else -> {
                                    add(Kind.LIT, d.toString(), true)
                                    i++
                                }
                            }
                        }
                        if (i >= s.length) doubts += DOUBT_UNCLOSED
                        i = minOf(s.length, i + 1)
                    }
                    c == '$' -> dollar(false, ::add)
                    c == '`' -> backticks(false, ::add)
                    else -> {
                        add(Kind.LIT, c.toString(), false)
                        i++
                    }
                }
            }
            return Word(tilde(pieces), s.substring(start, minOf(i, s.length)))
        }

        private fun dollar(quoted: Boolean, add: (Kind, String, Boolean) -> Unit) {
            val next = s.getOrNull(i + 1)
            when {
                next == '(' -> {
                    val end = closing(i + 1)
                    val whole = s.substring(i, minOf(s.length, end + 1))
                    // "$((…))" is arithmetic only when the two brackets close together; "$( (a) )" is a command in a subshell.
                    val arithmetic = s.getOrNull(i + 2) == '(' && end < s.length && s[end - 1] == ')' && closing(i + 2) == end - 1
                    if (arithmetic) {
                        if ("$(" in whole.drop(2) || '`' in whole) doubts += DOUBT_HIDDEN_COMMAND
                        add(Kind.DYN, whole, quoted)
                    } else {
                        val inner = s.substring(i + 2, minOf(s.length, end))
                        if ("<<" in inner) doubts += DOUBT_HERE_NESTED
                        // A "case x in a) …" inside: the ")" found may be the pattern's. Then a case is left open in what was taken.
                        if (CASE.containsMatchIn(inner) && parse(inner).openCase) doubts += DOUBT_CASE
                        add(Kind.SUB, inner, quoted)
                    }
                    i = minOf(s.length, end + 1)
                }
                next == '{' -> {
                    val end = closingBrace(i + 1)
                    val inner = s.substring(i + 2, minOf(s.length, end))
                    if (NAME.matches(inner)) add(Kind.VAR, inner, quoted) else {
                        if ("$(" in inner || '`' in inner) doubts += DOUBT_HIDDEN_COMMAND
                        add(Kind.DYN, s.substring(i, minOf(s.length, end + 1)), quoted)
                    }
                    i = minOf(s.length, end + 1)
                }
                next != null && (next.isLetter() || next == '_') && next.code < 128 -> {
                    var end = i + 1
                    while (end < s.length && s[end].code < 128 && (s[end].isLetterOrDigit() || s[end] == '_')) end++
                    add(Kind.VAR, s.substring(i + 1, end), quoted)
                    i = end
                }
                next == '\'' && !quoted -> {
                    // $'…' with backslash escapes: literal only when it has none.
                    var end = i + 2
                    while (end < s.length && s[end] != '\'') end += if (s[end] == '\\') 2 else 1
                    if (end >= s.length) doubts += DOUBT_UNCLOSED
                    val inner = s.substring(minOf(s.length, i + 2), minOf(s.length, end))
                    if ('\\' in inner) add(Kind.DYN, s.substring(i, minOf(s.length, end + 1)), true) else add(Kind.LIT, inner, true)
                    i = minOf(s.length, end + 1)
                }
                next == '"' && !quoted -> i++
                next != null && (next.isDigit() || next in "@*#?$!-") -> {
                    add(Kind.DYN, s.substring(i, i + 2), quoted)
                    i += 2
                }
                else -> {
                    add(Kind.LIT, "$", quoted)
                    i++
                }
            }
        }

        private fun backticks(quoted: Boolean, add: (Kind, String, Boolean) -> Unit) {
            var end = i + 1
            val inner = StringBuilder()
            while (end < s.length && s[end] != '`') {
                if (s[end] == '\\' && end + 1 < s.length) {
                    // Inside double quotes shells disagree on what a backslash before a quote means here.
                    if (quoted && s[end + 1] == '"') doubts += DOUBT_BACKQUOTES
                    if (s[end + 1] in "`\\$") end++
                }
                inner.append(s[end])
                end++
            }
            if (end >= s.length) doubts += DOUBT_UNCLOSED
            if ("<<" in inner) doubts += DOUBT_HERE_NESTED
            add(Kind.SUB, inner.toString(), quoted)
            i = minOf(s.length, end + 1)
        }

        /**
         * The index of the ")" that closes the "(" at [open], minding nesting, quotes, backslashes,
         * comments, and the `$(…)`, `${…}` and backquotes inside double quotes; the end of the text
         * when there is none (a doubt).
         */
        private fun closing(open: Int): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '\'' -> j = s.indexOf('\'', j + 1).let { if (it < 0) s.length else it }
                    '"' -> j = closingQuote(j)
                    '`' -> j = closingBacktick(j)
                    '$' -> if (s.getOrNull(j + 1) == '{') j = closingBrace(j + 1)
                    // A comment runs to the end of its line, brackets and quotes in it included.
                    '#' -> if (j == open + 1 || s[j - 1] in " \t\n;&|(") j = (s.indexOf('\n', j).let { if (it < 0) s.length else it }) - 1
                    '(' -> depth++
                    ')' -> if (--depth == 0) return j
                }
                j++
            }
            doubts += DOUBT_UNCLOSED
            return s.length
        }

        /** The index of the `"` that closes the one at [open]; what it holds may itself hold `$(…)` with quotes of its own. */
        private fun closingQuote(open: Int): Int {
            var j = open + 1
            while (j < s.length && s[j] != '"') {
                when {
                    s[j] == '\\' -> j++
                    s[j] == '$' && s.getOrNull(j + 1) == '(' -> j = closing(j + 1)
                    s[j] == '$' && s.getOrNull(j + 1) == '{' -> j = closingBrace(j + 1)
                    s[j] == '`' -> j = closingBacktick(j)
                }
                j++
            }
            return minOf(j, s.length)
        }

        private fun closingBacktick(open: Int): Int {
            var j = open + 1
            while (j < s.length && s[j] != '`') j += if (s[j] == '\\') 2 else 1
            return minOf(j, s.length)
        }

        /** The index of the "}" that closes the "{" at [open] (of a `${…}`), minding quotes and what is nested. */
        private fun closingBrace(open: Int): Int {
            var j = open + 1
            while (j < s.length) {
                when {
                    s[j] == '\\' -> j++
                    s[j] == '\'' -> j = s.indexOf('\'', j + 1).let { if (it < 0) s.length else it }
                    s[j] == '"' -> j = closingQuote(j)
                    s[j] == '`' -> j = closingBacktick(j)
                    s[j] == '$' && s.getOrNull(j + 1) == '(' -> j = closing(j + 1)
                    s[j] == '$' && s.getOrNull(j + 1) == '{' -> j = closingBrace(j + 1)
                    s[j] == '}' -> return j
                }
                j++
            }
            doubts += DOUBT_UNCLOSED
            return s.length
        }
    }

    private val CASE = Regex("""(?:^|[\s;&|(])case\s""")
}
