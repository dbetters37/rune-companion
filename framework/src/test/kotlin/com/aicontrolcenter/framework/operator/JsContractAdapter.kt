package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.testkit.contract.ScriptEnded
import com.aicontrolcenter.testkit.contract.ScriptEngineUnderTest
import com.aicontrolcenter.testkit.contract.ScriptHostRefusal
import com.aicontrolcenter.testkit.contract.ScriptHostUnderTest
import com.aicontrolcenter.testkit.contract.ScriptLimitsUnderTest

/** The testkit's script-engine contract in this module's types (`JsEngineContract`; :testkit does not see :framework). */
internal object JsContractAdapter {
    fun limits(l: ScriptLimitsUnderTest): JsLimits = JsLimits(
        maxCodeChars = l.maxCodeChars,
        maxInputChars = l.maxInputChars,
        timeoutMs = l.timeoutMs,
        maxResultChars = l.maxResultChars,
        maxLogChars = l.maxLogChars,
        maxLogLines = l.maxLogLines,
        maxStringChars = l.maxStringChars,
    )

    fun engine(engine: JsEngine): ScriptEngineUnderTest = ScriptEngineUnderTest { code, input, host, timeoutMs ->
        ended(engine.run(code, input, host?.let(::host), timeoutMs))
    }

    fun host(host: ScriptHostUnderTest): JsHost = object : JsHost {
        override val canWrite: Boolean get() = host.canWrite

        override fun read(path: String): String = refusals { host.read(path) }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> = refusals { host.list(path) }

        override fun write(path: String, text: String): String = refusals { host.write(path, text) }
    }

    private inline fun <T> refusals(block: () -> T): T = try {
        block()
    } catch (e: ScriptHostRefusal) {
        throw JsHostError(e.message ?: "Refused.")
    }

    fun ended(outcome: JsOutcome): ScriptEnded = when (outcome) {
        is JsOutcome.Finished -> ScriptEnded.Finished(outcome.resultJson, outcome.resultTruncated, outcome.log, outcome.logTruncated)
        is JsOutcome.ScriptError -> ScriptEnded.Failed(outcome.message, outcome.line, outcome.log, outcome.logTruncated)
        is JsOutcome.Stopped -> ScriptEnded.Stopped(outcome.reason, outcome.log, outcome.logTruncated)
    }
}

/** Phase 6 (WP6): the Rhino sandbox in this process keeps the script-engine contract. */
class JsSandboxContractTest : com.aicontrolcenter.testkit.contract.HonestJsEngineContract() {
    override fun engine(limits: ScriptLimitsUnderTest): ScriptEngineUnderTest = JsContractAdapter.engine(JsSandbox(JsContractAdapter.limits(limits)))
}
