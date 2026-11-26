package com.aicontrolcenter.app.ui.pickup

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.pickup.PickUpSafety
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Folder pick-up screens on screen (WP10c): folders and spaces are radio buttons, the chosen folder's refusal is shown
 * as it is with "Make it read-only" (48 dp), Save waits for a usable folder; a stopped rule says so in words with its
 * reason and "Switch on again"; files left out say why.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PickUpScreensTest {
    @get:Rule val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `folders and spaces are radio buttons, and a writable folder's refusal comes with Make it read-only`() {
        val inbox = FolderChoiceUi(FolderId("fld_1"), "Inbox", readOnly = false, access = "Agents may change files in it", problem = PickUpSafety.FOLDER_NOT_READ_ONLY, hasRule = false)
        val notes = FolderChoiceUi(FolderId("fld_2"), "Notes", readOnly = true, access = "Read-only for agents", problem = null, hasRule = false)
        var readOnly = 0
        var chosen: FolderId? = null
        compose.setContent {
            AppTheme(darkTheme = true) {
                PickUpRuleEditorScreen(
                    PickUpRuleEditorUiState(
                        isLoading = false, folders = listOf(inbox, notes), selectedFolder = inbox.id, folder = inbox, canMakeReadOnly = true,
                        spaces = listOf(SpaceChoiceUi(SpaceId("spc_1"), "Inbox notes")), selectedSpace = SpaceId("spc_1"),
                    ),
                    PickUpRuleEditorActions(onClose = {}, onMakeReadOnly = { readOnly++ }, onSelectFolder = { chosen = it }),
                )
            }
        }
        compose.onNode(radio and hasText("Inbox")).assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNode(radio and hasText("Notes")).assertIsNotSelected().performClick()
        assertEquals(FolderId("fld_2"), chosen)
        compose.onNode(radio and hasText("Inbox notes")).assertIsSelected()
        compose.onNodeWithText(PickUpSafety.FOLDER_NOT_READ_ONLY).assertExists()
        compose.onNode(hasText("Make it read-only") and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, readOnly)
        compose.onNode(hasText("Make the rule") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a stopped rule says so in words, with its reason, its files and Switch on again`() {
        var switched: Pair<PickUpRuleId, Boolean>? = null
        val rule = PickUpRuleUi(
            id = PickUpRuleId("pku_1"), folder = "Inbox", space = "Inbox notes", spaceId = SpaceId("spc_1"), enabled = false, status = "Stopped",
            tone = StatusTone.ATTENTION, lastRun = "Last looked today at 03:12", lastResult = PickUpSafety.RUN_FOLDER_NOT_READ_ONLY, stoppedForSafety = true,
            files = listOf(
                PickedUpFileUi("new.txt", "Picked up today at 03:12 · 26 bytes", leftOut = false),
                PickedUpFileUi("old.txt", PickUpRulesViewModel.LEFT_OUT, leftOut = true),
            ),
        )
        compose.setContent {
            AppTheme(darkTheme = false) {
                PickUpRulesScreen(
                    PickUpRulesUiState(isLoading = false, masterOn = true, rules = listOf(rule), count = "1 of 10 rules"),
                    PickUpNavigation(onBack = {}),
                    PickUpRulesActions(onSetEnabled = { id, on -> switched = id to on }),
                )
            }
        }
        compose.onNodeWithText("From “Inbox” into “Inbox notes”", substring = true).assertExists()
        compose.onNodeWithText("Stopped", substring = true).assertExists()
        compose.onNodeWithText(PickUpSafety.RUN_FOLDER_NOT_READ_ONLY).assertExists()
        compose.onNode(hasText("old.txt") and hasText(PickUpRulesViewModel.LEFT_OUT)).assertExists()
        compose.onNode(hasText("Switch on again") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(PickUpRuleId("pku_1") to true, switched)
        assertTrue(compose.onAllNodes(hasText("Folder pick-up is switched off")).fetchSemanticsNodes().isEmpty(), "the master switch is on")
    }
}
