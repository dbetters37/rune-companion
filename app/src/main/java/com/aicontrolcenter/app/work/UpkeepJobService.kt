package com.aicontrolcenter.app.work

import android.app.job.JobParameters
import android.app.job.JobService
import com.aicontrolcenter.app.Phase6Stub

/**
 * The daily upkeep job (Phase 6, R9, D36, rule 1; WP2 owns this file in wave 2): memory upkeep, activity pruning and
 * usage housekeeping while the phone is idle, through `UpkeepGraph` only, never the app's graph, a model, a tool or
 * a provider.
 *
 * Wave 1: nothing schedules it, and it does nothing if Android starts it anyway.
 */
@Phase6Stub("WP2")
class UpkeepJobService : JobService() {
    /** Nothing to do: the job is finished at once. */
    override fun onStartJob(params: JobParameters?): Boolean = false

    /** Nothing runs, so nothing needs to run again. */
    override fun onStopJob(params: JobParameters?): Boolean = false
}
