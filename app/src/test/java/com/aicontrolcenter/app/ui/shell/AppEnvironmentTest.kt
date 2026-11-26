package com.aicontrolcenter.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.activity.LocalUsageWork
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.calmMotion
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wave 3 hand-off (WP8; handoff-wp11a.md item 1, handoff-WP10a.md item 2.2, handoff-WP11b.md "Calm"): the app's root
 * hands the screens the background pool `MainActivity` gives it, for WP11a's and WP10a's view models alike, and Calm
 * follows the owner's setting and Android's own animation switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppEnvironmentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the main activity's pool reaches both packages' screens, and null keeps what an outer caller gave`() {
        assertSame(Dispatchers.Default, AppScreenWork)
        var screen: CoroutineContext? = null
        var usage: CoroutineContext? = null
        var given by mutableStateOf<CoroutineContext?>(AppScreenWork)
        val outer = Dispatchers.Unconfined
        compose.setContent {
            CompositionLocalProvider(LocalScreenWork provides outer, LocalUsageWork provides outer) {
                AppEnvironment(ReadOnlySettings(InMemorySettingsRepository()), given) {
                    screen = LocalScreenWork.current
                    usage = LocalUsageWork.current
                }
            }
        }
        compose.waitForIdle()
        assertSame(Dispatchers.Default, screen)
        assertSame(Dispatchers.Default, usage)

        given = null
        compose.waitForIdle()
        assertSame(outer, screen, "the tests' own context stays")
        assertSame(outer, usage)
    }

    @Test
    fun `without the activity the screens keep building where they are asked, as every test expects`() {
        var screen: CoroutineContext? = null
        compose.setContent {
            AppEnvironment(ReadOnlySettings(InMemorySettingsRepository()), screenWork = null) { screen = LocalScreenWork.current }
        }
        compose.waitForIdle()
        assertSame(EmptyCoroutineContext, screen)
    }

    @Test
    fun `Calm is on when the owner switches it on or Android's animations are off`() {
        assertFalse(calmMotion(calmSetting = false, animatorsEnabled = true))
        assertTrue(calmMotion(calmSetting = true, animatorsEnabled = true))
        assertTrue(calmMotion(calmSetting = false, animatorsEnabled = false))

        val settings = InMemorySettingsRepository()
        var calm: Boolean? = null
        compose.setContent {
            AppEnvironment(ReadOnlySettings(settings), screenWork = null, animatorsEnabled = { true }) { calm = LocalCalmMotion.current }
        }
        compose.waitForIdle()
        assertEquals(false, calm)

        runBlocking { settings.update { it.copy(calmMotion = true) } }
        compose.waitUntil(5_000) { calm == true }

        runBlocking { settings.update { it.copy(calmMotion = false) } }
        compose.waitUntil(5_000) { calm == false }
    }

    @Test
    fun `Android's animations switched off calm the app without the owner's setting`() {
        var calm: Boolean? = null
        compose.setContent {
            AppEnvironment(ReadOnlySettings(InMemorySettingsRepository()), screenWork = null, animatorsEnabled = { false }) {
                calm = LocalCalmMotion.current
            }
        }
        compose.waitForIdle()
        assertEquals(true, calm)
    }
}
