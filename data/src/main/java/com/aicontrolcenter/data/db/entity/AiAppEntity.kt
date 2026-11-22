package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per registered AI application (an installed Android package, not a model or agent).
 *
 * `package_name` is indexed for lookups but not declared unique: the port's reference
 * implementation accepts whatever the registry service writes, and uniqueness per package is
 * that service's rule to enforce. A lookup by package returns the oldest entry.
 *
 * A row with `hidden` set is an app the owner removed although scans still find it. It is
 * stored and returned like any other row; leaving it out of lists is the registry service's job.
 */
@Entity(
    tableName = AiAppEntity.TABLE,
    indices = [
        Index(value = ["first_seen_at", "id"]),
        Index(value = ["package_name"]),
        Index(value = ["linked_provider_id"]),
    ],
)
internal data class AiAppEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    val label: String,
    val vendor: String?,
    @ColumnInfo(name = "version_name") val versionName: String?,
    val source: String,
    @ColumnInfo(name = "capabilities_json") val capabilitiesJson: String,
    val status: String,
    val enabled: Boolean,
    @ColumnInfo(name = "accessibility_authorized") val accessibilityAuthorized: Boolean,
    @ColumnInfo(name = "linked_provider_id") val linkedProviderId: String?,
    @ColumnInfo(name = "prompt_deep_link") val promptDeepLink: String?,
    val notes: String,
    @ColumnInfo(name = "first_install_time") val firstInstallTime: Long?,
    @ColumnInfo(name = "signing_cert_sha256") val signingCertSha256: String?,
    @ColumnInfo(name = "verified_by_user") val verifiedByUser: Boolean,
    val hidden: Boolean,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
) {
    companion object {
        const val TABLE = "ai_apps"
    }
}
