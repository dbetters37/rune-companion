package com.aicontrolcenter.app.ui.components

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 27 (owner input is exact), as a scan of the app's source: every text field holds its text
 * in a `TextFieldState` that the field reads and the keyboard writes directly. A field fed a
 * `value` from view-model state (the cause of the owner's report #42) cannot be written.
 *
 * - Every call of a text field (`TextField(`, `OutlinedTextField(`, `BasicTextField(`, the secure
 *   fields and the app's own `AppTextField(` and `SecretTextField(`) passes `state =`, and none
 *   passes `value =` or `onValueChange =`.
 * - `TextFieldValue`, the old overload's type, is not used.
 * - A `TextFieldState` is created only in a view model (once, for the screen's life) or inside a
 *   `remember`, never afresh in a composition from a value that changes.
 */
class TextFieldRuleTest {

    private val sources: List<File> by lazy {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `every text field in the app takes its text from a TextFieldState`() {
        val calls = sources.flatMap { file -> textFieldCalls(file.readText()).map { file to it } }
        assertTrue(calls.size >= 30, "the scan found only ${calls.size} text fields; is it reading the app's source?")
        val wrong = calls.filterNot { (_, call) -> call.isStateBased }.map { (file, call) -> "${file.name}:${call.line} ${call.name}(${call.arguments.joinToString()})" }
        assertTrue(wrong.isEmpty(), "Text fields not bound to a TextFieldState (use the `state =` overload):\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `nothing uses the old TextFieldValue`() {
        val users = sources.filter { Regex("""\bTextFieldValue\b""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue(users.isEmpty(), "TextFieldValue is used in $users")
    }

    @Test
    fun `a TextFieldState is created only in a view model or inside remember`() {
        val wrong = sources.filterNot { it.name.contains("ViewModel") }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                val creates = Regex("""(?<![\w.])TextFieldState\s*\(""").containsMatchIn(code)
                if (creates && !code.contains("remember")) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(wrong.isEmpty(), "TextFieldState created outside a view model and outside remember:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `the scan tells a bound field from one fed a value`() {
        val source = """
            fun AppTextField(state: TextFieldState, label: String) = Unit
            @Composable fun Screen(state: UiState, name: TextFieldState) {
                AppTextField(state = name, label = "Name (a)")
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { onText(it) },
                    supportingText = { Text("count: ${'$'}{state.text.length}") },
                )
                OutlinedSecureTextField(state = key, label = { Text("Key") })
                BasicTextField(value = "x", onValueChange = {})
                Text("TextField(value = not code)")
                // OutlinedTextField(value = commented out)
            }
        """.trimIndent()
        val calls = textFieldCalls(source)
        assertEquals(listOf("AppTextField", "OutlinedTextField", "OutlinedSecureTextField", "BasicTextField"), calls.map { it.name })
        assertEquals(listOf(true, false, true, false), calls.map { it.isStateBased })
        assertEquals(listOf("value = state.text", "onValueChange = { onText(it) }", "supportingText = { Text(\"count: \${state.text.length}\") }"), calls[1].arguments)
    }

    /** One call of a text field: its name, its line, and its arguments at the top level. */
    private data class FieldCall(val name: String, val line: Int, val arguments: List<String>) {
        val isStateBased: Boolean
            get() = arguments.any { it.startsWith("state =") || it.startsWith("state=") } &&
                arguments.none { it.startsWith("value =") || it.startsWith("value=") || it.startsWith("onValueChange") }
    }

    /** Every call (not declaration) of a function whose name ends in `TextField`, outside comments and strings. */
    private fun textFieldCalls(source: String): List<FieldCall> {
        val code = blankCommentsAndStrings(source)
        return Regex("""\b(\w*TextField)\s*\(""").findAll(code).mapNotNull { match ->
            val before = code.substring(0, match.range.first).trimEnd()
            if (before.endsWith("fun") || before.endsWith("import")) return@mapNotNull null
            val open = match.range.last
            val close = closingParenthesis(code, open)
            val arguments = topLevelArguments(source.substring(open + 1, close), code.substring(open + 1, close))
            FieldCall(match.groupValues[1], source.substring(0, match.range.first).count { it == '\n' } + 1, arguments)
        }.toList()
    }

    /** [source] with every comment and string literal replaced by spaces, so offsets stay the same. */
    private fun blankCommentsAndStrings(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        fun blank(from: Int, to: Int) {
            for (k in from until to) if (out[k] != '\n') out[k] = ' '
        }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blank(i, end); i = end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    blank(i, end); i = end
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    blank(i, end); i = end
                }
                source[i] == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                    val end = minOf(j + 1, source.length)
                    blank(i, end); i = end
                }
                source[i] == '\'' && i + 2 < source.length && (source[i + 2] == '\'' || source[i + 1] == '\\') -> {
                    val end = source.indexOf('\'', i + 2).let { if (it < 0) source.length else it + 1 }
                    blank(i, end); i = end
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private fun closingParenthesis(code: String, open: Int): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (--depth == 0) return i
            }
        }
        error("unbalanced parentheses after offset $open")
    }

    /** The arguments of a call, split at the commas of its top level ([code] has strings blanked; [text] is the original). */
    private fun topLevelArguments(text: String, code: String): List<String> {
        val arguments = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in code.indices) {
            when (code[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    arguments += text.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        arguments += text.substring(start).trim()
        return arguments.filter { it.isNotEmpty() }.map { it.replace(Regex("""\s+"""), " ") }
    }
}
