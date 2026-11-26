package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * D3A-19: under the owner's Calm, a block that grows shows its end size at once. (Without Calm it animates; Robolectric's
 * frame clock does not show the spring reliably, so the device run checks that half: device-tests-wave3.md section 9.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CalmMotionTest {
    @get:Rule val compose = createComposeRule()

    private fun heightAfterOneFrame(calm: Boolean): Float {
        var big by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalCalmMotion provides calm) {
                Box(Modifier.fillMaxWidth().calmAnimateContentSize().testTag("block")) {
                    Box(Modifier.fillMaxWidth().height(if (big) 200.dp else 50.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            big = true
            Snapshot.sendApplyNotifications()
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val bounds = compose.onNodeWithTag("block").getUnclippedBoundsInRoot()
        val height = (bounds.bottom - bounds.top).value
        compose.mainClock.autoAdvance = true
        return height
    }

    @Test
    fun `under Calm the end size shows at once`() {
        assertEquals(200f, heightAfterOneFrame(calm = true))
    }
}
