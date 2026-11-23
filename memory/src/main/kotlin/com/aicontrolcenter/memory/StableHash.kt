package com.aicontrolcenter.memory

/**
 * A 64-bit hash that is the same on every device, JVM and app version: FNV-1a over the UTF-16
 * code units, followed by MurmurHash3's 64-bit finaliser to spread FNV's weak low bits.
 *
 * Stored embeddings depend on it, so it must never change for an existing embedder id; and it
 * must never be [String.hashCode], whose 32 bits collide far too often for feature hashing.
 */
internal object StableHash {
    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun hash64(text: CharSequence, seed: Long = 0L): Long {
        var h = FNV_OFFSET_BASIS xor seed
        for (i in 0 until text.length) {
            val unit = text[i].code
            h = (h xor (unit and 0xFF).toLong()) * FNV_PRIME
            h = (h xor (unit ushr 8).toLong()) * FNV_PRIME
        }
        return fmix64(h)
    }

    /** MurmurHash3 fmix64: every input bit affects every output bit. */
    fun fmix64(value: Long): Long {
        var k = value
        k = k xor (k ushr 33)
        k *= -0xae502812aa7333L // 0xff51afd7ed558ccd
        k = k xor (k ushr 33)
        k *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        k = k xor (k ushr 33)
        return k
    }

    /** A value in [0, 1) derived from [text] and [salt], for deterministic pseudo-random choices. */
    fun unit(text: CharSequence, salt: Long): Double =
        (hash64(text, salt) ushr 11).toDouble() / (1L shl 53).toDouble()
}
