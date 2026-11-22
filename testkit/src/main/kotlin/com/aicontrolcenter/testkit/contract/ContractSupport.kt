package com.aicontrolcenter.testkit.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects [flow] on a real dispatcher for as long as [scope] lives and returns the list the
 * emissions are appended to. Implementations may deliver from their own threads, so tests
 * wait for an expected state with [awaitUntil] instead of counting emissions.
 */
fun <T> collectIn(scope: CoroutineScope, flow: Flow<T>): List<T> {
    val seen = CopyOnWriteArrayList<T>()
    scope.launch(Dispatchers.Default) { flow.collect { seen += it } }
    return seen
}

/** Waits in real time until [condition] holds; fails the test after [timeoutMs]. */
suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }
}
