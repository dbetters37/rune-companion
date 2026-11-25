package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.framework.operator.JsEngine
import com.aicontrolcenter.framework.operator.JsLimits

/*
 * Scripts in their own process (Phase 6, WP6 owns this file in wave 2; rules 11 and 31).
 *
 * Wave 1 (WP1d) leaves the in-process JavaScript sandbox in place: with no [ScriptsPorts.engine], the operator's
 * `scripts.run_js` tool runs on `JsSandbox` exactly as in 0.5.2. WP6 returns an engine that runs each script in the
 * isolated `:scripts` process (app/scripts/ScriptProcessService) and keeps every limit that protects the app in this
 * process.
 */

/**
 * What the scripts package hands the composition root.
 *
 * @param engine builds the engine for the given limits; null keeps the in-process sandbox.
 */
class ScriptsPorts(val engine: ((JsLimits) -> JsEngine)? = null)

@Phase6Stub("WP6")
object ScriptsWiring {
    /** The production scripts port; called once per process by `createProductionPorts`. */
    fun production(@Suppress("UNUSED_PARAMETER") context: Context): ScriptsPorts = ScriptsPorts()
}
