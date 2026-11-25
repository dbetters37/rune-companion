package com.aicontrolcenter.app.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The daily upkeep job (Phase 6, R9, D36, rule 1; WP2 item 11): memory upkeep, activity pruning and usage retention
 * while the phone is idle, through [UpkeepGraph] only, never the app's graph, a model, a tool or a provider. It finds
 * its work as `(application as UpkeepHost).upkeep` and names nothing wider (`UpkeepIsolationTest`).
 *
 * Android's own `JobScheduler` (androidx.work is not available offline): idle, battery not low, every 24 hours, not
 * kept across a restart of the phone (no `RECEIVE_BOOT_COMPLETED`; the next start of the app schedules it again).
 * [onStartJob] runs the work in the job's own scope, keeps the parameters and calls `jobFinished` when it is done (a job
 * collected without it is counted against the app on Android 16); [onStopJob] cancels the work and asks to be run again.
 */
class UpkeepJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val host = application as? UpkeepHost ?: return false
        work = scope.launch {
            runCatching { host.upkeep.run() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The job's id: one job, replaced rather than added. */
        const val JOB_ID: Int = 6_001

        /** Once a day. */
        const val PERIOD_MS: Long = 24L * 60 * 60 * 1000

        /** The job as it is scheduled. */
        fun jobInfo(context: Context): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, UpkeepJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
                .build()

        /**
         * Schedules the job unless it is scheduled already (start-up calls this once the app started fully). Never
         * throws: without the job, upkeep still runs while the app is open.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            try {
                if (scheduler.getPendingJob(JOB_ID) != null) return
                scheduler.schedule(jobInfo(context))
            } catch (_: RuntimeException) {
                // Android refused (too many jobs, a system failure): the app's own upkeep while it is open remains.
            }
        }
    }
}
