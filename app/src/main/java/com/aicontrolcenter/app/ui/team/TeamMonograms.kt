package com.aicontrolcenter.app.ui.team

import com.aicontrolcenter.app.ui.components.monogramOf

/**
 * The text in each member's circle of one picture (D3B-2): its usual initials ("R" for Researcher, "PO" for Phone
 * Operator), unless another member of the same picture would read the same. Then each of them gets a text of its own,
 * so no two circles are told apart by colour alone: the first letter and the trailing number ("H10" for Helper 10), or
 * the first letter and the next one that sets it apart ("Re" for Researcher, "Rv" for Reviewer).
 */
object TeamMonograms {
    /** The circle texts of [names], in their order, no two alike (ignoring case). */
    fun forNames(names: List<String>): List<String> {
        val base = names.map { monogramOf(it) }
        val counts = base.groupingBy { it.uppercase() }.eachCount()
        val taken = base.filter { counts.getValue(it.uppercase()) == 1 }.mapTo(HashSet()) { it.uppercase() }
        return names.mapIndexed { index, name ->
            val own = base[index]
            if (counts.getValue(own.uppercase()) == 1) return@mapIndexed own
            val pick = candidates(name, own).first { it.uppercase() !in taken }
            taken += pick.uppercase()
            pick
        }
    }

    /** [names] to their circle texts (names that appear twice share the first one's entry). */
    fun of(names: List<String>): Map<String, String> = names.zip(forNames(names)).toMap()

    private fun candidates(name: String, own: String): Sequence<String> = sequence {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = own.take(1).ifEmpty { "?" }
        // "Helper 10" -> "H10": a name's number sets it apart best.
        val number = words.lastOrNull()?.takeIf { words.size > 1 && it.all(Char::isDigit) }
        if (number != null) yield(first + number.take(2))
        // "Researcher" -> "Re", "Reviewer" -> "Rv": the first letter and one more of the name, in its order.
        for (c in name.trim().drop(1)) if (c.isLetterOrDigit()) yield(first + c.lowercaseChar())
        // Two names alike to the last letter: numbered.
        for (n in 2..999) yield("$first$n")
    }
}
