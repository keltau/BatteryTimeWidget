package com.keltau.batterytimewidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class BatteryMaintenanceService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        BatteryStore.executor.execute {
            val result = runCatching {
                BatteryStore.get(this).prune(System.currentTimeMillis())
                BatteryWidgetProvider.updateAll(this)
            }
            jobFinished(params, result.isFailure)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) == null) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, BatteryMaintenanceService::class.java))
                    .setPeriodic(BatteryConfig.DAY_MS)
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
