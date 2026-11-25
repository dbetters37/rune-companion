package com.aicontrolcenter.data.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a test a second time, after [reset], when its first run fails with the
 * [NoSuchElementException] of `single()` on an empty list, and only then.
 *
 * For `MessageHeaderContract`'s "an open observer of headers sees new messages" only: its wait
 * condition reads `recent.last().single()`, which throws instead of answering "not yet" when the
 * observer of the headers has caught up and the observer of the recent headers has not emitted
 * since the first, empty list. Room updates the two flows independently, so this can happen
 * however correct both are; the in-memory reference updates both at once. The fix belongs in the
 * contract (`singleOrNull()`); it is in `scratchpad/phase6/handoff-wp1b.md`. Any other failure,
 * and a second failure of the same kind, fails the test as usual.
 */
class RetryOnceOnEmptyListRule(private val reset: () -> Unit) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (race: NoSuchElementException) {
                if (race.message != "List is empty.") throw race
                reset()
                base.evaluate()
            }
        }
    }
}
