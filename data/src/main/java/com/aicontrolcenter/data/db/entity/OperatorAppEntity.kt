package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per app the owner authorised for screen operation (`OperatorApp`), keyed by package
 * name. The single record of which apps may be operated: the screen tools read this table only.
 */
@Entity(tableName = OperatorAppEntity.TABLE)
internal data class OperatorAppEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    val label: String,
    @ColumnInfo(name = "authorised_at") val authorisedAt: Long,
    @ColumnInfo(name = "signing_cert_sha256") val signingCertSha256: String?,
) {
    companion object {
        const val TABLE = "operator_apps"
    }
}
