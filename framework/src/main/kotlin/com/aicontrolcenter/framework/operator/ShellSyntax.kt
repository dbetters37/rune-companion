package com.aicontrolcenter.framework.operator

/**
 * A reading of shell text, as far as it can be read without running it: words with their quotes
 * and backslashes taken off, the operators between them, and the simple commands they form.
 * [ShellCommands] decides on top of this. It follows the POSIX shell grammar closely enough for
 * what Android's `sh` (mksh) does with static text; whatever a command builds only when it runs
 * (`$x`, `$(…)`) stays marked as such, never guessed.
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

        /**
         * For a here-document: its end word is a plain name (`EOF`, `'EOF'`, `"END"`), so where the
         * document ends is beyond doubt. With any other end word the text taken as [body] may
         * differ from what the shell takes, and must not be passed over as mere data.
         */
        val plainEnd: Boolean get() = target != null && PLAIN_END.matches(target.raw)
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
     */
    class Script(val commands: List<Simple>, val loops: List<Loop>, val extras: List<Word>, val operators: List<String>)

    private val PLAIN_END = Regex("""^(?:[A-Za-z_][A-Za-z0-9_]*|'[A-Za-z_][A-Za-z0-9_]*'|"[A-Za-z_][A-Za-z0-9_]*")$""")
    private val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=")
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val CONTROL = setOf(";", ";;", "&", "&&", "||", "|", "|&", "(", ")", "\n")

    /** Words that, where a command starts, are followed by another command start. */
    private val TRANSPARENT = setOf("if", "then", "else", "elif", "fi", "do", "done", "while", "until", "!", "{", "}", "time")

    /** Reads [text] into simple commands. Never throws: unfinished quotes run to the end. */
    fun parse(text: String): Script {
        val tokens = Lexer(text).run()
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
                        reserved != null && reserved in TRANSPARENT -> Unit
                        starts && assignmentOf(word) != null -> assignments += assignmentOf(word)!!
                        else -> words += word
                    }
                }
            }
        }
        finish(pipesOn = false)
        return Script(commands, loops, extras, tokens.mapNotNull { (it as? Op)?.text ?: (it as? Redirect)?.op })
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

        fun run(): List<Token> {
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
            return out
        }

        private fun operator(text: String) {
            out += Op(text)
            i += text.length
        }

        private fun redirection() {
            val op = listOf("<<<", "<<-", "<<", "<&", "<>", "<", ">>", ">&", ">|", ">").first { s.startsWith(it, i) }
            i += op.length
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

        /** After a line break: the text of every here-document started on the line before, up to its end word. */
        private fun hereDocuments() {
            for (redirect in pending) {
                // The shell does not expand the end word; it only takes its quotes off.
                val end = redirect.target?.raw?.filterNot { it == '\'' || it == '"' || it == '\\' } ?: break
                val body = StringBuilder()
                while (i < s.length) {
                    val lineEnd = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    val line = s.substring(i, lineEnd)
                    i = minOf(s.length, lineEnd + 1)
                    if ((if (redirect.op == "<<-") line.trimStart('\t') else line) == end) break
                    body.append(line).append('\n')
                }
                redirect.body = body.toString()
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
                        val end = s.indexOf('\'', i + 1).let { if (it < 0) s.length else it }
                        add(Kind.LIT, s.substring(i + 1, end), true)
                        i = minOf(s.length, end + 1)
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
                next == '(' && s.getOrNull(i + 2) == '(' -> {
                    val end = closing(i + 1)
                    add(Kind.DYN, s.substring(i, minOf(s.length, end + 1)), quoted)
                    i = minOf(s.length, end + 1)
                }
                next == '(' -> {
                    val end = closing(i + 1)
                    add(Kind.SUB, s.substring(i + 2, minOf(s.length, end)), quoted)
                    i = minOf(s.length, end + 1)
                }
                next == '{' -> {
                    val end = s.indexOf('}', i + 2).let { if (it < 0) s.length else it }
                    val inner = s.substring(i + 2, end)
                    if (NAME.matches(inner)) add(Kind.VAR, inner, quoted) else add(Kind.DYN, s.substring(i, minOf(s.length, end + 1)), quoted)
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
                if (s[end] == '\\' && end + 1 < s.length && s[end + 1] in "`\\$") end++
                inner.append(s[end])
                end++
            }
            add(Kind.SUB, inner.toString(), quoted)
            i = minOf(s.length, end + 1)
        }

        /** The index of the ")" that closes the "(" at [open], minding nesting, quotes and backslashes; the end when there is none. */
        private fun closing(open: Int): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '\'' -> j = s.indexOf('\'', j + 1).let { if (it < 0) s.length else it }
                    '"' -> {
                        j++
                        while (j < s.length && s[j] != '"') j += if (s[j] == '\\') 2 else 1
                    }
                    '(' -> depth++
                    ')' -> if (--depth == 0) return j
                }
                j++
            }
            return s.length
        }
    }
}
