package com.aicontrolcenter.data

/**
 * Thrown when something read back from storage cannot be turned into a domain object, for
 * example a JSON column that no longer parses or an enum name this build does not know.
 *
 * It names the table, column and row so the damaged record can be found, and never includes
 * the stored content itself.
 */
class StoredDataException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
