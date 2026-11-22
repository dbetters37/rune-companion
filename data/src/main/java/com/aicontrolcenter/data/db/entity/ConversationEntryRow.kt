package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo

/**
 * An entry as the list queries return it: every small column, and of the message only its
 * first [HEAD_BYTES] bytes together with its full size.
 *
 * Android hands query results over in a cursor window of about 2 MB, and a row that does not
 * fit into one cannot be read at all. A message with a photo attached is easily larger than
 * that, and it would make its whole conversation unreadable. So a message is fetched as UTF-8
 * bytes in pieces that always fit: the head with the row, and the rest, only when there is
 * more, with `ConversationDao.messageBytes`.
 */
internal class ConversationEntryRow(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Int,
    val at: Long,
    @ColumnInfo(name = "model_name") val modelName: String?,
    @ColumnInfo(name = "usage_json") val usageJson: String?,
    val error: String?,
    @ColumnInfo(name = "message_head") val messageHead: ByteArray,
    @ColumnInfo(name = "message_bytes") val messageBytes: Int,
) {
    companion object {
        /** Large enough that an ordinary message arrives complete with its row. */
        const val HEAD_BYTES = 256 * 1024

        /** Size of the further pieces; half a cursor window. */
        const val PART_BYTES = 1024 * 1024
    }
}
