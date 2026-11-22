package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Shield
import com.aicontrolcenter.app.ui.theme.Spacing

/**
 * The explainer for the app's three central nouns. They are different things with different
 * registries, and the whole interface depends on the owner not confusing them.
 */
@Composable
fun ConceptsScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "Three different things", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "This app keeps three registries. An AI application, a model and an agent are not interchangeable, " +
                    "and nothing here treats one as another.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ConceptCard(
                icon = AppIcons.Apps,
                accent = MaterialTheme.colorScheme.tertiary,
                name = "AI application",
                definition = "An Android app installed on this phone that has its own screen and belongs to its vendor, " +
                    "such as a chatbot app.",
                canDo = "Be opened, or be handed text through Android's share action. Its reply stays inside that app.",
                isNot = "It is not something agents can think with, and it cannot be controlled just because it is listed.",
            )
            ConceptCard(
                icon = AppIcons.ModelChip,
                accent = MaterialTheme.colorScheme.secondary,
                name = "Model",
                definition = "A language model this framework can call directly through a provider: a cloud service, " +
                    "a server on your network, or a runtime on this phone.",
                canDo = "Receive a prompt and return an answer to the framework. Every model shows where your data goes.",
                isNot = "It has no screen and no identity of its own. A model does nothing until an agent uses it.",
            )
            ConceptCard(
                icon = AppIcons.Agent,
                accent = MaterialTheme.colorScheme.primary,
                name = "Agent",
                definition = "A configured actor inside this framework: a name, a role, instructions and policies, " +
                    "powered by a model you assign to it.",
                canDo = "Be given work, once it has a model. Changing its model changes what does the thinking and nothing else.",
                isNot = "It is not an app and not a model. Without a model assigned, an agent cannot run.",
            )
            InfoBanner(
                icon = AppIcons.Shield,
                title = "Registering is not controlling",
                text = "Listing an AI application only records that it is installed. What may be done with it is exactly " +
                    "what its page lists, each through a mechanism Android officially supports. Operating an app's " +
                    "screen needs your explicit authorisation for that one app, and a service that does not exist " +
                    "in this version.",
            )
        }
    }
}

@Composable
private fun ConceptCard(icon: ImageVector, accent: Color, name: String, definition: String, canDo: String, isNot: String) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                modifier = Modifier.size(40.dp).background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            definition,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.md),
        )
        ConceptLine("What can be done with it", canDo)
        ConceptLine("What it is not", isNot)
    }
}

@Composable
private fun ConceptLine(label: String, text: String) {
    Column(modifier = Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
