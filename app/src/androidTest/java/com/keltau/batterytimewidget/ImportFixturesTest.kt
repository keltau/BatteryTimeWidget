package com.keltau.batterytimewidget

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class ImportFixturesTest {
    private val now = System.currentTimeMillis()
    private val dayMs = BatteryConfig.DAY_MS

    private data class Scenario(
        val name: String,
        val description: String,
        val exportedAt: Long,
        val data: BatteryStore.Snapshot,
    )

    @Test fun importFixturesThroughApplication() {
        generateAndValidateImportFixtures()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "import-fixtures")
        val report = JSONObject(File(output, "expected-results.json").readText())
        val cases = report.getJSONArray("validScenarios")
        val invalid = report.getJSONArray("invalidScenarios")
        check(!BatteryMonitorService.running && !BatteryMonitorService.isEnabled(context)) { "Pause learning before running fixture imports." }
        val original = onStore { BatteryStore.get(context).snapshot(System.currentTimeMillis()) }
        val checks = mutableListOf<String>()
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("dumpsys battery unplug")
        shell("dumpsys battery set level 80")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainActivity.Model
                scenario.onActivity { model = ViewModelProvider(it)[MainActivity.Model::class.java] }
                fun idle() = awaitCondition {
                    var idle = false
                    instrumentation.runOnMainSync { idle = model.busy.value == false }
                    idle
                }
                fun prepare(file: File) {
                    idle()
                    scenario.onActivity { model.prepareImport(Uri.fromFile(file)) }
                    idle()
                    scenario.onActivity { assertNotNull(model.pendingImport.value) }
                }
                fun verifyStored(file: File) {
                    onStore {
                        val time = System.currentTimeMillis()
                        val expected = file.inputStream().use { DataTransfer.read(it, time) }
                        assertEquals(file.name, expected, BatteryStore.get(context).snapshot(time))
                    }
                }
                idle()
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    val file = File(output, item.getString("file"))
                    prepare(file)
                    onView(withText(R.string.replace_data)).perform(click())
                    idle()
                    verifyStored(file)
                    val expected = item.getJSONArray("expectedAtGeneration").getJSONObject(5)
                    onView(withId(R.id.estimate_off)).check(matches(withText(expected.getJSONObject("OFF").getString("display"))))
                    onView(withId(R.id.estimate_on)).check(matches(withText(expected.getJSONObject("ON").getString("display"))))
                    checks += "PASS ${file.name}: confirmation, stored dataset and both overview estimates at 80%."
                    val exported = File(context.cacheDir, "fixture-roundtrip.json")
                    scenario.onActivity { model.export(Uri.fromFile(exported)) }
                    idle()
                    verifyStored(exported)
                    checks += "PASS ${file.name}: app export round trip."
                    if (item.getString("file") == "02-constant-rates.json") {
                        prepare(file)
                        onView(withText(R.string.replace_data)).perform(click())
                        idle()
                        verifyStored(file)
                        checks += "PASS repeated constant-rate import: no duplicate samples."
                    }
                }
                val reference = File(output, "02-constant-rates.json")
                prepare(reference)
                onView(withText(R.string.replace_data)).perform(click())
                idle()
                prepare(File(output, "10-empty-history.json"))
                onView(withText(R.string.cancel)).perform(click())
                verifyStored(reference)
                checks += "PASS cancelled replacement: existing history preserved."
                for (index in 0 until invalid.length()) {
                    val name = invalid.getJSONObject(index).getString("file")
                    scenario.onActivity { model.prepareImport(Uri.fromFile(File(output, name))) }
                    idle()
                    scenario.onActivity { assertNull(model.pendingImport.value) }
                    verifyStored(reference)
                    checks += "PASS $name: application rejects file and preserves existing history."
                }
                shell("dumpsys battery set level 15")
                scenario.onActivity { model.refresh() }
                idle()
                onView(withId(R.id.estimate_off)).check(matches(withText(R.string.zero_minutes)))
                onView(withId(R.id.estimate_on)).check(matches(withText(R.string.zero_minutes)))
                checks += "PASS actual device level 15%: both overview estimates are zero."
            }
        } finally {
            shell("dumpsys battery reset")
            onStore {
                BatteryStore.get(context).replace(original, System.currentTimeMillis())
                BatteryWidgetProvider.updateAll(context)
            }
        }
        File(output, "application-test-results.md").writeText(buildString {
            appendLine("# Application fixture checks")
            appendLine()
            appendLine("Run on ${Instant.now()} using the Android emulator, actual import confirmation dialog, MainActivity.Model, SQLite store and overview views.")
            appendLine()
            checks.forEach { appendLine("- $it") }
            appendLine()
            appendLine("All ${checks.size} checks passed. The original database and real battery reporting were restored after the run. File access in this automated test uses app-owned file URIs; the system document picker is checked separately.")
        })
    }

    private fun <T> onStore(block: () -> T): T = BatteryStore.executor.submit(Callable(block)).get(15, TimeUnit.SECONDS)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the application import/export operation", condition())
    }

    @Test fun generateAndValidateImportFixtures() {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "import-fixtures")
        check(output.isDirectory || output.mkdirs())
        val linear = cycles(now, 14, { 600.0 }, { 120.0 })
        val cases = listOf(
            Scenario("01-realistic-six-weeks", "Start here: six weeks of varying use, different discharge speeds across battery levels, and discarded mixed/audio intervals.", now, nonlinear()),
            Scenario("02-constant-rates", "Analytic reference: screen off is 600 seconds per percent; screen on is 120 seconds per percent. Both modes have full coverage.", now, linear),
            Scenario("03-recent-usage-change", "The latest 14 days shorten the time per percent from 600 to 300 seconds off and 300 to 120 seconds on, representing faster battery drain. The older 14-day block has half the recent block's weight. Expected weighted rates: 400 seconds off, 180 seconds on.", now,
                cycles(now, 28, { if (it <= 14) 300.0 else 600.0 }, { if (it <= 14) 120.0 else 300.0 })),
            Scenario("04-five-samples-per-mode", "Only five accepted drops per mode. Both values stay Learning above 15%, regardless of the observed rates.", now, sparse()),
            Scenario("05-screen-on-only", "Seven days of screen-on history. Screen on has a numeric estimate; screen off stays Learning above 15%.", now,
                cycles(now, 7, { 600.0 }, { 120.0 }, includeOff = false)),
            Scenario("06-excluded-audio-and-other-intervals", "Same summaries as 02, with extra excluded screen-off audio, charging, mixed-state, unknown-audio, level-jump, warmup, duration and clock-change intervals. All estimates must match 02 exactly.", now,
                linear.copy(raw = (linear.raw + excludedIntervals()).sortedBy { it.endMs })),
            Scenario("07-raw-expired-summaries-retained", "A backup exported ten days ago. Its raw intervals disappear on import; all 14 summary days remain, with the same constant-rate estimates as 02.", now - 10 * dayMs,
                cycles(now - 10 * dayMs, 14, { 600.0 }, { 120.0 })),
            Scenario("08-summary-retention-boundary", "A backup exported 52 days ago. Only summary ages 53, 54 and 55 days survive: 252 samples per mode across three days. Raw history is empty; estimates are provisional with zero well-sampled band coverage.", now - 52 * dayMs,
                cycles(now - 52 * dayMs, 7, { 600.0 }, { 120.0 })),
            Scenario("09-entire-backup-expired", "A backup exported 60 days ago. All raw intervals and summaries expire on import. Both modes return to Learning above 15%.", now - 60 * dayMs,
                cycles(now - 60 * dayMs, 14, { 600.0 }, { 120.0 })),
            Scenario("10-empty-history", "An empty valid backup. Use it to check the initial Learning state and empty developer page.", now,
                BatteryStore.Snapshot(emptyList(), emptyList())),
        )
        val restored = linkedMapOf<String, BatteryStore.Snapshot>()
        val expectations = JSONObject().put("generatedAtMs", now).put("generatedAtUtc", Instant.ofEpochMilli(now).toString())
        val results = JSONArray()
        cases.forEach { scenario ->
            val bytes = ByteArrayOutputStream().also {
                DataTransfer.write(it, scenario.data, 80, scenario.exportedAt)
            }.toByteArray()
            val json = JSONObject(bytes.toString(Charsets.UTF_8)).put("syntheticTestData", true).put("testScenario", scenario.description)
            val file = File(output, "${scenario.name}.json")
            file.writeText(json.toString(2), Charsets.UTF_8)
            val imported = file.inputStream().use { DataTransfer.read(it, now) }
            restored[scenario.name] = imported
            results.put(JSONObject().apply {
                put("file", file.name)
                put("description", scenario.description)
                put("rawIntervalsBeforeImport", scenario.data.raw.size)
                put("rawIntervalsAfterImport", imported.raw.size)
                put("summaryRowsBeforeImport", scenario.data.summaries.size)
                put("summaryRowsAfterImport", imported.summaries.size)
                put("expectedAtGeneration", JSONArray().apply {
                    listOf(0, 15, 16, 25, 50, 80, 100).forEach { percent ->
                        put(JSONObject().put("batteryPercent", percent).apply {
                            ScreenMode.entries.forEach { mode ->
                                val estimate = BatteryEstimator.estimate(percent, mode, imported.summaries, now)
                                put(mode.name, JSONObject()
                                    .put("secondsToTarget", estimate.seconds ?: JSONObject.NULL)
                                    .put("display", display(estimate.seconds))
                                    .put("samples", estimate.sampleCount)
                                    .put("days", estimate.days)
                                    .put("coverage", estimate.coverage)
                                    .put("established", estimate.established))
                            }
                        })
                    }
                })
            })
        }
        validateExpectedBehavior(restored)
        val invalid = invalidFiles(output, File(output, "02-constant-rates.json"))
        expectations.put("validScenarios", results).put("invalidScenarios", invalid)
        File(output, "expected-results.json").writeText(expectations.toString(2), Charsets.UTF_8)
        File(output, "README.md").writeText(guide(cases, restored, invalid), Charsets.UTF_8)
        println("Generated and verified ${cases.size} importable fixtures and ${invalid.length()} rejected fixtures in ${output.absolutePath}")
    }

    private fun snapshot(rows: List<DischargeInterval>, exportedAt: Long): BatteryStore.Snapshot {
        val summaries = rows.filter { it.exclusion == null }.groupBy {
            Triple(Math.floorDiv(it.endMs, dayMs), it.screen, it.band)
        }.map { (key, group) ->
            DailySummary(key.first, key.second, key.third, group.size, group.sumOf { it.seconds }, group.sumOf { it.seconds * it.seconds })
        }.sortedWith(compareBy({ it.day }, { it.screen.name }, { it.band }))
        return BatteryStore.Snapshot(summaries, rows.filter { it.endMs >= exportedAt - 7 * dayMs }.sortedBy { it.endMs })
    }

    private fun cycle(rows: MutableList<DischargeInterval>, start: Long, mode: ScreenMode, seconds: Double, from: Int = 100, to: Int = 15): Long {
        var cursor = start
        for (percent in from downTo to + 1) {
            val end = cursor + (seconds * 1000).roundToLong()
            rows += DischargeInterval(cursor, end, percent, percent - 1, seconds, mode, if (percent == from) Exclusion.WARMUP else null)
            cursor = end
        }
        return cursor
    }

    private fun cycles(exportedAt: Long, days: Int, off: (Int) -> Double, on: (Int) -> Double, includeOff: Boolean = true): BatteryStore.Snapshot {
        val rows = mutableListOf<DischargeInterval>()
        val today = Math.floorDiv(exportedAt, dayMs)
        for (age in days downTo 1) {
            val startOfDay = (today - age) * dayMs
            var cursor = startOfDay + 3_600_000
            if (includeOff) {
                cursor = cycle(rows, cursor, ScreenMode.OFF, off(age))
                rows += DischargeInterval(cursor, cursor + 3_600_000, 15, 100, 3600.0, ScreenMode.OFF, Exclusion.CHARGING)
                cursor += 3_600_000
            }
            cursor = cycle(rows, cursor, ScreenMode.ON, on(age))
            check(cursor < startOfDay + dayMs)
        }
        return snapshot(rows, exportedAt)
    }

    private fun nonlinear(): BatteryStore.Snapshot {
        val off = doubleArrayOf(600.0, 600.0, 600.0, 600.0, 720.0, 900.0, 1080.0, 1260.0, 1440.0, 1560.0, 1680.0, 1680.0, 1560.0, 1440.0, 1320.0, 1200.0, 1080.0, 960.0, 840.0, 720.0)
        val on = doubleArrayOf(120.0, 120.0, 120.0, 120.0, 135.0, 150.0, 165.0, 180.0, 195.0, 210.0, 210.0, 210.0, 195.0, 180.0, 165.0, 150.0, 145.0, 140.0, 135.0, 130.0)
        val rows = mutableListOf<DischargeInterval>()
        val today = Math.floorDiv(now, dayMs)
        for (age in 42 downTo 1) {
            val dayStart = (today - age) * dayMs
            var cursor = dayStart + 3_600_000
            for (percent in 100 downTo 16) {
                val band = (percent - 1) / 5
                val mode = if ((age + band) % 2 == 0) ScreenMode.OFF else ScreenMode.ON
                val rate = if (mode == ScreenMode.OFF) off[band] else on[band]
                val seconds = (rate * (0.9 + (age % 5) * 0.05) * (0.98 + (percent % 3) * 0.02)).roundToLong().toDouble()
                val exclusion = when {
                    mode == ScreenMode.OFF && age % 6 == 0 && band == 10 -> Exclusion.SCREEN_OFF_AUDIO
                    percent == 100 -> Exclusion.WARMUP
                    percent % 5 == 0 -> Exclusion.SCREEN_CHANGE
                    else -> null
                }
                val end = cursor + seconds.toLong() * 1000
                rows += DischargeInterval(cursor, end, percent, percent - 1, seconds, mode, exclusion)
                cursor = end
            }
            rows += DischargeInterval(cursor, cursor + 3_600_000, 15, 100, 3600.0, ScreenMode.OFF, Exclusion.CHARGING)
            check(cursor + 3_600_000 < dayStart + dayMs)
        }
        return snapshot(rows, now)
    }

    private fun sparse(): BatteryStore.Snapshot {
        val rows = mutableListOf<DischargeInterval>()
        val start = (Math.floorDiv(now, dayMs) - 1) * dayMs + 43_200_000
        val end = cycle(rows, start, ScreenMode.OFF, 600.0, 91, 85)
        rows += DischargeInterval(end, end + 900_000, 85, 91, 900.0, ScreenMode.OFF, Exclusion.CHARGING)
        cycle(rows, end + 900_000, ScreenMode.ON, 120.0, 91, 85)
        return snapshot(rows, now)
    }

    private fun excludedIntervals(): List<DischargeInterval> {
        val rows = mutableListOf<DischargeInterval>()
        val today = Math.floorDiv(now, dayMs)
        for (age in 7 downTo 1) {
            var cursor = (today - age) * dayMs + 21 * 3_600_000
            rows += DischargeInterval(cursor, cursor + 1_800_000, 15, 90, 1800.0, ScreenMode.OFF, Exclusion.CHARGING)
            cursor += 1_800_000
            var level = 90
            val reasons = List(15) { Exclusion.SCREEN_OFF_AUDIO } + listOf(
                Exclusion.SCREEN_CHANGE, Exclusion.AUDIO_UNKNOWN, Exclusion.LEVEL_JUMP,
                Exclusion.WARMUP, Exclusion.DURATION, Exclusion.CLOCK_CHANGE,
            )
            reasons.forEach { reason ->
                val seconds = when (reason) {
                    Exclusion.SCREEN_OFF_AUDIO -> 30.0
                    Exclusion.DURATION -> 5.0
                    else -> 120.0
                }
                val drop = if (reason == Exclusion.LEVEL_JUMP) 3 else 1
                val end = cursor + seconds.toLong() * 1000
                rows += DischargeInterval(cursor, end, level, level - drop, seconds, ScreenMode.OFF, reason)
                cursor = end
                level -= drop
            }
        }
        return rows.filter { it.endMs >= now - 7 * dayMs }
    }

    private fun validateExpectedBehavior(data: Map<String, BatteryStore.Snapshot>) {
        fun estimate(name: String, mode: ScreenMode, percent: Int = 80) = BatteryEstimator.estimate(percent, mode, data.getValue(name).summaries, now)
        listOf(16, 25, 50, 80, 100).forEach { percent ->
            assertEquals((percent - 15) * 600L, estimate("02-constant-rates", ScreenMode.OFF, percent).seconds)
            assertEquals((percent - 15) * 120L, estimate("02-constant-rates", ScreenMode.ON, percent).seconds)
            assertEquals((percent - 15) * 400L, estimate("03-recent-usage-change", ScreenMode.OFF, percent).seconds)
            assertEquals((percent - 15) * 180L, estimate("03-recent-usage-change", ScreenMode.ON, percent).seconds)
        }
        ScreenMode.entries.forEach { mode ->
            assertTrue(estimate("01-realistic-six-weeks", mode).established)
            assertEquals(1.0, estimate("01-realistic-six-weeks", mode).coverage, 0.0)
            assertNull(estimate("04-five-samples-per-mode", mode).seconds)
            assertEquals(5, estimate("04-five-samples-per-mode", mode).sampleCount)
            for (percent in 0..100) {
                assertEquals(estimate("02-constant-rates", mode, percent), estimate("06-excluded-audio-and-other-intervals", mode, percent))
                assertEquals(estimate("02-constant-rates", mode, percent).seconds, estimate("07-raw-expired-summaries-retained", mode, percent).seconds)
            }
            assertEquals(252, estimate("08-summary-retention-boundary", mode).sampleCount)
            assertEquals(3, estimate("08-summary-retention-boundary", mode).days)
            assertFalse(estimate("08-summary-retention-boundary", mode).established)
            assertEquals(0.0, estimate("08-summary-retention-boundary", mode).coverage, 0.0)
            assertNull(estimate("09-entire-backup-expired", mode).seconds)
            assertNull(estimate("10-empty-history", mode).seconds)
            data.keys.forEach { name ->
                assertEquals(0L, estimate(name, mode, 15).seconds)
                assertEquals(0L, estimate(name, mode, 0).seconds)
            }
        }
        assertNull(estimate("05-screen-on-only", ScreenMode.OFF).seconds)
        assertEquals(7800L, estimate("05-screen-on-only", ScreenMode.ON).seconds)
        assertTrue(data.getValue("07-raw-expired-summaries-retained").raw.isEmpty())
        assertTrue(data.getValue("08-summary-retention-boundary").raw.isEmpty())
        assertEquals(BatteryStore.Snapshot(emptyList(), emptyList()), data.getValue("09-entire-backup-expired"))
    }

    private fun invalidFiles(output: File, reference: File): JSONArray {
        val cases = linkedMapOf(
            "invalid-unsupported-version.json" to ("Unsupported backup format or version." to JSONObject(reference.readText()).put("version", 99)),
            "invalid-duplicate-summary.json" to ("Duplicate daily summary." to JSONObject(reference.readText()).apply {
                val rows = getJSONArray("dailySummaries")
                rows.put(JSONObject(rows.getJSONObject(0).toString()))
            }),
            "invalid-overlapping-intervals.json" to ("Overlapping raw intervals." to JSONObject(reference.readText()).apply {
                val rows = getJSONArray("rawIntervals")
                val row = JSONObject(rows.getJSONObject(0).toString())
                row.put("startMs", row.getLong("startMs") + 1000)
                row.put("endMs", row.getLong("endMs") + 1000)
                row.put("exclusion", Exclusion.SCREEN_OFF_AUDIO.name)
                rows.put(row)
            }),
        )
        return JSONArray().apply {
            cases.forEach { (name, pair) ->
                val file = File(output, name)
                file.writeText(pair.second.toString(2), Charsets.UTF_8)
                val failure = assertThrows(IllegalArgumentException::class.java) { file.inputStream().use { DataTransfer.read(it, now) } }
                assertEquals(pair.first, failure.message)
                put(JSONObject().put("file", name).put("expectedError", pair.first))
            }
        }
    }

    private fun display(seconds: Long?): String {
        if (seconds == null) return "Learning"
        if (seconds == 0L) return "0 min"
        val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
        return when {
            minutes >= 1440 -> "${minutes / 1440} d ${minutes / 60 % 24} h"
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
            else -> "$minutes min"
        }
    }

    private fun guide(cases: List<Scenario>, data: Map<String, BatteryStore.Snapshot>, invalid: JSONArray): String = buildString {
        appendLine("# Importable battery test data")
        appendLine()
        appendLine("Synthetic fixtures generated and verified with the app's DataTransfer and BatteryEstimator on ${Instant.ofEpochMilli(now)}. None of these files contains real usage data.")
        appendLine()
        appendLine("## Use")
        appendLine()
        appendLine("1. Export your current history if you want to keep it, then pause learning so incoming samples do not change the comparison.")
        appendLine("2. Copy the numbered JSON files to your phone's Downloads folder. In Battery Time, choose Import data, select one file, and confirm replacement.")
        appendLine("3. Begin with 01-realistic-six-weeks.json. Check the overview, widget, and Developer insights. Resize the widget to check compact duration labels.")
        appendLine("4. Each import replaces the previous dataset. Re-importing the same file must leave sample counts and estimates unchanged.")
        appendLine("5. Restore your own export and resume learning when finished.")
        appendLine()
        appendLine("The phone's current battery level determines the displayed times. Import does not change that level. The JSON batteryPercent is 80 only to provide a reference snapshot; its cached estimates are not imported as live predictions. While plugged in, the overview describes use after unplugging and the widget may show that charging status instead of its evidence label.")
        appendLine()
        appendLine("## Scenarios")
        appendLine()
        appendLine("Times below assume the phone is at 80% on the generation date. Rounded display values are followed by exact seconds in expected-results.json.")
        appendLine()
        appendLine("| Import file | Screen off | Screen on | Samples off/on after import | Raw intervals after import |")
        appendLine("| --- | --- | --- | --- | --- |")
        cases.forEach { scenario ->
            val restored = data.getValue(scenario.name)
            val off = BatteryEstimator.estimate(80, ScreenMode.OFF, restored.summaries, now)
            val on = BatteryEstimator.estimate(80, ScreenMode.ON, restored.summaries, now)
            appendLine("| [${scenario.name}.json](${scenario.name}.json) | ${display(off.seconds)} | ${display(on.seconds)} | ${off.sampleCount} / ${on.sampleCount} | ${restored.raw.size} |")
        }
        appendLine()
        cases.forEach { appendLine("- **${it.name}:** ${it.description}") }
        appendLine()
        appendLine("## Known numerical results")
        appendLine()
        appendLine("For 02, 06 and 07, at percentage P above 15, screen-off time is (P - 15) × 600 seconds and screen-on time is (P - 15) × 120 seconds. For 03, those rates are 400 and 180 seconds. These formulas are checked against the actual estimator during generation.")
        appendLine()
        appendLine("| Battery | Constant off | Constant on | Recent usage change off | Recent usage change on |")
        appendLine("| --- | --- | --- | --- | --- |")
        listOf(15, 16, 25, 50, 80, 100).forEach { percent ->
            val n = (percent - 15).toLong()
            appendLine("| $percent% | ${display(n * 600)} | ${display(n * 120)} | ${display(n * 400)} | ${display(n * 180)} |")
        }
        appendLine()
        appendLine("At or below 15%, every scenario must show zero for both modes, including empty history. File 01 has intentionally uneven band rates: look at Developer insights for faster discharge near 15% and slower discharge near 50–60%. Expected values, coverage, days and counts at 0%, 15%, 16%, 25%, 50%, 80% and 100% are in expected-results.json; that file is a test report, not an import file.")
        appendLine()
        appendLine("## Exclusion and retention checks")
        appendLine()
        appendLine("File 06 adds 30-second screen-off audio drops and other rejected intervals to 02 without altering its summaries. Developer insights should show the extra rejection reasons, while all estimates and accepted-sample counts remain identical. Raw intervals are already classified in this backup format: importing them does not replay audio events or retrain summaries. Use the existing BatteryModelTest and collectorObservesScreenAndPlaybackEvents test to check live event classification and playback detection.")
        appendLine()
        appendLine("Files 07–09 deliberately have older export dates. The reader first validates each historical backup, then applies retention relative to the import date. File 07 retains summaries but no raw rows. File 08 keeps only three summary days on the generation date; one more expires each UTC day, so this boundary case needs regeneration if tested later. File 09 imports as empty history.")
        appendLine()
        appendLine("All fixtures age naturally. Coverage, counts and predictions can change as weights decay or rows expire. Regenerate for reproducible comparisons on a later date; do not only change exportedAtMs, because row dates and retention must remain consistent.")
        appendLine()
        appendLine("## Files that must be rejected")
        appendLine()
        appendLine("Import each invalid-* file while a valid dataset is loaded. The app must show the error below and preserve the current history.")
        appendLine()
        for (index in 0 until invalid.length()) {
            val item = invalid.getJSONObject(index)
            appendLine("- **${item.getString("file")}:** ${item.getString("expectedError")}")
        }
        appendLine()
        appendLine("## Regenerate")
        appendLine()
        appendLine("The generator is app/src/androidTest/java/com/keltau/batterytimewidget/ImportFixturesTest.kt. It does not modify the app database. With an emulator or development phone connected, run these commands from the project root; select the intended device with adb -s SERIAL if more than one is connected:")
        appendLine()
        appendLine("```powershell")
        appendLine(".\\gradlew.bat assembleDebug assembleDebugAndroidTest")
        appendLine("adb install -r app/build/outputs/apk/debug/app-debug.apk")
        appendLine("adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        appendLine("adb shell am instrument -w -e class 'com.keltau.batterytimewidget.ImportFixturesTest#generateAndValidateImportFixtures' com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner")
        appendLine("adb pull /sdcard/Android/data/com.keltau.batterytimewidget/files/import-fixtures/. test-data/")
        appendLine("```")
        appendLine()
        appendLine("The generator checks exact constant/recency formulas, audio-pair equality at every battery percentage, learning thresholds, retention boundaries and all three rejection errors. Values in the JSON estimates sections come directly from the production estimator.")
        appendLine()
        appendLine("To repeat the application checks on an emulator, run the same instrumentation command with #importFixturesThroughApplication instead. That test regenerates the fixtures, exercises the import confirmation dialog and overview, verifies persisted data and export round trips, and restores the original database and battery reporting afterward. Pause learning first. Its report is application-test-results.md in the same output directory. Use the SDK's platform-tools/adb executable if adb is not on your PATH.")
    }
}
