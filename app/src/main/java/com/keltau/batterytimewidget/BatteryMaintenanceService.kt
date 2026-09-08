package com.keltau.batterytimewidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class BatteryMaintenanceService : JobService() {
    private class Run {
        val cancelled = AtomicBoolean(false)
        var future: Future<*>? = null

        fun cancel() {
            cancelled.set(true)
            future?.cancel(false)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var activeRun: Run? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeRun?.cancel()
        val run = Run().also { activeRun = it }
        run.future = BatteryStore.executor.submit {
            if (run.cancelled.get()) return@submit
            val result = runCatching {
                BatteryStore.get(this).prune(System.currentTimeMillis())
                if (!run.cancelled.get()) BatteryWidgetProvider.updateAll(this)
            }
            handler.post {
                if (activeRun === run && !run.cancelled.get()) {
                    activeRun = null
                    jobFinished(params, result.isFailure)
                    if (result.isSuccess) schedule(this@BatteryMaintenanceService)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeRun?.cancel()
        activeRun = null
        return true
    }

    override fun onDestroy() {
        activeRun?.cancel()
        activeRun = null
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context, onComplete: () -> Unit = {}) {
            val application = context.applicationContext
            BatteryStore.executor.execute {
                try {
                    runCatching { reconcileSchedule(application) }
                        .onFailure { Log.e("BatteryMaintenance", "Maintenance scheduling failed", it) }
                } finally {
                    onComplete()
                }
            }
        }

        private fun reconcileSchedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!BatteryMonitorService.isEnabled(context) && !BatteryStore.get(context).hasHistory()) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) == null) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, BatteryMaintenanceService::class.java))
                    .setPeriodic(BatteryConfig.DAY_MS)
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
