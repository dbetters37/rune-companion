package com.aicontrolcenter.app.ui.tasks

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Where the Tasks, Waiting for you and question screens build their state (P1, P2, P7): their view models run the
 * building of what a screen shows `flowOn` this context, never on the main thread once it is a background pool.
 *
 * The app's activity provides `Dispatchers.Default` (scratchpad/phase6/handoff-wp11a.md, item for `MainActivity`). The
 * default is the caller's own dispatcher, so tests that compose the app directly see every state at once, without a
 * thread in between (their stores answer at once anyway).
 */
val LocalScreenWork = staticCompositionLocalOf<CoroutineContext> { EmptyCoroutineContext }
