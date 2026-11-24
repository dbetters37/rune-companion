package com.aicontrolcenter.app.ui.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.approvals.ApprovalNotificationsOffBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.History
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.icons.Storage
import com.aicontrolcenter.app.ui.icons.Tools
import com.aicontrolcenter.app.ui.icons.TouchApp
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DeviceAccess
import kotlinx.serialization.Serializable

/** The Tools section's five views. Short names, so all five fit side by side on the narrowest phone. */
@Serializable
enum class ToolsTab(val title: String) {
    TOOLS("All tools"),
    PERMISSIONS("Allowed"),
    FOLDERS("Folders"),
    SCREEN("Screen"),
    REQUESTS("History"),
    ;

    val icon: ImageVector
        get() = when (this) {
            TOOLS -> AppIcons.Tools
            PERMISSIONS -> AppIcons.Key
            FOLDERS -> AppIcons.Storage
            SCREEN -> AppIcons.TouchApp
            REQUESTS -> AppIcons.History
        }
}

/** Where the Tools section leads. Supplied by the navigation shell. */
class ToolsNavigation(
    val onOpenAgent: (AgentId) -> Unit,
    val onOpenWorkspace: () -> Unit,
    val onOpenRequest: (RequestId) -> Unit,
    val onOpenSettings: () -> Unit,
    /** Shown as a back arrow when the section was opened from another screen (an agent's Permissions link). */
    val onBack: (() -> Unit)? = null,
)

/**
 * The Tools section: what agents can do on this phone and what they may do. Each tab's view model
 * is created the first time the tab is shown and kept with the section, so switching tabs keeps
 * filters and open families.
 *
 * @param initialAgent narrows the Permissions tab to one agent (the agent editor's link).
 */
