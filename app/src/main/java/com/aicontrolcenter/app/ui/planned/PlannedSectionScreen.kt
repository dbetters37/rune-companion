package com.aicontrolcenter.app.ui.planned

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Tasks
import com.aicontrolcenter.app.ui.icons.Tools
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone

/** A section whose subsystem does not exist yet. Describes it honestly; offers nothing to tap. */
enum class PlannedSection(
    val title: String,
    val summary: String,
    val willShow: List<String>,
    val dependsOn: String,
) {
    TASKS(
        title = "Tasks",
        summary = "Where you will give the Director an objective and follow the work it turns into.",
        willShow = listOf(
            "Objectives you have given, and the tasks each was split into",
            "Which agent each task is assigned to, and its state",
            "Results, reviews and requested revisions",
            "Controls to pause or cancel a run",
        ),
        dependsOn = "Tasks need the Director to plan and hand out work, which is a later stage of this build. Agents can already " +
            "be talked to one at a time: open an agent and tap Chat.",
    ),
    TOOLS(
        title = "Tools",
        summary = "Where you will decide what each agent is allowed to do, tool by tool.",
        willShow = listOf(
            "Every tool agents can use, and what it can touch",
            "Which agents hold a grant for which tool",
            "Actions that always need your confirmation",
        ),
        dependsOn = "Information tools (the current time, the team list) and memory tools (search, save, share) exist and are " +
            "switched on per agent in its editor. Tools that act on this phone, and the permission system for them, are a later " +
            "stage of this build.",
    ),
    ;

    val icon: ImageVector
        get() = when (this) {
            TASKS -> AppIcons.Tasks
            TOOLS -> AppIcons.Tools
        }
}

@Composable
fun PlannedSectionScreen(section: PlannedSection) {
    ScreenScaffold(title = section.title) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Box(
                        modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            section.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        StatusChip("Not built yet in this version", StatusTone.DISABLED)
                        Text(
                            section.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Column {
                SectionHeader("What this section will show")
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        section.willShow.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            Text(
                section.dependsOn,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
