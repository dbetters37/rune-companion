package com.aicontrolcenter.core.operator

/**
 * A normalised path inside the workspace or a shared folder: "/"-separated names, no "." or
 * "..", never absolute. The only way to name a file to a gateway, so an agent's path cannot climb
 * out of its root. The empty path is the root itself. Symlinks are a file-system matter and are
 * refused by the workspace sandbox in `:framework`, not here.
 */
@JvmInline
value class RelativePath private constructor(val value: String) {
    val isRoot: Boolean get() = value.isEmpty()

    val segments: List<String> get() = if (value.isEmpty()) emptyList() else value.split('/')

    /** The last name; empty for the root. */
    val name: String get() = value.substringAfterLast('/')

    /** The containing path; null for the root. */
    val parent: RelativePath? get() = if (isRoot) null else RelativePath(value.substringBeforeLast('/', ""))

    /** This path with one more name. @throws IllegalArgumentException if [name] is not a valid single name. */
    fun child(name: String): RelativePath {
        require('/' !in name) { "A name cannot contain \"/\"." }
        return of(if (isRoot) name else "$value/$name").also { require(it.segments.size == segments.size + 1) { "Invalid name." } }
    }

    override fun toString(): String = value

    /** The outcome of [parse]. */
    sealed interface Check {
        /** [path] is the normalised form. */
        data class Valid(val path: RelativePath) : Check

        /** [reason] is a sentence the model can act on. */
        data class Invalid(val reason: String) : Check
    }

    companion object {
        const val MAX_LENGTH = 1024
        const val MAX_DEPTH = 32
        const val MAX_NAME_BYTES = 255

        val ROOT = RelativePath("")

        /**
         * Normalises [raw]: empty names and "." are dropped ("a//./b/" is "a/b"). Refused:
         * a leading "/" (absolute), any ".." name, backslashes, control, invisible,
         * direction-changing and unusual space characters ([PreviewText.isHidden],
         * [PreviewText.isUnusualSpace]), names that start or end with a space, more than
         * [MAX_LENGTH] characters or [MAX_DEPTH] names, and names longer than [MAX_NAME_BYTES]
         * bytes in UTF-8.
         */
        fun parse(raw: String): Check {
            if (raw.length > MAX_LENGTH) return Check.Invalid("The path is longer than $MAX_LENGTH characters.")
            raw.firstOrNull(::isForbiddenChar)?.let {
                return Check.Invalid("The path contains a character that is not allowed (U+%04X).".format(it.code))
            }
            if ('\\' in raw) return Check.Invalid("Use \"/\" to separate folders, not \"\\\".")
            if (raw.startsWith("/")) return Check.Invalid("Use a path relative to the folder, without a leading \"/\".")
            val names = raw.split('/').filter { it.isNotEmpty() && it != "." }
            if (names.any { it == ".." }) return Check.Invalid("\"..\" is not allowed in a path.")
            names.firstOrNull { it != it.trim() }?.let {
                return Check.Invalid("The name \"${it.trim().take(40)}\" starts or ends with a space; remove it.")
            }
            if (names.size > MAX_DEPTH) return Check.Invalid("The path is deeper than $MAX_DEPTH folders.")
            names.firstOrNull { it.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES }?.let {
                return Check.Invalid("The name \"${it.take(40)}…\" is too long.")
            }
            return Check.Valid(RelativePath(names.joinToString("/")))
        }

        /** [parse] for trusted input. @throws IllegalArgumentException if it is not valid. */
        fun of(raw: String): RelativePath = when (val check = parse(raw)) {
            is Check.Valid -> check.path
            is Check.Invalid -> throw IllegalArgumentException(check.reason)
        }

        private fun isForbiddenChar(c: Char): Boolean =
            c.isISOControl() || PreviewText.isHidden(c) || PreviewText.isUnusualSpace(c.code)
    }
}
