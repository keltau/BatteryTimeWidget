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
import androidx.test.espresso.action.ViewActions.*
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

/** Regenerates, validates and imports the complete catalog in one reproducible run. */
class ImportFixturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = System.currentTimeMillis()
    private val catalog = ImportFixtureCatalog(now)
    private val output = File(context.getExternalFilesDir(null), "import-fixtures")
    private val checks = mutableListOf<String>()
    private fun <T> onStore(block: () -> T): T = BatteryStore.executor.submit(Callable(block)).get(30, TimeUnit.SECONDS)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for application/widget", condition())
    }
    private fun read(file: File, at: Long = now) = file.inputStream().use { DataTransfer.read(it, at) }
    private fun estimate(data: BatteryStore.Snapshot, percent: Int, mode: ScreenMode, at: Long = now) =
        HybridEstimator.estimate(percent, mode, data.summaries, data.chargeSummaries, at)
    private fun duration(seconds: Long?) = BatteryWidgetProvider.duration(context, seconds)

    private fun generate(): List<File> {
        check(output.isDirectory || output.mkdirs())
        // This app-owned folder contains generated fixtures/results only, never user exports.
        output.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
        val restored = linkedMapOf<String, BatteryStore.Snapshot>()
        val files = catalog.cases.map { case ->
            File(output, "${case.name}.json").also {
                it.writeText(catalog.encode(case).toString(2))
                restored[case.name] = read(it)
            }
        }
        catalog.validate(restored)
        checks += "Independent analytic agreement/recency oracles pass at every percentage 0–100 in both modes for both algorithms. Blends, coverage, learning thresholds and opposite reliability preferences pass."
        assertEquals(1, read(files[5], now + 1).raw.size)
        assertEquals(1, read(files[5], now + 1).chargeRaw.size)
        val week = read(files[5], now + 8 * BatteryConfig.DAY_MS)
        assertTrue(week.raw.isEmpty() && week.chargeRaw.isEmpty())
        assertTrue(week.summaries.isNotEmpty() && week.chargeSummaries.isNotEmpty())
        assertEquals(BatteryStore.Snapshot(emptyList(), emptyList()), read(files[0], now + 57 * BatteryConfig.DAY_MS))
        checks += "Both models enforce inclusive seven-day raw and 56-day summary boundaries; raw expiry retains learning, and all history expires later."
        val report = JSONObject().put("generatedAtUtc", Instant.ofEpochMilli(now).toString()).put("generatedAtMs", now)
        report.put("validScenarios", JSONArray().apply {
            catalog.cases.forEach { case ->
                val data = restored.getValue(case.name)
                put(JSONObject().put("file", "${case.name}.json").put("description", case.description).put("version", case.version)
                    .put("beforeImport", counts(case.data)).put("afterImportAtGeneration", counts(data))
                    .put("expectedAtGeneration", JSONArray().apply {
                        listOf(0, 15, 16, 25, 50, 80, 100).forEach { percent ->
                            put(JSONObject().put("batteryPercent", percent).apply {
                                ScreenMode.entries.forEach { mode -> put(mode.name, forecast(estimate(data, percent, mode))) }
                            })
                        }
                    }))
            }
        })
        val invalid = listOf(
            "09-invalid-version.json" to catalog.encode(catalog.cases[0].copy(data = BatteryStore.Snapshot(emptyList(), emptyList()))).put("version", 99),
            "10-invalid-counter-reset.json" to catalog.encode(catalog.cases[3]).apply {
                getJSONArray("chargeRawIntervals").getJSONObject(0).getJSONObject("end").put("chargeUah", 2_100_000)
            },
        ).map { (name, json) -> File(output, name).also { it.writeText(json.toString(2)) } }
        report.put("invalidScenarios", JSONArray().apply {
            invalid.forEach { file ->
                val failure = assertThrows(IllegalArgumentException::class.java) { read(file) }
                put(JSONObject().put("file", file.name).put("expectedError", failure.message))
            }
        })
        File(output, "expected-results.json").writeText(report.toString(2))
        File(output, "README.md").writeText(guide(restored))
        return files + invalid
    }

    private fun counts(data: BatteryStore.Snapshot) = JSONObject()
        .put("percentageSummaries", data.summaries.size).put("percentageSamples", data.summaries.sumOf { it.count })
        .put("percentageRaw", data.raw.size).put("chargeSummaries", data.chargeSummaries.size)
        .put("chargeSamples", data.chargeSummaries.sumOf { it.count }).put("chargeRaw", data.chargeRaw.size)

    private fun forecast(value: HybridEstimate) = JSONObject()
        .put("percentageSeconds", value.percentage.seconds ?: JSONObject.NULL).put("chargeSeconds", value.charge.time.seconds ?: JSONObject.NULL)
        .put("combinedSeconds", value.seconds ?: JSONObject.NULL).put("combinedDisplay", duration(value.seconds))
        .put("percentageWeight", value.percentageWeight).put("chargeWeight", value.chargeWeight)
        .put("percentageScore", value.percentage.reliability).put("chargeScore", value.charge.time.reliability)
        .put("percentageCoverage", value.percentage.coverage).put("chargeCoverage", value.charge.time.coverage)
        .put("percentageDays", value.percentage.days).put("chargeDays", value.charge.time.days)
        .put("percentageSamples", value.percentage.sampleCount).put("chargeSamples", value.charge.time.sampleCount)
        .put("chargeEquivalentPercent", value.charge.equivalentPercent).put("established", value.established)

    @Test fun importFixturesThroughApplication() {
        val files = generate()
        check(!BatteryMonitorService.isEnabled(context) && !BatteryMonitorService.running) { "Pause learning before fixture tests." }
        val original = onStore { BatteryStore.get(context).snapshot(now) }
        val host = AppWidgetHost(context, 84220)
        var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        var success = false
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set status 3")
            shell("dumpsys battery set level 80")
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
                fun prepare(file: File) {
                    idle()
                    scenario.onActivity { model.prepareImport(Uri.fromFile(file)) }
                    idle()
                    scenario.onActivity { assertNotNull("Import should succeed: ${file.name}", model.pendingImport.value) }
                }
                fun replace(file: File): BatteryStore.Snapshot {
                    prepare(file)
                    onView(withText(R.string.replace_data)).perform(click())
                    idle()
                    val time = System.currentTimeMillis()
                    val expected = read(file, time)
                    assertEquals(file.name, expected, onStore { BatteryStore.get(context).snapshot(time) })
                    return expected
                }
                fun verifyScreen(data: BatteryStore.Snapshot, percent: Int) {
                    shell("dumpsys battery set level $percent")
                    scenario.onActivity { model.refresh() }
                    idle()
                    val expected = ScreenMode.entries.associateWith { estimate(data, percent, it, System.currentTimeMillis()) }
                    scenario.onActivity { activity ->
                        ScreenMode.entries.forEach { mode ->
                            val e = expected.getValue(mode)
                            val timeId = if (mode == ScreenMode.OFF) R.id.estimate_off else R.id.estimate_on
                            val modelsId = if (mode == ScreenMode.OFF) R.id.models_off else R.id.models_on
                            assertEquals(duration(e.seconds), activity.findViewById<TextView>(timeId).text.toString())
                            val pWeight = (e.percentageWeight * 100).roundToInt()
                            assertEquals(context.getString(R.string.model_estimates, duration(e.percentage.seconds), duration(e.charge.time.seconds),
                                pWeight, if (e.seconds == null) 0 else 100 - pWeight), activity.findViewById<TextView>(modelsId).text.toString())
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
                for (file in files.take(8)) {
                    val data = replace(file)
                    listOf(15, 50, 80).forEach { verifyScreen(data, it) }
                    checks += "${file.name}: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%."
                    val exported = File(context.cacheDir, "fixture-roundtrip.json")
                    scenario.onActivity { model.export(Uri.fromFile(exported)) }
                    idle()
                    assertEquals(data, read(exported, System.currentTimeMillis()))
                    replace(exported)
                    assertEquals(data, onStore { BatteryStore.get(context).snapshot(System.currentTimeMillis()) })
                    checks += "${file.name}: app export and re-import are lossless and idempotent."
                    scenario.onActivity { activity ->
                        assertEquals(data, model.snapshot.value)
                        activity.findViewById<ScrollView>(R.id.main_root).apply {
                            isSmoothScrollingEnabled = false
                            fullScroll(View.FOCUS_DOWN)
                        }
                    }
                    instrumentation.waitForIdleSync()
                    onView(withId(R.id.open_dev)).perform(click())
                    onView(withId(R.id.dev_panel)).check(matches(isDisplayed()))
                    scenario.onActivity { activity ->
                        val text = activity.findViewById<TextView>(R.id.dev_summary).text.toString()
                        val expectedCounts = context.getString(R.string.dev_counts, data.raw.size, data.summaries.sumOf { it.count }, data.summaries.map { it.day }.distinct().size)
                        assertTrue("${file.name}: expected $expectedCounts; actual $text", text.contains(expectedCounts))
                        assertTrue(text.contains(context.getString(R.string.dev_charge_counts, data.chargeRaw.size, data.chargeSummaries.sumOf { it.count })))
                    }
                    if (file.name.startsWith("03-")) screenshot("developer-exclusions.png")
                    onView(withId(R.id.close_dev)).perform(click())
                    onView(withId(R.id.overview_panel)).check(matches(isDisplayed()))
                    if (file.name.startsWith("01-") || file.name.startsWith("04-") || file.name.startsWith("08-")) screenshot("overview-${file.name.removeSuffix(".json")}.png")
                    checks += "${file.name}: developer statistics show correct separate model counts."
                }
                val reference = replace(files[0])
                prepare(files[6])
                onView(withText(R.string.cancel)).perform(click())
                assertEquals(reference, onStore { BatteryStore.get(context).snapshot(System.currentTimeMillis()) })
                checks += "Cancelled replacement preserves both histories."
                fun reject(file: File) {
                    idle()
                    scenario.onActivity { model.prepareImport(Uri.fromFile(file)) }
                    idle()
                    scenario.onActivity { assertNull("Must reject ${file.name}", model.pendingImport.value) }
                    assertEquals(reference, onStore { BatteryStore.get(context).snapshot(System.currentTimeMillis()) })
                }
                files.drop(8).forEach { reject(it); checks += "${it.name}: app rejects import and preserves both histories." }
                malformedMutations().forEach { (name, mutate) ->
                    val json = catalog.encode(catalog.cases[0])
                    mutate(json)
                    val file = File(context.cacheDir, "invalid-fixture.json").apply { writeText(json.toString()) }
                    assertThrows(Exception::class.java) { read(file) }
                    reject(file)
                    checks += "Transient malformed variant rejected without data loss: $name."
                }
                val informational = File(context.cacheDir, "cached-estimates.json").apply {
                    writeText(catalog.encode(catalog.cases[0]).apply { put("estimates", JSONObject().put("OFF", 1).put("ON", 1)) }.toString())
                }
                assertEquals(reference, replace(informational))
                verifyScreen(reference, 80)
                checks += "Imported cached predictions are ignored; both algorithms and widget values are recomputed."
                val excluded = read(files[2], System.currentTimeMillis())
                val clean = excluded.copy(raw = excluded.raw.filter { it.exclusion == null }, chargeRaw = excluded.chargeRaw.filter { it.exclusion == null })
                val cleanFile = File(context.cacheDir, "without-exclusions.json")
                cleanFile.outputStream().use { DataTransfer.write(it, clean, 80, now) }
                val cleaned = replace(cleanFile)
                for (percent in 0..100) for (mode in ScreenMode.entries) assertEquals(estimate(excluded, percent, mode), estimate(cleaned, percent, mode))
                verifyScreen(cleaned, 80)
                checks += "Removing excluded raw rows preserves both model times, weights and sample counts at every battery level; raw diagnostics are not replayed as learning."
            }
            success = true
        } finally {
            shell("dumpsys battery reset")
            instrumentation.runOnMainSync {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
                host.stopListening()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            onStore { BatteryStore.get(context).replace(original, System.currentTimeMillis()); BatteryWidgetProvider.updateAll(context) }
            File(output, "application-test-results.md").writeText(buildString {
                appendLine("# Import fixture application test results\n")
                appendLine("Run: ${Instant.now()}. Result: ${if (success) "PASS" else "FAILED / incomplete"}.\n")
                appendLine("Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}. Eight valid and two invalid import files.\n")
                checks.forEach { appendLine("- PASS $it") }
                appendLine("\n${checks.size} grouped checks completed. Data and battery simulation restored; temporary widget removed.")
                appendLine("\nScope: real import confirmation, MainActivity.Model, SQLite, developer/overview views, app export and AppWidgetHost updates. File access uses app-owned URIs; this run does not automate the system document picker. Synthetic histories test implementation behavior, not physical battery accuracy.")
            })
        }
    }

    private fun malformedMutations(): Map<String, (JSONObject) -> Unit> = linkedMapOf(
        "duplicate percentage summary" to { root -> root.getJSONArray("dailySummaries").let { it.put(it.getJSONObject(0)) } },
        "duplicate counter summary" to { root -> root.getJSONArray("chargeDailySummaries").let { it.put(it.getJSONObject(0)) } },
        "duplicate percentage raw" to { root -> root.getJSONArray("rawIntervals").let { it.put(it.getJSONObject(0)) } },
        "duplicate counter raw" to { root -> root.getJSONArray("chargeRawIntervals").let { it.put(it.getJSONObject(0)) } },
        "percentage overlap" to { root -> root.getJSONArray("rawIntervals").getJSONObject(1).let { it.put("startMs", it.getLong("startMs") - 1000) } },
        "counter overlap" to { root -> root.getJSONArray("chargeRawIntervals").getJSONObject(1).getJSONObject("start").let { it.put("timeMs", it.getLong("timeMs") - 1000); it.put("elapsedMs", it.getLong("elapsedMs") - 1000) } },
        "fractional integer" to { root -> root.getJSONArray("chargeRawIntervals").getJSONObject(0).getJSONObject("start").put("percent", 50.5) },
        "negative percentage variance" to { root -> root.getJSONArray("dailySummaries").getJSONObject(0).put("squaredSeconds", -1) },
        "negative counter variance" to { root -> root.getJSONArray("chargeDailySummaries").getJSONObject(0).put("squaredRateSeconds", -1) },
        "negative counter calibration" to { root -> root.getJSONArray("chargeDailySummaries").getJSONObject(0).put("capacityUahSeconds", -1) },
        "impossible calibration variance" to { root -> root.getJSONArray("chargeDailySummaries").getJSONObject(0).put("squaredCapacitySeconds", 0) },
        "missing percentage summaries" to { root -> root.put("dailySummaries", JSONArray()) },
        "missing counter summaries" to { root -> root.put("chargeDailySummaries", JSONArray()) },
        "unsupported counter algorithm" to { root -> root.put("chargeModel", "unknown") },
        "future export" to { root -> root.put("exportedAtMs", now + 30 * BatteryConfig.DAY_MS) },
    )
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun guide(data: Map<String, BatteryStore.Snapshot>) = buildString {
        appendLine("# Importable battery test data\n")
        appendLine("Generated ${Instant.ofEpochMilli(now)}. **Ten import files total: eight valid scenarios and two rejection examples.** All histories are synthetic. This replaces the previous twelve valid examples and three invalid files.\n")
        appendLine("## Use\n")
        appendLine("Export your own history first, pause learning, then select one numbered JSON file with Import data and confirm replacement. Each import replaces both histories. The current phone percentage determines times; 80% is only the export reference. Restore your own backup and resume learning afterwards. Files 09 and 10 must be rejected without replacing data.\n")
        appendLine("At 80% on the generation date, P = percentage, C = counter, B = blended/widget. Detailed seconds, scores, weights, coverage, days and counts at 0/15/16/25/50/80/100% are in expected-results.json (a report, not an import file).\n")
        appendLine("| File | Screen off P / C / B | Screen on P / C / B |\n| --- | --- | --- |")
        catalog.cases.forEach { case ->
            fun cell(mode: ScreenMode) = estimate(data.getValue(case.name), 80, mode).let { "${duration(it.percentage.seconds)} / ${duration(it.charge.time.seconds)} / ${duration(it.seconds)}" }
            appendLine("| [${case.name}.json](${case.name}.json) | ${cell(ScreenMode.OFF)} | ${cell(ScreenMode.ON)} |")
        }
        appendLine()
        catalog.cases.forEach { appendLine("- **${it.name}:** ${it.description}") }
        appendLine("- **09-invalid-version:** unsupported version 99.\n- **10-invalid-counter-reset:** an accepted counter interval increases charge.\n")
        appendLine("## Rigor and limits\n")
        appendLine("01 and 02 have independent closed-form oracles checked for every percentage 0–100. In 02, recent history is twice as influential as the preceding fortnight: P off/on = 400/180 seconds per percent; C off/on = 500/200. The blend must lie between the corresponding estimates. 08 reverses reliability preference between modes. 04 tests different learning thresholds and mode isolation.\n")
        appendLine("03 varies battery-band rates and contains every preclassified exclusion reason. Reimporting it with excluded rows removed must leave predictions and weights unchanged. Live event classification is covered by the model/collector tests; importing raw rows does not retrain summaries.\n")
        appendLine("06 combines retention boundaries. A raw row exactly seven days old survives at generation and expires one millisecond later; later imports can retain fewer rows. Summary age 55 survives, age 56 does not. 07 replaces mature history with empty histories after expiry. Both models return zero at/below 15%, even when empty. Fixtures age naturally; regenerate for reproducible later comparisons.\n")
        appendLine("Fifteen additional malformed variants are tested through the app without permanent files: duplicates/overlaps in both models, invalid numbers/variances/calibration, missing summaries, unknown counter model and future dates. Tests also cover cancellation, ignored cached predictions, export/re-import idempotence, developer counts and live widget updates at 15/50/80%. The system document picker itself is outside this automated run.\n")
        appendLine("## Regenerate and exercise\n")
        appendLine("ImportFixturesTest#importFixturesThroughApplication generates this catalog with the production serializer, validates it, imports all ten files in the app and writes application-test-results.md. Use an emulator with learning paused, then replace previous generated files with its import-fixtures folder.\n")
        appendLine("```powershell\n.\\gradlew.bat assembleDebug assembleDebugAndroidTest\nadb install -r app/build/outputs/apk/debug/app-debug.apk\nadb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk\nadb shell am instrument -w -e class 'com.keltau.batterytimewidget.ImportFixturesTest#importFixturesThroughApplication' com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner\nadb pull /sdcard/Android/data/com.keltau.batterytimewidget/files/import-fixtures/. test-data/\n```\n")
        appendLine("Select the intended emulator with adb -s SERIAL if necessary. If the Windows wrapper cannot find Java, invoke the installed JDK's java.exe with -jar gradle/wrapper/gradle-wrapper.jar and the same tasks. The catalog is ImportFixtureCatalog.kt; the app/storage/widget runner is ImportFixturesTest.kt.")
    }
}
