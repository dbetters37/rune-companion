package com.aicontrolcenter.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every icon the app draws, in one namespace and one style (Material "Rounded").
 *
 * `material-icons-core` only ships a few dozen icons and `material-icons-extended` would add
 * several megabytes to the APK, so the extra vectors are built here from the same path data.
 * They are declared as extension properties in the sibling files of this package
 * (`SectionIcons`, `StatusIcons`, `ActionIcons`, `DomainIcons`, `DetailIcons`); the members
 * below simply re-export the core icons so that screens never mix two icon sources.
 */
object AppIcons {
    val Add: ImageVector get() = Icons.Rounded.Add
    val Back: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
    val Forward: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowForward
    val Check: ImageVector get() = Icons.Rounded.Check
    val Close: ImageVector get() = Icons.Rounded.Close
    val Delete: ImageVector get() = Icons.Rounded.Delete
    val Edit: ImageVector get() = Icons.Rounded.Edit
    val Info: ImageVector get() = Icons.Rounded.Info
    val Lock: ImageVector get() = Icons.Rounded.Lock
    val MoreVert: ImageVector get() = Icons.Rounded.MoreVert
    val Person: ImageVector get() = Icons.Rounded.Person
    val Play: ImageVector get() = Icons.Rounded.PlayArrow
    val Refresh: ImageVector get() = Icons.Rounded.Refresh
    val Search: ImageVector get() = Icons.Rounded.Search
    val Send: ImageVector get() = Icons.AutoMirrored.Rounded.Send
    val Settings: ImageVector get() = Icons.Rounded.Settings
    val Share: ImageVector get() = Icons.Rounded.Share
    val Star: ImageVector get() = Icons.Rounded.Star

    /** Status glyphs that already exist in the core set; the rest live in `StatusIcons`. */
    val StatusReady: ImageVector get() = Icons.Rounded.CheckCircle
    val StatusAttention: ImageVector get() = Icons.Rounded.Warning
}

/** Scope for the generated icon files: `p { … }` adds one filled path. */
internal class AppIconBuilder(private val builder: ImageVector.Builder) {
    fun p(commands: PathBuilder.() -> Unit) {
        builder.path(fill = SolidColor(Color.Black), pathBuilder = commands)
    }
}

/** Builds a 24dp Material-grid icon. The fill colour is irrelevant: `Icon` tints it. */
internal fun appIcon(name: String, content: AppIconBuilder.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = "AppIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    AppIconBuilder(builder).content()
    return builder.build()
}
