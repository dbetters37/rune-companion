package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.data.StoredDataException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The stored form of an embedding vector: each value as an IEEE-754 float32, little-endian,
 * one after the other, 4 bytes per dimension.
 *
 * The byte order is fixed rather than the platform's so that a database (or an export of it)
 * means the same on any machine. Values are copied bit for bit, so `-0.0` and NaN payloads
 * survive, and two vectors that were equal before storing are equal after.
 */
internal object EmbeddingCodec {
    const val BYTES_PER_DIMENSION = 4

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * BYTES_PER_DIMENSION).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    /**
     * @throws StoredDataException when [bytes] is not exactly [dimensions] values long; [row]
     *   names the vector in the message.
     */
    fun decode(bytes: ByteArray, dimensions: Int, row: RowRef): FloatArray {
        if (dimensions < 0 || bytes.size.toLong() != dimensions.toLong() * BYTES_PER_DIMENSION) {
            throw StoredDataException(
                "Column ${row.table}.vector of row '${row.rowId}' holds ${bytes.size} bytes, not $dimensions float32 values.",
            )
        }
        val vector = FloatArray(dimensions)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector)
        return vector
    }
}
