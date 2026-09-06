package com.keltau.batterytimewidget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Only fixture 11: never generates, imports, deletes or reruns fixtures 01–10. */
class RealisticUsageImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = System.currentTimeMillis()
    private val output = File(context.getExternalFilesDir(null), "realistic-usage-fixture")
    private val checks = mutableListOf<String>()
    private fun <T> onStore(block: () -> T): T = BatteryStore.executor.submit(Callable(block)).get(30, TimeUnit.SECONDS)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun estimate(data: BatteryStore.Snapshot, percent: Int, mode: ScreenMode, at: Long = now) =
        HybridEstimator.estimate(percent, mode, data.summaries, data.chargeSummaries, at)
    private fun read(file: File, at: Long = now) = file.inputStream().use { DataTransfer.read(it, at) }
    private fun duration(seconds: Long?) = BatteryWidgetProvider.duration(context, seconds)
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for application/widget", condition())
    }

    @Test fun realisticUsageThroughApplication() {
        check(output.isDirectory || output.mkdirs())
        var success = false
        try {
            val history = RealisticUsageHistory.generate(now)
            val catalog = ImportFixtureCatalog(now)
            val case = catalog.realisticUsage(history)
            val file = File(output, "${case.name}.json").apply { writeText(catalog.encode(case).toString(2)) }
            val data = read(file)
            assertEquals(case.data, data)
            checks += "Fixture 11 serializes and imports both collector histories without loss; original ten fixtures are not generated or exercised."
            validate(history, data)
            writeExpectations(case, data)
            exercise(file, data)
            success = true
        } finally {
            File(output, "realistic-usage-test-results.md").writeText(buildString {
                appendLine("# Realistic usage sample test results\n")
                appendLine("Run: ${Instant.now()}. Result: ${if (success) "PASS" else "FAILED / incomplete"}.\n")
                appendLine("Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}. Only 11-realistic-daily-usage.json was tested; previous tests were not rerun.\n")
                checks.forEach { appendLine("- PASS $it") }
                appendLine("\n${checks.size} grouped checks completed. This synthetic history checks application behavior, not measured battery accuracy. Import uses an app-owned URI; the system document picker is outside this run.")
            })
        }
    }

    private fun validate(history: RealisticUsageHistory.History, data: BatteryStore.Snapshot) {
        assertEquals(42, data.summaries.map { it.day }.distinct().size)
        assertEquals(42, data.chargeSummaries.map { it.day }.distinct().size)
        assertTrue(data.chargeSummaries.sumOf { it.count } > data.summaries.sumOf { it.count })
        assertTrue(data.chargeRaw.any { it.exclusion == null && it.start.percent == it.end.percent && it.start.screen != it.end.screen })
        assertTrue(data.raw.mapNotNull { it.exclusion }.containsAll(listOf(Exclusion.CHARGING, Exclusion.SCREEN_CHANGE, Exclusion.SCREEN_OFF_AUDIO, Exclusion.AUDIO_UNKNOWN)))
        assertTrue(data.chargeRaw.mapNotNull { it.exclusion }.containsAll(listOf(ChargeExclusion.CHARGING, ChargeExclusion.SCREEN_OFF_AUDIO, ChargeExclusion.AUDIO_UNKNOWN, ChargeExclusion.UNAVAILABLE)))
        assertTrue(data.chargeRaw.filter { it.exclusion == null }.all {
            it.chargeUah > 0 && it.start.discharging && it.end.discharging && it.start.audioKnown && it.end.audioKnown &&
                !(it.start.screen == ScreenMode.OFF && it.start.audioActive)
        })
        val readings = data.chargeRaw.flatMap { listOf(it.start, it.end) }
        assertTrue(readings.mapNotNull { it.voltageMv }.distinct().size > 100)
        assertTrue(readings.mapNotNull { it.temperatureDeciC }.distinct().size > 30)
        assertTrue(readings.any { (it.currentUa ?: 0) > 0 } && readings.any { (it.currentUa ?: 0) < 0 })
        assertTrue(data.chargeRaw.map { it.seconds }.distinct().size >= 5)
        checks += "Six weeks of shared events produce distinct learning windows, accepted sub-percent screen transitions, charging/audio/unknown-sensor exclusions, varied electrical telemetry and irregular sample durations."

        val firstHour = history.charge.first().start.timeMs + 3_600_000
        val early = ImportFixtureCatalog(firstHour).realisticUsage(RealisticUsageHistory.History(
            history.percentage.filter { it.endMs <= firstHour }, history.charge.filter { it.end.timeMs <= firstHour },
        )).data
        val starting = estimate(early, 80, ScreenMode.OFF, firstHour)
        assertNull(starting.percentage.seconds)
        assertNotNull(starting.charge.time.seconds)
        assertEquals(1.0, starting.chargeWeight, 0.0)
        assertEquals(starting.charge.time.seconds, starting.seconds)
        checks += "During the first simulated hour the counter model is ready while the percentage model is still learning; the combined value uses 100% counter weight."

        for (mode in ScreenMode.entries) {
            var previous = 0L
            for (percent in 0..100) {
                val e = estimate(data, percent, mode)
                val p = requireNotNull(e.percentage.seconds)
                val c = requireNotNull(e.charge.time.seconds)
                val combined = requireNotNull(e.seconds)
                assertEquals(1.0, e.percentageWeight + e.chargeWeight, 1e-12)
                assertTrue(combined in minOf(p, c)..maxOf(p, c))
                assertEquals((p * e.percentageWeight + c * e.chargeWeight).roundToLong(), combined)
                if (percent <= 15) assertEquals(0L, combined) else {
                    assertTrue(e.percentageWeight > 0 && e.chargeWeight > 0)
                    assertTrue(combined >= previous)
                }
                previous = combined
            }
            val at80 = estimate(data, 80, mode)
            assertNotEquals(at80.percentage.seconds, at80.charge.time.seconds)
            // Broad physical bounds from the simulated capacity and workload, not
            // golden values copied from the estimator under test.
            val range = if (mode == ScreenMode.OFF) (36 * 3600L)..(240 * 3600L) else (3 * 3600L)..(8 * 3600L)
            assertTrue(at80.percentage.seconds!! in range)
            assertTrue(at80.charge.time.seconds!! in range)
        }
        checks += "At all 101 battery levels in both modes, estimates are monotonic, weights normalized and the blend lies between the two contributing estimates. At 80%, the models differ and both remain within workload-based physical bounds."
        val clean = data.copy(raw = data.raw.filter { it.exclusion == null }, chargeRaw = data.chargeRaw.filter { it.exclusion == null })
        for (percent in 0..100) for (mode in ScreenMode.entries) assertEquals(estimate(data, percent, mode), estimate(clean, percent, mode))
        checks += "Removing excluded diagnostic rows leaves both estimates and learning weights unchanged at every battery level."
    }

    private fun exercise(file: File, data: BatteryStore.Snapshot) {
        check(!BatteryMonitorService.isEnabled(context) && !BatteryMonitorService.running) { "Pause learning before fixture tests." }
        val original = onStore { BatteryStore.get(context).snapshot(now) }
        val host = AppWidgetHost(context, 84221)
        var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set status 3")
            lateinit var widget: AppWidgetHostView
            instrumentation.runOnMainSync {
                widgetId = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(context)
                assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, BatteryWidgetProvider::class.java), Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80)
                }))
                host.startListening()
                widget = host.createView(context, widgetId, manager.getAppWidgetInfo(widgetId))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainActivity.Model
                scenario.onActivity { model = ViewModelProvider(it)[MainActivity.Model::class.java] }
                fun idle() = awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = model.busy.value == false && model.snapshot.value != null }
                    ready
                }
                fun replace(source: File) {
                    idle()
                    scenario.onActivity { model.prepareImport(Uri.fromFile(source)) }
                    idle()
                    scenario.onActivity { assertNotNull(model.pendingImport.value) }
                    onView(withText(R.string.replace_data)).perform(click())
                    idle()
                    assertEquals(read(source, System.currentTimeMillis()), onStore { BatteryStore.get(context).snapshot(System.currentTimeMillis()) })
                }
                replace(file)
                checks += "The app's import confirmation replaces both SQLite histories exactly."
                for (percent in listOf(15, 50, 80)) {
                    shell("dumpsys battery set level $percent")
                    scenario.onActivity { model.refresh() }
                    idle()
                    val expected = ScreenMode.entries.associateWith { estimate(data, percent, it, System.currentTimeMillis()) }
                    scenario.onActivity { activity ->
                        ScreenMode.entries.forEach { mode ->
                            val e = expected.getValue(mode)
                            val timeId = if (mode == ScreenMode.OFF) R.id.estimate_off else R.id.estimate_on
                            val modelsId = if (mode == ScreenMode.OFF) R.id.models_off else R.id.models_on
                            val pWeight = (e.percentageWeight * 100).roundToInt()
                            assertEquals(duration(e.seconds), activity.findViewById<TextView>(timeId).text.toString())
                            assertEquals(context.getString(R.string.model_estimates, duration(e.percentage.seconds), duration(e.charge.time.seconds), pWeight, 100 - pWeight), activity.findViewById<TextView>(modelsId).text.toString())
                        }
                    }
                    awaitCondition {
                        var ready = false
                        instrumentation.runOnMainSync {
                            ready = ScreenMode.entries.all { mode ->
                                val id = if (mode == ScreenMode.OFF) R.id.widget_off else R.id.widget_on
                                widget.findViewById<TextView>(id)?.text?.toString() == BatteryWidgetProvider.widgetDuration(context, expected.getValue(mode).seconds).toString()
                            }
                        }
                        ready
                    }
                }
                screenshot("realistic-usage-overview.png")
                checks += "Overview component estimates, displayed reliability weights and combined values match the live AppWidgetHost at 15%, 50% and 80%, for both screen states."
                val exported = File(context.cacheDir, "realistic-roundtrip.json")
                scenario.onActivity { model.export(Uri.fromFile(exported)) }
                idle()
                assertEquals(data, read(exported, System.currentTimeMillis()))
                replace(exported)
                checks += "App export and re-import preserve summaries, raw intervals, nullable telemetry and exclusions for both algorithms."
                scenario.onActivity { activity ->
                    activity.findViewById<ScrollView>(R.id.main_root).apply { isSmoothScrollingEnabled = false; fullScroll(View.FOCUS_DOWN) }
                }
                instrumentation.waitForIdleSync()
                onView(withId(R.id.open_dev)).perform(click())
                onView(withId(R.id.dev_panel)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val text = activity.findViewById<TextView>(R.id.dev_summary).text.toString()
                    assertTrue(text.contains(context.getString(R.string.dev_counts, data.raw.size, data.summaries.sumOf { it.count }, data.summaries.map { it.day }.distinct().size)))
                    assertTrue(text.contains(context.getString(R.string.dev_charge_counts, data.chargeRaw.size, data.chargeSummaries.sumOf { it.count })))
                }
                screenshot("realistic-usage-developer.png")
                checks += "Developer display reports separate percentage/counter raw and learned counts."
            }
        } finally {
            shell("dumpsys battery reset")
            instrumentation.runOnMainSync {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
                host.stopListening()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            onStore { BatteryStore.get(context).replace(original, System.currentTimeMillis()); BatteryWidgetProvider.updateAll(context) }
        }
        checks += "Original app data and battery simulation restored; temporary widget removed."
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun writeExpectations(case: ImportFixtureCatalog.Case, data: BatteryStore.Snapshot) {
        val matrix = JSONArray().apply {
            listOf(0, 15, 16, 25, 50, 80, 100).forEach { percent ->
                put(JSONObject().put("batteryPercent", percent).apply {
                    ScreenMode.entries.forEach { mode ->
                        val e = estimate(data, percent, mode)
                        put(mode.name, JSONObject().put("percentageSeconds", e.percentage.seconds).put("chargeSeconds", e.charge.time.seconds)
                            .put("combinedSeconds", e.seconds).put("combinedDisplay", duration(e.seconds))
                            .put("percentageWeight", e.percentageWeight).put("chargeWeight", e.chargeWeight)
                            .put("percentageScore", e.percentage.reliability).put("chargeScore", e.charge.time.reliability)
                            .put("percentageCoverage", e.percentage.coverage).put("chargeCoverage", e.charge.time.coverage)
                            .put("percentageSamples", e.percentage.sampleCount).put("chargeSamples", e.charge.time.sampleCount)
                            .put("percentageDays", e.percentage.days).put("chargeDays", e.charge.time.days)
                            .put("chargeEquivalentPercent", e.charge.equivalentPercent).put("chargeCapacityUah", e.charge.capacityUah)
                            .put("established", e.established))
                    }
                })
            }
        }
        File(output, "realistic-usage-expected-results.json").writeText(JSONObject()
            .put("generatedAtUtc", Instant.ofEpochMilli(now)).put("generatedAtMs", now).put("file", "${case.name}.json")
            .put("description", case.description).put("syntheticTestData", true).put("randomSeed", 450042)
            .put("percentageRaw", data.raw.size).put("chargeRaw", data.chargeRaw.size)
            .put("expectedAtGeneration", matrix).toString(2))
        File(output, "realistic-usage-guide.md").writeText(buildString {
            appendLine("# Realistic daily usage sample\n")
            appendLine("[11-realistic-daily-usage.json](11-realistic-daily-usage.json) is an additional valid import; the original ten files and their test results are preserved. Generated ${Instant.ofEpochMilli(now)}.\n")
            appendLine("${case.description} This is a simulation, not a real person's recorded data. Seed 450042 reproduces the usage pattern. Day schedules use UTC hours as a fixed local-day stand-in; the data covers the 42 complete days before generation.\n")
            appendLine("The battery starts at 72%. Charge is integrated continuously from net current, with a 25 µAh counter resolution and rounded integer percentage. Short checks, longer evening use, additional weekend activity, two music sessions, evening charging with taper near full and occasional partial top-ups produce different learning windows for each model. Voltage changes with state of charge and load; temperature and average current change gradually. Brief counter and audio-status outages exercise exclusions.\n")
            appendLine("One-minute integration emits simulated battery broadcasts every 2–6 minutes and at percentage, screen, audio, charging and sensor-availability changes. Both production trackers receive exactly the same event stream. Only accepted intervals create summaries; the export retains six weeks of learning and the most recent seven days of raw diagnostics.\n")
            appendLine("Short screen checks interrupt many full-percentage quiet windows. The percentage model therefore learns much of its screen-off history during long overnight idle, while the counter model also captures shorter daytime idle. The screen-off estimates can differ substantially and remain provisional after six weeks because battery-band coverage is sparse.\n")
            appendLine("## Expected at 80% on the generation date\n")
            appendLine("| Screen | Percentage | Counter | Combined/widget | P / C weight |\n| --- | --- | --- | --- | --- |")
            ScreenMode.entries.forEach { mode ->
                val e = estimate(data, 80, mode)
                val p = (e.percentageWeight * 100).roundToInt()
                appendLine("| $mode | ${duration(e.percentage.seconds)} | ${duration(e.charge.time.seconds)} | ${duration(e.seconds)} | $p% / ${100 - p}% |")
            }
            appendLine("\nExact seconds, scores, coverage and sample counts at 0/15/16/25/50/80/100% are in [realistic-usage-expected-results.json](realistic-usage-expected-results.json). This report is not an import file. The app uses the phone's current percentage; neither time is a mixed-use daily-runtime forecast. Missing bands are estimated from learned rates.\n")
            appendLine("Export your own history, pause learning, import file 11 and confirm replacement. Both models and their combination should appear in the overview; the widget shows the combination. Restore your backup afterwards. Raw diagnostics age out after seven days and daily learning after 56 days, so later imports may show fewer records.\n")
            appendLine("## Run only this sample\n")
            appendLine("Build/install the debug app and test APK, then select an emulator with learning paused:\n")
            appendLine("```powershell\nadb -s SERIAL shell am instrument -w -e class com.keltau.batterytimewidget.RealisticUsageImportTest com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner\nadb -s SERIAL pull /sdcard/Android/data/com.keltau.batterytimewidget/files/realistic-usage-fixture/. test-data/\n```\n")
            appendLine("This generates only file 11 and its separate report/screenshots; it does not invoke ImportFixturesTest or write any of its files. See [realistic-usage-test-results.md](realistic-usage-test-results.md) for the actual run. Simulated current/voltage are diagnostic inputs; the algorithms learn from percentage changes and charge-counter changes, respectively.")
        })
    }
}
