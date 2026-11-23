package com.aicontrolcenter.data.db.mapper

/**
 * How many bytes this text takes in the database, which stores UTF-8, without encoding it.
 * Unpaired surrogates count as the 3 bytes of the replacement character SQLite stores.
 *
 * The repositories use it to refuse rows that could never be read back: Android hands query
 * results over in a cursor window of about 2 MB, and a row larger than that makes every list
 * containing it fail.
 */
internal fun String.utf8Length(): Long {
    var bytes = 0L
    var i = 0
    while (i < length) {
        val c = this[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return bytes
}
