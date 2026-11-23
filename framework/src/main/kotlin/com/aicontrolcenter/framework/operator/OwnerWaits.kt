package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.OwnerRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Told when the tool call in whose coroutine it sits starts and stops waiting for the owner.
 * The agent runner puts one into the context of every tool call, so the run can show the wait
 * (the agent's activity, a chat's live turn) while [PermissionBroker] holds the call. It
 * observes only: nothing it is given can answer a request.
 */
abstract class OwnerWaits : AbstractCoroutineContextElement(Key) {
    /** The call now waits for the owner to answer [request]. */
    abstract suspend fun waiting(request: OwnerRequest)

    /**
     * The wait for [request] is over, however it ended: answered, expired or cancelled.
     * [request] is as it ended, or as it was raised when that could not be read. Called on the
     * way out even when the call is being cancelled.
     */
    abstract suspend fun answered(request: OwnerRequest)

    /** The context key. */
    companion object Key : CoroutineContext.Key<OwnerWaits> {
        /** The observer of the current tool call, or null outside an agent run. */
        suspend fun current(): OwnerWaits? = currentCoroutineContext()[Key]
    }
}
