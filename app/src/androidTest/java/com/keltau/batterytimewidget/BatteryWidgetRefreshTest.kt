package com.keltau.batterytimewidget

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatteryWidgetRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun chargeOnlyWidgetStartsEarlyAndLaterBlendsBothModels() = withWidget { host, _ ->
        val now = System.currentTimeMillis()
        val charge = ScreenMode.entries.map { mode -> ChargeSummary(now / BatteryConfig.DAY_MS, mode, 10, 3,
            180.0, 6000.0, 200000.0, 4_000_000.0 * 180, 4_000_000.0 * 4_000_000.0 * 180) }
        val early = BatteryStore.Snapshot(emptyList(), emptyList(), charge)
        BatteryStore.executor.submit {
            BatteryStore.get(context).replace(early, now)
            BatteryWidgetProvider.updateAll(context)
        }.get(10, TimeUnit.SECONDS)
        await { shows(host, early) }
        val learned = early.copy(summaries = data(now, 600.0).summaries)
        BatteryStore.executor.submit {
            BatteryStore.get(context).replace(learned, now)
            BatteryWidgetProvider.updateAll(context)
        }.get(10, TimeUnit.SECONDS)
        await { shows(host, learned) }
        val estimate = HybridEstimator.estimate(50, ScreenMode.OFF, learned.summaries, charge, now)
        assertTrue(estimate.chargeWeight > 0 && estimate.percentageWeight > 0)
        assertTrue(estimate.seconds!! > estimate.percentage.seconds!!)
        assertTrue(estimate.seconds < estimate.charge.time.seconds!!)
    }

    @Test fun appRefreshUpdatesAPausedWidgetWithoutResizing() = withWidget { host, id ->
        val now = System.currentTimeMillis()
        val data = data(now, 600.0)
        BatteryStore.executor.submit { BatteryStore.get(context).replace(data, now) }.get(10, TimeUnit.SECONDS)
        AppWidgetManager.getInstance(context).updateAppWidget(id, BatteryWidgetProvider.createViews(
            context, SizeF(180f, 80f), null, null, "stale", "stale"))
        await { host.findViewById<TextView>(R.id.widget_off)?.text?.toString() == context.getString(R.string.learning) }

        lateinit var model: MainActivity.Model
        instrumentation.runOnMainSync {
            model = MainActivity.Model(context.applicationContext as Application)
            model.refresh()
        }
        await { model.busy.value == false && model.snapshot.value?.summaries == data.summaries && shows(host, data) }
    }

    @Test fun refreshRequestedWhileBusyIsDeliveredAfterTheNewData() = withWidget { host, _ ->
        val now = System.currentTimeMillis()
        val first = data(now, 300.0)
        val second = data(now, 900.0)
        BatteryStore.executor.submit { BatteryStore.get(context).replace(first, now) }.get(10, TimeUnit.SECONDS)
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val blocker = BatteryStore.executor.submit {
            blocked.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        lateinit var model: MainActivity.Model
        try {
            instrumentation.runOnMainSync {
                model = MainActivity.Model(context.applicationContext as Application)
                model.refresh()
            }
            // A new sample arrives after the first snapshot read, while the UI is still busy.
            BatteryStore.executor.execute { BatteryStore.get(context).replace(second, now) }
            instrumentation.runOnMainSync { model.refresh() }
        } finally {
            release.countDown()
        }
        blocker.get(10, TimeUnit.SECONDS)
        await { model.busy.value == false && model.snapshot.value?.summaries == second.summaries && shows(host, second) }
    }

    private fun data(now: Long, seconds: Double) = BatteryStore.Snapshot(
        listOf(ScreenMode.OFF, ScreenMode.ON).map { mode ->
            val rate = if (mode == ScreenMode.OFF) seconds else seconds / 3
            DailySummary(now / BatteryConfig.DAY_MS, mode, 10, 10, rate * 10, rate * rate * 10)
        }.sortedBy { it.screen.name }, emptyList())

    private fun shows(host: AppWidgetHostView, data: BatteryStore.Snapshot): Boolean {
        val percent = BatteryMonitorService.percent(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val now = System.currentTimeMillis()
        return listOf(ScreenMode.OFF to R.id.widget_off, ScreenMode.ON to R.id.widget_on).all { (mode, id) ->
            val expected = BatteryWidgetProvider.widgetDuration(context,
                HybridEstimator.estimate(percent, mode, data.summaries, data.chargeSummaries, now).seconds).toString()
            host.findViewById<TextView>(id)?.text?.toString() == expected
        }
    }

    private fun withWidget(block: (AppWidgetHostView, Int) -> Unit) {
        val original = BatteryStore.executor.submit<BatteryStore.Snapshot> {
            BatteryStore.get(context).snapshot(System.currentTimeMillis())
        }.get(10, TimeUnit.SECONDS)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        lateinit var host: AppWidgetHost
        var id = AppWidgetManager.INVALID_APPWIDGET_ID
        try {
            lateinit var view: AppWidgetHostView
            instrumentation.runOnMainSync {
                host = AppWidgetHost(context, 84219)
                id = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(context)
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80)
                }
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, BatteryWidgetProvider::class.java), options))
                host.startListening()
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
            }
            await { view.findViewById<TextView>(R.id.widget_off) != null }
            // Finish initial provider callbacks before testing app-driven updates.
            instrumentation.waitForIdleSync()
            BatteryStore.executor.submit {}.get(10, TimeUnit.SECONDS)
            block(view, id)
        } finally {
            instrumentation.runOnMainSync {
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    host.deleteAppWidgetId(id)
                    host.stopListening()
                }
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            BatteryStore.executor.submit {
                BatteryStore.get(context).replace(original, System.currentTimeMillis())
            }.get(10, TimeUnit.SECONDS)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("App and widget did not reach the same updated state")
    }
}
