package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OwnerRequestQueue

/*
 * What the permission broker, the capability requester, the task hooks and the tools are given
 * instead of the full stores. Each is a separate object that implements the agent-side interface
 * only, so nothing holding one can be cast to the owner's side (deciding a request, storing a
 * grant, pressing Resume), even where one implementation serves both sides: Room's request store
 * also decides, and a test's fake screen also stops.
 *
 * `OperatorDependencyTest` (app unit tests) walks the wired graph and allows a full store only
 * inside these views.
 */

/** Raise, watch and close requests; never answer one. */
class RequestQueueView(store: OwnerRequestQueue) : OwnerRequestQueue by store

/** Find, use up and narrow grants; never store or give one. */
class GrantLookupView(store: GrantLookup) : GrantLookup by store

// The screen operator's view is [ScreenSessions], which also follows who operates which app.
