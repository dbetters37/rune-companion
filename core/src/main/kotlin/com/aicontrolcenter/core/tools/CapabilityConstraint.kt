package com.aicontrolcenter.core.tools

import com.aicontrolcenter.core.FolderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a capability's grants are narrowed to. Declared per capability in [CapabilityInfo.constrainedBy]. */
enum class ConstraintKind {
    /** Grants cover the capability as a whole. */
    NONE,

    /** Every grant and every call names one shared folder. */
    FOLDER,

    /** Every grant and every call names one app by package name. */
    APP,
}

/**
 * The part of the world one grant or one call is limited to: nothing, one shared folder, or one
 * app. A grant covers a call only when both carry the *same* constraint, so a grant without a
 * constraint never covers a constrained call, and a folder grant never covers another folder.
 *
 * [stored] is the stable text form for databases: `""`, `"folder:<id>"` or `"app:<package>"`.
 */
@Serializable
sealed interface CapabilityConstraint {
    val kind: ConstraintKind

    /** Stable text form; [parse] reads it back. */
    val stored: String

    /** Not narrowed. */
    @Serializable
    @SerialName("none")
    data object None : CapabilityConstraint {
        override val kind get() = ConstraintKind.NONE
        override val stored get() = ""
    }

    /** One folder the owner picked. */
    @Serializable
    @SerialName("folder")
    data class Folder(val folderId: FolderId) : CapabilityConstraint {
        init {
            require(isValidFolderId(folderId.value)) { "Invalid folder id." }
        }

        override val kind get() = ConstraintKind.FOLDER
        override val stored get() = FOLDER_PREFIX + folderId.value
    }

    /** One installed app, by package name. */
    @Serializable
    @SerialName("app")
    data class App(val packageName: String) : CapabilityConstraint {
        init {
            require(isValidPackageName(packageName)) { "Invalid package name: ${packageName.take(80)}" }
        }

        override val kind get() = ConstraintKind.APP
        override val stored get() = APP_PREFIX + packageName
    }

    companion object {
        private const val FOLDER_PREFIX = "folder:"
        private const val APP_PREFIX = "app:"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

        /** Reads a [stored] form. @throws IllegalArgumentException for anything else. */
        fun parse(stored: String): CapabilityConstraint = when {
            stored.isEmpty() -> None
            stored.startsWith(FOLDER_PREFIX) -> Folder(FolderId(stored.removePrefix(FOLDER_PREFIX)))
            stored.startsWith(APP_PREFIX) -> App(stored.removePrefix(APP_PREFIX))
            else -> throw IllegalArgumentException("Unknown capability constraint: ${stored.take(80)}")
        }

        /** An Android package name: dot-separated Java identifiers, at most 255 characters. */
        fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)

        private fun isValidFolderId(id: String): Boolean =
            id.isNotBlank() && id.length <= 128 && id.none { it.isISOControl() || it.isWhitespace() }
    }
}
