package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.toRiskLabel
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ConstraintKind
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One tool in the catalogue: what it does, how risky it is, what it needs and who has it. */
@Immutable
data class CatalogToolUi(
    val id: ToolId,
    val title: String,
    /** What it does, for the owner. */
    val plainWords: String,
    val riskLevel: RiskLevel,
    val risk: StatusLabel,
    /** Calling it sends something off this phone or hands it to another app (blocked in local-only mode); [offPhoneMark] words it. */
    val sendsDataOffPhone: Boolean,
    /** The permission it needs, by its title. */
    val permission: String,
    /** "Allowed for every agent", or how the permission is given. */
    val permissionNote: String,
    /** The agents that have it switched on, by name. */
    val agents: List<AgentFaceUi>,
)

/**
 * Something Android must allow before some tools of a family work (photo access, "Install
 * unknown apps", Termux's permission), with where it stands now and how to change it here.
 */
@Immutable
data class DeviceAccessUi(
    val access: DeviceAccess,
    val title: String,
    val state: DeviceAccessState,
    /** Where it stands, in words. */
    val status: String,
    /** What the owner should know beyond the switch (Termux's own setting), or null. */
    val note: String?,
    /** The button that asks Android's own permission dialog, or null when only a settings page can change it. */
    val requestLabel: String?,
    /** The button that opens Android's settings page for it, or null when there is none (or it is not installed). */
    val settingsLabel: String?,
)

/** One family of tools, in catalogue order. */
@Immutable
data class CatalogFamilyUi(
    val family: ToolFamily,
    val tools: List<CatalogToolUi>,
    /** Why none, or some, of these can be used right now (a switch in Settings, local-only mode), or null. */
    val blockedBecause: String?,
    /** What Android must allow for some of these tools, with its state. */
    val access: List<DeviceAccessUi> = emptyList(),
) {
    /** Agents with at least one tool of the family switched on. */
    val agentCount: Int get() = tools.flatMap { it.agents }.map { it.id }.distinct().size
}

@Immutable
data class ToolsCatalogUiState(
    val isLoading: Boolean = true,
    val families: List<CatalogFamilyUi> = emptyList(),
    val message: UserMessage? = null,
) {
    val toolCount: Int get() = families.sumOf { it.tools.size }
}

/**
 * The Tools tab: every registered tool by family, with what it does in plain words, its risk,
 * whether it sends anything off the phone, the permission it needs and which agents have it
 * switched on, and what Android must allow for the photo, install and Termux tools, with a way
 * to allow it. Tools are switched on per agent in the agent editor, and permissions are given
 * and revoked in the Permissions tab.
 *
 * @param accessState reads where each Android access stands; read again by [refreshAccess]
 *   (when the screen comes back from Android's settings or permission dialog).
 */
