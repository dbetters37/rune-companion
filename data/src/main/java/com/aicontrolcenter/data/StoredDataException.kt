package com.aicontrolcenter.data

/**
 * Thrown when something read back from storage cannot be turned into a domain object, for
 * example a JSON column that no longer parses or an enum name this build does not know.
 *
 * It names the table, column and row so the damaged record can be found, and never includes
 * the stored content itself (an unknown enum name is the one exception).
 *
 * The repositories do not let it escape: they treat such a row as absent and log this
 * exception's message. It is public for code that works with the mappers' results directly.
 */
class StoredDataException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
