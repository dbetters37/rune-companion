package com.aicontrolcenter.memory

import kotlin.math.sqrt

internal object VectorMath {
    /**
     * Cosine similarity, or 0 when it is undefined: different lengths (vectors from different
     * models, or the empty marker of a stale embedding), a zero vector, or non-finite values.
     * Normalises itself, so embedders whose output is not exactly unit length still compare.
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        val c = dot / (sqrt(na) * sqrt(nb))
        return if (c.isFinite()) c.coerceIn(-1.0, 1.0) else 0.0
    }

    fun isZero(v: FloatArray): Boolean = v.all { it == 0f }
}