class ToolsCatalogViewModel(
    directory: RegistryDirectory,
    private val tools: List<ToolSpec>,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    private val accessState: (DeviceAccess) -> DeviceAccessState = { DeviceAccessState.NOT_ALLOWED },
) : ViewModel() {
    private val access = MutableStateFlow(readAccess())
    private val message = MutableStateFlow<UserMessage?>(null)
    private var messageCounter = 0L

    val uiState: StateFlow<ToolsCatalogUiState> = combine(directory.observe(), access, message) { snapshot, states, note ->
        ToolsCatalogUiState(
            isLoading = false,
            families = catalogOf(tools, snapshot, capabilityInfo).map { family ->
                family.copy(access = ACCESS_OF[family.family].orEmpty().map { accessUi(it, states.getValue(it)) })
            },
            message = note,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsCatalogUiState())

    /** Reads every access again. */
    fun refreshAccess() {
        access.value = readAccess()
    }

    /** After Android's permission dialog: reads again, and says so when the owner did not allow it there. */
    fun permissionAnswered(asked: DeviceAccess) {
        refreshAccess()
        if (access.value[asked] == DeviceAccessState.NOT_ALLOWED) {
            message.value = UserMessage(
                "Android did not allow it. If it no longer asks, allow it in this app's App info page: tap \"Open App info\".",
                ++messageCounter,
            )
        }
    }

    /** Android has no screen for it here. */
    fun couldNotOpen(what: String) {
        message.value = UserMessage("Android could not open $what on this phone.", ++messageCounter)
    }

    fun messageShown() {
        message.value = null
    }

    private fun readAccess(): Map<DeviceAccess, DeviceAccessState> = ACCESS_OF.values.flatten().distinct().associateWith(accessState)

    companion object {
        /** Which family's tools need which Android access. */
        val ACCESS_OF: Map<ToolFamily, List<DeviceAccess>> = mapOf(
            ToolFamily.MEDIA to listOf(DeviceAccess.MEDIA),
            ToolFamily.APPS to listOf(DeviceAccess.INSTALL_UNKNOWN_APPS),
            ToolFamily.SCRIPTS to listOf(DeviceAccess.TERMUX_RUN_COMMAND),
        )

        /** [access] in [state], in the owner's words, with the buttons that change it. */
        fun accessUi(access: DeviceAccess, state: DeviceAccessState): DeviceAccessUi = when (access) {
            DeviceAccess.MEDIA -> DeviceAccessUi(
                access, "Photos and videos (Android permission)", state,
                status = when (state) {
                    DeviceAccessState.ALLOWED -> "Allowed: the photo tools can see all your photos and videos."
                    DeviceAccessState.SELECTED_ONLY -> "Allowed for the photos and videos you selected only."
                    else -> "Not allowed yet, so the photo tools cannot see any photo or video."
                },
                note = null,
                requestLabel = when (state) {
                    DeviceAccessState.ALLOWED -> null
                    DeviceAccessState.SELECTED_ONLY -> "Change selection"
                    else -> "Allow photos and videos"
                },
                settingsLabel = "Open App info",
            )
            DeviceAccess.INSTALL_UNKNOWN_APPS -> DeviceAccessUi(
                access, "Install unknown apps (Android setting)", state,
                status = if (state == DeviceAccessState.ALLOWED) "On: Android lets this app ask to install apps. You still approve every install."
                else "Off, so \"Install an app\" cannot ask Android to install anything.",
                note = null,
                requestLabel = null,
                settingsLabel = "Open Android's setting",
            )
            DeviceAccess.TERMUX_RUN_COMMAND -> DeviceAccessUi(
                access, "Run commands in Termux (Termux permission)", state,
                status = when (state) {
                    DeviceAccessState.ALLOWED -> "Allowed."
                    DeviceAccessState.NOT_INSTALLED -> "Termux is not installed, so Termux commands cannot run."
                    else -> "Not allowed yet, so Termux commands cannot run."
                },
                note = if (state == DeviceAccessState.NOT_INSTALLED) null
                else "Termux must also allow it: set allow-external-apps=true in ~/.termux/termux.properties, in Termux.",
                requestLabel = if (state == DeviceAccessState.NOT_ALLOWED) "Allow Termux commands" else null,
                settingsLabel = if (state == DeviceAccessState.NOT_INSTALLED) null else "Open App info",
            )
            DeviceAccess.ACCESSIBILITY_SERVICE -> DeviceAccessUi(access, "Screen control", state, "Set up in the Screen tab.", null, null, null)
        }

        /** The catalogue for [tools] with the agents and settings of [snapshot]. Families without a tool are left out. */
        fun catalogOf(tools: List<ToolSpec>, snapshot: RegistrySnapshot, capabilityInfo: (Capability) -> CapabilityInfo?): List<CatalogFamilyUi> {
            val agents = snapshot.agents.map { it.profile }.sortedBy { it.name.lowercase() }
            val settings = snapshot.settings
            return tools.groupBy { ToolFamily.of(it) }.toSortedMap().map { (family, specs) ->
                val operatorFamily = specs.any { isOperatorTool(it.id) && it.id.value != "permissions.request" }
                CatalogFamilyUi(
                    family = family,
                    tools = specs.sortedBy { it.title.lowercase() }.map { spec ->
                        val info = capabilityInfo(spec.capability)
                        CatalogToolUi(
                            id = spec.id,
                            title = spec.title,
                            plainWords = plainWordsFor(spec),
                            riskLevel = spec.risk,
                            risk = spec.risk.toRiskLabel(),
                            sendsDataOffPhone = spec.sendsDataOffDevice,
                            permission = info?.title ?: spec.capability.id,
                            permissionNote = permissionNote(info),
                            agents = agents.filter { spec.id in it.toolIds }.map { AgentFaceUi(it.id, it.name, it.colorIndex) },
                        )
                    },
                    blockedBecause = when {
                        operatorFamily && !settings.operatorEnabled ->
                            "Phone operator is off in Settings, so no agent can use these tools."
                        family == ToolFamily.SCREEN && !settings.accessibilityOperatorEnabled ->
                            "Screen control is off in Settings, so no agent can use these tools."
                        family == ToolFamily.WEB && settings.localOnlyMode ->
                            "Local-only mode is on, so these are blocked: they send a web address off this phone."
                        family == ToolFamily.SCRIPTS && settings.localOnlyMode ->
                            "Local-only mode is on, so shell and Termux commands are blocked: they can use the internet. JavaScript still runs."
                        family == ToolFamily.APPS && settings.localOnlyMode ->
                            "Local-only mode is on, so links are not opened and nothing is shared with or prepared in another app: " +
                                "that app could send it off this phone. Opening, installing and removing apps still work."
                        family == ToolFamily.WORKSPACE && settings.localOnlyMode ->
                            "Local-only mode is on, so workspace files are not shared with another app. Everything else here still works."
                        else -> null
                    },
                )
            }
        }

        /** How [info] is given, in words. */
        fun permissionNote(info: CapabilityInfo?): String = when {
            info == null -> "A permission this app does not describe"
            info.grantedByDefault -> "Allowed for every agent"
            info.constrainedBy == ConstraintKind.FOLDER -> "Needs your permission, folder by folder"
            info.constrainedBy == ConstraintKind.APP -> "Needs your permission, app by app"
            else -> "Needs your permission"
        }
    }
}
