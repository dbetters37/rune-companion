package com.aicontrolcenter.app.scripts

import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsSandbox
import com.aicontrolcenter.testkit.contract.HonestJsEngineContract
import com.aicontrolcenter.testkit.contract.JsEngineContract
import com.aicontrolcenter.testkit.contract.ScriptEngineUnderTest
import com.aicontrolcenter.testkit.contract.ScriptLimitsUnderTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6 (WP6, rule 11): the isolated engine keeps the script-engine contract, with an honest script process (the
 * real runner and sandbox, in this test's process) behind the app's half.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IsolatedJsEngineContractTest : HonestJsEngineContract() {
    override fun engine(limits: ScriptLimitsUnderTest): ScriptEngineUnderTest {
        val pipes = TestPipes()
        val js = JsContractAdapter.limits(limits)
        val engine = IsolatedJsEngine(js, DirectLauncher(makeProcess = honestProcess(pipes)), pipes, JsSandbox(js), sharedProcess = false)
        return JsContractAdapter.engine(engine)
    }
}

/**
 * The same contract with a script process that starts slowly (0.3 s) and that Android ends while it is idle after
 * every run, without the app hearing of it until its next call fails: the engine binds afresh and the run goes ahead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SlowDyingScriptProcessContractTest : HonestJsEngineContract() {
    override val readyMs: Long = 12_000

    override fun engine(limits: ScriptLimitsUnderTest): ScriptEngineUnderTest {
        val pipes = TestPipes()
        val honest = honestProcess(pipes)
        val js = JsContractAdapter.limits(limits)
        val launcher = DirectLauncher(connectDelayMs = 300) { instance, l -> DiesWhenIdle(honest(instance, l) as IScriptRunner) }
        return JsContractAdapter.engine(IsolatedJsEngine(js, launcher, pipes, JsSandbox(js), sharedProcess = false))
    }

    /** A process that serves one run and is dead when the next one comes. */
    private class DiesWhenIdle(private val inner: IScriptRunner) : IScriptRunner.Stub() {
        private val served = AtomicBoolean(false)

        override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
            if (served.getAndSet(true)) throw DeadObjectException()
            inner.run(request, host, result)
        }

        override fun pid(): Int = inner.pid()
    }
}

/**
 * The limits that protect the app hold with a script process that ignores every limit of its own (rule 11): it makes
 * 25 files, asks for files after it said it was done, and answers with a result and a console far over every cap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HostileScriptProcessContractTest : JsEngineContract() {
    override fun engine(limits: ScriptLimitsUnderTest): ScriptEngineUnderTest {
        val pipes = TestPipes()
        val js = JsContractAdapter.limits(limits)
        val launcher = DirectLauncher { _, _ -> HostileRunner(pipes) as IBinder }
        return JsContractAdapter.engine(IsolatedJsEngine(js, launcher, pipes, JsSandbox(js), sharedProcess = false))
    }
}

/**
 * A script process that has broken out of its sandbox and ignores every limit: whatever the script, it creates 25
 * files, reads a file of any size, answers with far too much, and goes on calling the workspace after its answer.
 * [replies] holds what the app answered each call.
 */
internal class HostileRunner(private val pipes: TestPipes, private val answer: JsOutcome = TOO_MUCH) : IScriptRunner.Stub() {
    val replies: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var finished = false

    override fun pid(): Int = 666

    override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
        Thread({
            try {
                host?.let { h -> repeat(25) { replies += write(h, "h$it.txt", "x") } }
                host?.let { h -> replies += read(h, "big.txt") }
                result!!.started()
                val pipe = pipes.open()
                Thread({ runCatching { pipes.writer(pipe.write).use { ScriptFrames.writeOutcome(it, answer, threadEnded = true) } } }, "hostile-answer").start()
                result.done(ScriptStatus.OUTCOME, pipe.read)
                host?.let { h -> repeat(5) { replies += write(h, "late$it.txt", "y") } }
                host?.let { h -> replies += read(h, "a.txt") }
            } finally {
                finished = true
            }
        }, "hostile-run").apply { isDaemon = true }.start()
    }

    fun write(host: IScriptHost, path: String, text: String): String {
        val pipe = pipes.open()
        Thread({ runCatching { pipes.writer(pipe.write).use { ScriptFrames.writeContent(it, text) } } }, "hostile-content").start()
        return host.write(path, pipe.read)
    }

    fun read(host: IScriptHost, path: String): String {
        val end = host.read(path)
        val (ok, text) = runCatching { pipes.reader(end).use { ScriptFrames.readReply(it, Int.MAX_VALUE) } }.getOrElse { return "!unreadable" }
        return if (ok) "+${text.length} characters" else "!$text"
    }

    companion object {
        val TOO_MUCH = JsOutcome.Finished("\"" + "x".repeat(500_000) + "\"", false, List(2_000) { "line $it " + "l".repeat(100) }, false)
    }
}

/** For the tests' limits. */
internal fun jsLimits(timeoutMs: Long = 5_000, engineStartMs: Long = 30_000, bindTimeoutMs: Long = 30_000, maxStringChars: Int = 5_000_000) =
    // No instruction budget: the busy loops of these tests end on the clock.
    JsLimits(timeoutMs = timeoutMs, engineStartMs = engineStartMs, bindTimeoutMs = bindTimeoutMs, maxStringChars = maxStringChars, maxInstructions = Long.MAX_VALUE)
