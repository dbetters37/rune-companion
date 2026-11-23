package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per permission grant (`PermissionGrant`). Rows are inserted once and never replaced
 * or deleted: revoking sets [revokedAt], using up a ONCE grant sets [consumedAt], each by one
 * conditional `UPDATE` that only matches a grant still in force.
 *
 * - [constraintKey] is `CapabilityConstraint.stored` (`""`, `"folder:<id>"`, `"app:<package>"`);
 *   `constraint` is an SQL keyword.
 * - [scope] is the name of a `GrantScope`.
 *
 * Indexes: the broker's lookup `(agent_id, capability, constraint_key)`, which also serves the
 * per-agent listing, and `request_id`, for the waiter of a request.
 */
@Entity(
    tableName = PermissionGrantEntity.TABLE,
    indices = [
        Index(value = ["agent_id", "capability", "constraint_key"]),
        Index(value = ["request_id"]),
    ],
)
internal data class PermissionGrantEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val capability: String,
    @ColumnInfo(name = "constraint_key") val constraintKey: String,
    val scope: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "granted_at") val grantedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
    @ColumnInfo(name = "consumed_at") val consumedAt: Long?,
) {
    companion object {
        const val TABLE = "permission_grants"
    }
}