@Composable
fun ToolsSectionRoute(initialTab: ToolsTab, initialAgent: AgentId?, navigation: ToolsNavigation) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val familyOfTool: (ToolId) -> ToolFamily? = remember(graph) { { id -> graph.toolRegistry.byId(id)?.spec?.let(ToolFamily::of) } }

    // Built before the scaffold, so the scaffold shows the open tab's message.
    var message: UserMessage? = null
    var onMessageShown: () -> Unit = {}
    val body: @Composable () -> Unit = when (tab) {
        ToolsTab.TOOLS -> {
            val viewModel = viewModel {
                ToolsCatalogViewModel(graph.registryDirectory, graph.toolRegistry.all().map { it.spec }, graph.capabilities::info, graph.systemScreens::accessState)
            }
            // Coming back from Android's settings: read every access again.
            LifecycleResumeEffect(viewModel) {
                viewModel.refreshAccess()
                onPauseOrDispose { }
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            message = state.message
            onMessageShown = viewModel::messageShown
            // Android's own permission dialog; which access it was for is kept across the dialog.
            var asked by rememberSaveable { mutableStateOf<DeviceAccess?>(null) }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                asked?.let(viewModel::permissionAnswered)
            }
            ({
                CatalogTab(
                    state, navigation.onOpenAgent, navigation.onOpenWorkspace,
                    DeviceAccessActions(
                        onRequest = { access ->
                            val wanted = graph.systemScreens.accessPermissions(access)
                            if (wanted.isNotEmpty()) {
                                asked = access
                                permissions.launch(wanted.toTypedArray())
                            }
                        },
                        onOpenSettings = { access ->
                            val intent = graph.systemScreens.accessSettings(access)
                            try {
                                if (intent != null) context.startActivity(intent) else viewModel.couldNotOpen("that setting")
                            } catch (_: ActivityNotFoundException) {
                                viewModel.couldNotOpen("that setting")
                            }
                        },
                    ),
                )
            })
        }
        ToolsTab.PERMISSIONS -> {
            val viewModel = viewModel {
                PermissionsViewModel(
                    grants = graph.ownerGrants,
                    directory = graph.registryDirectory,
                    folders = graph.ownerFolders,
                    apps = graph.ownerOperatorApps,
                    capabilityInfo = graph.capabilities::info,
                    allCapabilities = graph.capabilities.all(),
                    clock = graph.clock,
                    initialAgent = initialAgent,
                )
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            message = state.message
            onMessageShown = viewModel::messageShown
            ({
                PermissionsTab(
                    state,
                    PermissionsActions(viewModel::select, viewModel::revoke, viewModel::revokeAll, navigation.onOpenAgent),
                )
            })
        }
        ToolsTab.FOLDERS -> {
            val viewModel = viewModel { FoldersViewModel(graph.ownerFolders, graph.ownerGrants, graph.registryDirectory, familyOfTool, graph.clock) }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            // Android's folder picker; the tree it returns is kept with a persisted grant by OwnerFolders.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> viewModel.add(uri?.toString()) }
            message = state.message
            onMessageShown = viewModel::messageShown
            ({
                FoldersTab(
                    state,
                    FoldersActions(
                        onAddFolder = {
                            try {
                                picker.launch(null)
                            } catch (_: ActivityNotFoundException) {
                                viewModel.pickerUnavailable()
                            }
                        },
                        onRemove = viewModel::remove,
                        onSetReadOnly = viewModel::setReadOnly,
                        onSetAccess = viewModel::setAccess,
                    ),
                )
            })
        }
        ToolsTab.SCREEN -> {
            val viewModel = viewModel {
                ScreenControlViewModel(
                    serviceState = graph.screenSessions.state,
                    control = graph.screenControl,
                    settings = graph.settings,
                    apps = graph.ownerOperatorApps,
                    grants = graph.ownerGrants,
                    directory = graph.registryDirectory,
                    installed = graph.installedApps,
                    isProtected = graph::isProtectedPackage,
                    familyOfTool = familyOfTool,
                    refreshService = graph::refreshScreenState,
                    clock = graph.clock,
                    registry = graph.aiAppRegistry,
                )
            }
            // Coming back from Android's settings: read the service state again.
            LifecycleResumeEffect(viewModel) {
                viewModel.refresh()
                onPauseOrDispose { }
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            message = state.message
            onMessageShown = viewModel::messageShown
            val open: (Intent, String) -> Unit = { intent, what ->
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.couldNotOpen(what)
                }
            }
            ({
                ScreenControlTab(
                    state,
                    ScreenControlActions(
                        onOpenAccessibilitySettings = { open(graph.systemScreens.accessibilitySettings(), "the accessibility settings") },
                        onOpenAppInfo = { open(graph.systemScreens.appInfo(), "this app's App info page") },
                        onCheckAgain = viewModel::refresh,
                        onOpenSettings = navigation.onOpenSettings,
                        onStop = viewModel::stop,
                        onResume = viewModel::resume,
                        onOpenPicker = viewModel::openPicker,
                        onClosePicker = viewModel::closePicker,
                        onAuthorise = viewModel::authorise,
                        onWithdraw = viewModel::withdraw,
                        onSetAccess = viewModel::setAccess,
                        onSetPolicy = viewModel::setPolicy,
                    ),
                )
            })
        }
        ToolsTab.REQUESTS -> {
            val viewModel = viewModel { RequestsViewModel(graph.ownerRequests, graph.registryDirectory, graph.capabilities::info, graph.clock) }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            message = state.message
            onMessageShown = viewModel::messageShown
            ({
                RequestsTab(
                    state,
                    RequestsActions(
                        viewModel::setKind, viewModel::setOutcome, viewModel::setAgent, viewModel::clearFilter, navigation.onOpenRequest,
                        onLetAskAgain = viewModel::letAskAgain,
                    ),
                )
            })
        }
    }

    // The workspace is opened from its family in the All tools tab; one way in, not three.
    ScreenScaffold(
        title = "Tools",
        onBack = navigation.onBack,
        message = message,
        onMessageShown = onMessageShown,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A fixed row: every tab, the request history too, is always in sight.
            PrimaryTabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                divider = { HorizontalDivider(color = appColors.hairline) },
            ) {
                ToolsTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { tab = entry },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(entry.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // Agents wait for the owner's answers; without notifications the owner is not told (A2).
            val notificationsOn by graph.notifications.enabled.collectAsStateWithLifecycle()
            if (!notificationsOn) ApprovalNotificationsOffBanner(Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.md))
            body()
        }
    }
}
