package com.keltau.batterytimewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Date

class BatteryWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        BatteryStore.executor.execute {
            runCatching { updateAll(context) }.onFailure { Log.e("BatteryWidget", "Widget refresh failed", it) }
            BatteryMaintenanceService.schedule(context) { pending.finish() }
        }
    }

    companion object {
        private data class TimeSizeKey(val size: SizeF, val configuration: Configuration,
            val off: String, val on: String, val offLearning: Boolean, val onLearning: Boolean,
            val status: String, val updated: String)
        private val timeSizes = LruCache<TimeSizeKey, Int>(128)
        private val durationUnits = Regex("[^\\p{Nd}]+")

        fun updateAll(context: Context, snapshot: BatteryStore.Snapshot? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis()
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val percent = BatteryMonitorService.percent(battery)
            val summaries = snapshot?.summaries ?: BatteryStore.get(context).summaries(now)
            val chargeSummaries = snapshot?.chargeSummaries ?: BatteryStore.get(context).chargeSummaries(now)
            val off = HybridEstimator.estimate(percent, ScreenMode.OFF, summaries, chargeSummaries, now)
            val on = HybridEstimator.estimate(percent, ScreenMode.ON, summaries, chargeSummaries, now)
            val failure = BatteryMonitorService.failure
            val status = when {
                failure != null -> failure
                percent < 0 -> context.getString(R.string.battery_unavailable)
                percent <= BatteryConfig.TARGET_PERCENT -> context.getString(R.string.target_reached)
                !BatteryMonitorService.running -> context.getString(if (BatteryMonitorService.isEnabled(context)) R.string.widget_waiting else R.string.widget_paused)
                battery != null && !BatteryMonitorService.isDischarging(battery) -> context.getString(R.string.unplugged_projection)
                off.seconds == null || on.seconds == null -> context.getString(R.string.widget_learning)
                off.percentage.seconds == null || on.percentage.seconds == null -> context.getString(R.string.widget_charge_learning)
                !off.established || !on.established -> context.getString(R.string.provisional)
                else -> context.getString(R.string.learned_estimate)
            }
            val updated = context.getString(R.string.widget_updated,
                if (percent < 0) "—" else "$percent%", DateFormat.getTimeFormat(context).format(Date(now)))
            val layouts = mutableMapOf<SizeF, RemoteViews>()
            fun views(size: SizeF) = layouts.getOrPut(size) {
                createViews(context, size, off.seconds, on.seconds, status, updated)
            }
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val portrait = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceAtLeast(1).toFloat())
                val landscape = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250).coerceAtLeast(1).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(1).toFloat())
                if (Build.VERSION.SDK_INT >= 31) {
                    @Suppress("DEPRECATION")
                    val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                        .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct().take(16)
                        .ifEmpty { listOf(portrait, landscape).distinct() }
                    manager.updateAppWidget(id, RemoteViews(sizes.associateWith { views(it) }))
                } else {
                    manager.updateAppWidget(id, RemoteViews(views(landscape), views(portrait)))
                }
            }
        }

        fun createViews(context: Context, size: SizeF, offSeconds: Long?, onSeconds: Long?,
                        status: String, updated: String): RemoteViews {
            val compact = size.width < 180 || size.height < 180
            val off = widgetDuration(context, offSeconds)
            val on = widgetDuration(context, onSeconds)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val views = RemoteViews(context.packageName, R.layout.battery_widget).apply {
                val visibility = if (compact) View.GONE else View.VISIBLE
                setViewVisibility(R.id.widget_title, visibility)
                setViewVisibility(R.id.widget_status, visibility)
                setViewVisibility(R.id.widget_updated, visibility)
                setInt(R.id.widget_root, "setGravity", if (compact) Gravity.CENTER_VERTICAL else Gravity.TOP)
                val horizontal = dp(if (compact) 4 else 12)
                val vertical = dp(if (compact) 0 else 8)
                val spacing = dp(if (compact) 0 else 4)
                setViewPadding(R.id.widget_root, horizontal, vertical, horizontal, vertical)
                setViewPadding(R.id.widget_estimates, 0, spacing, 0, spacing)
                setTextViewText(R.id.widget_off, off)
                setTextViewText(R.id.widget_on, on)
                setContentDescription(R.id.widget_off, duration(context, offSeconds))
                setContentDescription(R.id.widget_on, duration(context, onSeconds))
                setTextViewText(R.id.widget_status, status)
                setTextViewText(R.id.widget_updated, updated)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 2,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            val key = TimeSizeKey(size, Configuration(context.resources.configuration),
                off.toString(), on.toString(), offSeconds == null, onSeconds == null,
                if (compact) "" else status, if (compact) "" else updated)
            val sharedSize = timeSizes.get(key) ?: measureSharedTimeSize(context, views, size).also { timeSizes.put(key, it) }
            for (id in listOf(R.id.widget_off, R.id.widget_on)) {
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sharedSize.toFloat())
            }
            return views
        }

        private fun measureSharedTimeSize(context: Context, views: RemoteViews, size: SizeF): Int {
            // Measure the same layout the launcher receives, including spans and font scaling.
            // One shared size prevents a shorter estimate from growing larger than the other.
            val root = views.apply(context, FrameLayout(context))
            val times = listOf(root.findViewById<TextView>(R.id.widget_off), root.findViewById<TextView>(R.id.widget_on))
            val density = context.resources.displayMetrics.density
            val width = (size.width * density).toInt()
            val height = (size.height * density).toInt()
            return (56 downTo 8).firstOrNull { candidate ->
                times.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, candidate.toFloat()) }
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.measuredHeight <= height && times.all {
                    Layout.getDesiredWidth(it.text, it.paint) <= it.measuredWidth - it.totalPaddingLeft - it.totalPaddingRight
                }
            } ?: 8
        }

        fun widgetDuration(context: Context, seconds: Long?): CharSequence {
            val text = SpannableString(duration(context, seconds, compact = true))
            if (seconds == null) {
                text.setSpan(RelativeSizeSpan(0.6f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // Smaller units and spaces leave more room for the important digits.
                durationUnits.findAll(text).forEach { match ->
                    text.setSpan(RelativeSizeSpan(0.7f), match.range.first, match.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return text
        }

        fun duration(context: Context, seconds: Long?, compact: Boolean = false): String {
            if (seconds == null) return context.getString(R.string.learning)
            if (seconds == 0L) {
                return if (compact) context.getString(R.string.duration_minutes_compact, 0L)
                else context.getString(R.string.zero_minutes)
            }
            val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> context.getString(if (compact) R.string.duration_days_compact else R.string.duration_days, days, hours % 24)
                hours > 0 -> context.getString(if (compact) R.string.duration_hours_compact else R.string.duration_hours, hours, minutes % 60)
                else -> context.getString(if (compact) R.string.duration_minutes_compact else R.string.duration_minutes, minutes)
            }
        }
    }
}
