package com.keltau.batterytimewidget

import android.content.Context
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcel
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class BatteryWidgetLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun restoringBackgroundAfterLaunchAnimationKeepsBothDurationParts() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            for (size in listOf(SizeF(110f, 56f), SizeF(180f, 80f), SizeF(180f, 180f), SizeF(320f, 180f))) {
                val root = render(context, size, 23 * 3600L + 59 * 60L, 12 * 86400L + 23 * 3600L)
                val content = root.findViewById<View>(R.id.widget_root)
                val padding = listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
                val background = content.background
                repeat(3) {
                    // The launcher temporarily moves the background to its animation overlay.
                    content.background = null
                    layout(context, size, root)
                    content.background = background
                    // No provider refresh or widget resize: return directly to the home screen.
                    layout(context, size, root)
                    for (id in listOf(R.id.widget_off, R.id.widget_on)) {
                        val time = root.findViewById<TextView>(id)
                        assertEquals("Duration wrapped after animation at $size: ${time.text}", 1, time.layout.lineCount)
                        assertTrue("Duration clipped after animation at $size", time.layout.getLineWidth(0) <= time.width + 1)
                        assertTrue("Duration clipped vertically at $size", time.layout.height <= time.height)
                    }
                    assertEquals("Animation changed widget padding at $size", padding,
                        listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
                }
            }
        }
    }

    @Test fun widgetCanBeBuiltOnTheStorageExecutor() {
        val context = instrumentation.targetContext.applicationContext
        BatteryStore.executor.submit<RemoteViews> {
            BatteryWidgetProvider.createViews(context, SizeF(180f, 80f), 23 * 3600L + 59 * 60L,
                12 * 86400L + 23 * 3600L, context.getString(R.string.provisional), "85% · updated 12:34")
        }.get(10, TimeUnit.SECONDS)
    }

    @Test fun repeatedUpdatesKeepTheFullDurationWithoutResizing() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            for (size in listOf(SizeF(110f, 56f), SizeF(180f, 80f), SizeF(180f, 180f), SizeF(320f, 180f))) {
                val root = render(context, size, null, null)
                val changes = listOf(0L to 0L, 9 * 3600L to 20 * 60L,
                    (23 * 3600L + 59 * 60L) to (12 * 86400L + 23 * 3600L),
                    3600L to 86400L, null to 3600L, 60L to 60L)
                repeat(3) {
                    for ((off, on) in changes) {
                        val update = BatteryWidgetProvider.createViews(context, size, off, on,
                            context.getString(R.string.provisional), "85% · updated 12:35")
                        parcelCopy(update).reapply(context, root)
                        layout(context, size, root)
                        val times = listOf(root.findViewById<TextView>(R.id.widget_off), root.findViewById<TextView>(R.id.widget_on))
                        assertEquals(times[0].textSize, times[1].textSize, 0f)
                        times.zip(listOf(off, on)).forEach { (time, seconds) ->
                            assertEquals(BatteryWidgetProvider.widgetDuration(context, seconds).toString(), time.text.toString())
                            assertEquals("$size, $off / $on", 1, time.layout.lineCount)
                            assertTrue("$size, $off / $on", time.layout.getLineWidth(0) <= time.width + 1)
                            assertTrue(time.layout.height <= time.height)
                        }
                    }
                }
            }
        }
    }

    @Test fun asynchronousLauncherUpdatesKeepBothDurationParts() {
        val context = instrumentation.targetContext
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (size in listOf(SizeF(180f, 80f), SizeF(180f, 180f), SizeF(320f, 180f))) {
                lateinit var root: AppWidgetHostView
                instrumentation.runOnMainSync {
                    root = AppWidgetHostView(context).apply {
                        setAppWidget(-1, AppWidgetManager.getInstance(context).installedProviders.first {
                            it.provider.className == BatteryWidgetProvider::class.java.name
                        })
                        setPadding(0, 0, 0, 0)
                        setExecutor(executor)
                    }
                    layout(context, size, root)
                }
                for ((off, on) in listOf(0L to 0L, (23 * 3600L + 59 * 60L) to (12 * 86400L + 23 * 3600L),
                    3600L to 86400L, 60L to 60L, (23 * 3600L + 59 * 60L) to (12 * 86400L + 23 * 3600L))) {
                    val update = BatteryWidgetProvider.createViews(context, size, off, on,
                        context.getString(R.string.provisional), "85% · updated 12:35")
                    instrumentation.runOnMainSync {
                        root.updateAppWidget(parcelCopy(update))
                    }
                    executor.submit {}.get(10, TimeUnit.SECONDS)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        layout(context, size, root)
                        for (id in listOf(R.id.widget_off, R.id.widget_on)) {
                            val time = root.findViewById<TextView>(id)
                            assertEquals("Async update: $size, $off / $on", 1, time.layout.lineCount)
                            assertTrue("Async width: $size, $off / $on", time.layout.getLineWidth(0) <= time.width + 1)
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun estimatesShareOneSizeAndFitAfterLauncherSerialization() {
        instrumentation.runOnMainSync {
            val sizes = listOf(SizeF(110f, 56f), SizeF(180f, 80f), SizeF(180f, 180f),
                SizeF(250f, 180f), SizeF(320f, 180f), SizeF(400f, 240f))
            val estimates = listOf(0L to 0L, 9 * 3600L to 20 * 60L,
                (23 * 3600L + 59 * 60L) to (12 * 86400L + 23 * 3600L), null to 3600L, null to null)
            for (scale in listOf(1f, 1.5f)) {
                val context = instrumentation.targetContext.createConfigurationContext(
                    Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = scale })
                for (size in sizes) for ((off, on) in estimates) {
                    val root = render(context, size, off, on)
                    val times = listOf(root.findViewById<TextView>(R.id.widget_off), root.findViewById<TextView>(R.id.widget_on))
                    val scenario = "$size, font scale $scale, $off / $on"
                    assertEquals(scenario, times[0].textSize, times[1].textSize, 0f)
                    assertTrue(scenario, times[0].textSize <= TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 56f, context.resources.displayMetrics))
                    times.forEach { time ->
                        assertEquals(scenario, 1, time.layout.lineCount)
                        assertTrue("Width: $scenario", time.layout.getLineWidth(0) <= time.width + 1)
                        assertTrue("Height: $scenario", time.layout.height <= time.height)
                        // No weighted empty area above or below the line of text.
                        assertTrue("Extra space: $scenario", time.height - time.layout.height <= 1)
                        val column = time.parent as ViewGroup
                        val label = column.getChildAt(0)
                        assertEquals("Descriptor gap: $scenario", label.bottom, time.top)
                    }
                    val estimatesView = root.findViewById<View>(R.id.widget_estimates)
                    assertTrue("Content fits: $scenario", estimatesView.bottom <= root.height - root.paddingBottom)
                    val updated = root.findViewById<View>(R.id.widget_updated)
                    if (updated.visibility == View.VISIBLE) {
                        assertTrue("Footer fits: $scenario", updated.bottom <= root.height - root.paddingBottom)
                    }
                }
            }
        }
    }

    @Test fun renderWidgetPreviews() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            for ((name, size) in listOf("horizontal" to SizeF(180f, 80f),
                "square" to SizeF(180f, 180f), "expanded" to SizeF(320f, 180f))) {
                val root = render(context, size, 9 * 3600L, 20 * 60L)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.cacheDir, "widget-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    private fun render(context: Context, size: SizeF, off: Long?, on: Long?): View {
        val original = BatteryWidgetProvider.createViews(context, size, off, on,
            context.getString(R.string.provisional), "85% · updated 12:34")
        val root = parcelCopy(original).apply(context, FrameLayout(context))
        layout(context, size, root)
        return root
    }

    private fun parcelCopy(original: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun layout(context: Context, size: SizeF, root: View) {
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).toInt()
        val height = (size.height * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }
}
