package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Where the Activity log, Usage and cost, and the Dashboard's Today tile build their state (WP10a; rule 16, P7): their
 * view models run that building `flowOn` this context, off the main thread once the app provides a background pool.
 *
 * The app's activity should provide `Dispatchers.Default` here, as for the Tasks screens' `LocalScreenWork` (WP11a; the
 * two can become one, scratchpad/phase6/handoff-WP10a.md). The default is the caller's own dispatcher, so tests that
 * compose the app directly see every state at once, without a thread in between (their stores answer at once anyway).
 */
val LocalUsageWork = staticCompositionLocalOf<CoroutineContext> { EmptyCoroutineContext }
