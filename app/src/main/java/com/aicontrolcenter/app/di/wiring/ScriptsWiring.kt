package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.scripts.IsolatedJsEngine
import com.aicontrolcenter.framework.operator.JsEngine
import com.aicontrolcenter.framework.operator.JsLimits

/*
 * Scripts in their own process (Phase 6, WP6; D1-D3, rules 11 and 31).
 *
 * Production: the operator's `scripts.run_js` tool runs every script in the isolated `:scripts` process
 * (app/scripts/ScriptProcessService) through `IsolatedJsEngine`, which keeps every limit that protects the app in this
 * process and lets go of a script process that does not stop. When Android refuses to start that process at all, the
 * engine runs the script in this process's Rhino sandbox and says so in the answer. Tests (`TestApp`) keep the
 * in-process sandbox: no engine here.
 */

/**
 * What the scripts package hands the composition root.
 *
 * @param engine builds the engine for the given limits; null keeps the in-process sandbox.
 */
class ScriptsPorts(val engine: ((JsLimits) -> JsEngine)? = null)

object ScriptsWiring {
    /** The production scripts port; called once per process by `createProductionPorts`. Nothing is bound until a script runs. */
    fun production(context: Context): ScriptsPorts {
        val app = context.applicationContext ?: context
        return ScriptsPorts(engine = { limits -> IsolatedJsEngine(app, limits) })
    }
}
