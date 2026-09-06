package com.keltau.batterytimewidget

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.action.ViewActions.repeatedlyUntil
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import org.hamcrest.Matchers.allOf
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class HybridDataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = System.currentTimeMillis()

    private fun fixture(): BatteryStore.Snapshot {
        val raw = ScreenMode.entries.flatMapIndexed { index, mode ->
            (0..2).map { i ->
                fun reading(step: Int) = BatteryReading(now - (6 - (index * 3 + step)) * 60_000,
                    (index * 3 + step) * 60_000L, 50, true, mode, false, chargeUah = 2_000_000L - step * 2000,
                    currentUa = -120_000, averageCurrentUa = -120_000, voltageMv = 3900, temperatureDeciC = 270)
                ChargeInterval(reading(i), reading(i + 1), null)
            }
        }
        val summaries = raw.map { it.summary() }.groupBy { Triple(it.day, it.screen, it.band) }.values.map { group -> group.reduce { a, b -> a + b } }
        return BatteryStore.Snapshot(emptyList(), emptyList(), summaries, raw)
    }

    private fun json(data: BatteryStore.Snapshot = fixture()): JSONObject = JSONObject(ByteArrayOutputStream().also {
        DataTransfer.write(it, data, 50, now)
    }.toString("UTF-8"))
    private fun read(json: JSONObject, at: Long = now) = DataTransfer.read(ByteArrayInputStream(json.toString().toByteArray()), at)

    @Test fun hybridRoundTripPreservesTelemetryAndRecomputesBlend() {
        val encoded = json()
        assertEquals(2, encoded.getInt("version"))
        encoded.getJSONObject("estimates").put("OFF", JSONObject().put("secondsToTarget", 1).put("chargeWeight", 0))
        val restored = read(encoded)
        assertEquals(fixture(), restored)
        val blend = HybridEstimator.estimate(50, ScreenMode.ON, restored.summaries, restored.chargeSummaries, now)
        assertNull(blend.percentage.seconds)
        assertNotNull(blend.charge.time.seconds)
        assertEquals(blend.charge.time.seconds, blend.seconds)
        assertEquals(1.0, blend.chargeWeight, 0.0)
    }

    @Test fun versionOneStillImportsAndContainsNoChargeHistory() {
        val encoded = json(BatteryStore.Snapshot(emptyList(), emptyList())).put("version", 1)
        encoded.remove("chargeModel"); encoded.remove("chargeDailySummaries"); encoded.remove("chargeRawIntervals")
        assertEquals(BatteryStore.Snapshot(emptyList(), emptyList()), read(encoded))
    }

    @Test fun chargeRetentionDoesNotDependOnRawRows() {
        val week = read(json(), now + 8 * BatteryConfig.DAY_MS)
        assertTrue(week.chargeRaw.isEmpty())
        assertEquals(fixture().chargeSummaries, week.chargeSummaries)
        val expired = read(json(), now + 57 * BatteryConfig.DAY_MS)
        assertTrue(expired.chargeRaw.isEmpty())
        assertTrue(expired.chargeSummaries.isEmpty())
    }

    @Test fun invalidCounterLearningStatisticsAndSchemaAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("chargeModel", "unknown") },
            { it.remove("chargeDailySummaries") },
            { it.getJSONArray("chargeRawIntervals").getJSONObject(0).getJSONObject("end").put("chargeUah", 2_100_000) },
            { it.getJSONArray("chargeRawIntervals").getJSONObject(0).getJSONObject("start").put("audioKnown", false) },
            { it.getJSONArray("chargeRawIntervals").getJSONObject(0).getJSONObject("start").put("percent", 50.5) },
            { it.getJSONArray("chargeDailySummaries").getJSONObject(0).put("chargeUah", 1) },
            { it.getJSONArray("chargeDailySummaries").getJSONObject(0).put("squaredRateSeconds", 0) },
            { it.getJSONArray("chargeDailySummaries").getJSONObject(0).put("capacityUahSeconds", -1) },
            { it.getJSONArray("chargeDailySummaries").getJSONObject(0).put("squaredCapacitySeconds", 0) },
            { val rows = it.getJSONArray("chargeDailySummaries"); rows.put(rows.getJSONObject(0)) },
            { val rows = it.getJSONArray("chargeRawIntervals"); rows.put(rows.getJSONObject(0)) },
            { val first = it.getJSONArray("chargeRawIntervals").getJSONObject(1).getJSONObject("start"); first.put("timeMs", first.getLong("timeMs") - 1000); first.put("elapsedMs", first.getLong("elapsedMs") - 1000) },
        )
        mutations.forEachIndexed { index, mutation ->
            val encoded = json()
            mutation(encoded)
            assertThrows("Mutation $index", Exception::class.java) { read(encoded) }
        }
    }

    @Test fun chargeAggregationReplacementAndRollbackAreAtomic() {
        val name = "hybrid-test.db"
        context.deleteDatabase(name)
        BatteryStore(context, name).use { store ->
            fixture().chargeRaw.forEach { store.record(null, it, now) }
            assertEquals(fixture(), store.snapshot(now))
            store.replace(fixture(), now)
            store.replace(fixture(), now)
            assertEquals(fixture(), store.snapshot(now))
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                store.replace(fixture().copy(chargeSummaries = fixture().chargeSummaries + fixture().chargeSummaries.first()), now)
            }
            assertEquals(fixture(), store.snapshot(now))
            assertTrue(store.snapshot(now + 8 * BatteryConfig.DAY_MS).chargeRaw.isEmpty())
            assertFalse(store.chargeSummaries(now + 8 * BatteryConfig.DAY_MS).isEmpty())
            assertTrue(store.snapshot(now + 57 * BatteryConfig.DAY_MS).chargeSummaries.isEmpty())
        }
        context.deleteDatabase(name)
    }

    @Test fun databaseUpgradePreservesLegacyLearning() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE daily (day INTEGER NOT NULL, screen TEXT NOT NULL, band INTEGER NOT NULL, count INTEGER NOT NULL, seconds REAL NOT NULL, squared_seconds REAL NOT NULL, PRIMARY KEY(day, screen, band))")
            db.execSQL("CREATE TABLE raw (id INTEGER PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, start_percent INTEGER NOT NULL, end_percent INTEGER NOT NULL, seconds REAL NOT NULL, screen TEXT NOT NULL, exclusion TEXT)")
            db.execSQL("INSERT INTO daily VALUES (?, 'ON', 10, 10, 6000, 3600000)", arrayOf<Any>(now / BatteryConfig.DAY_MS))
            db.version = 1
        }
        BatteryStore(context, name).use { store ->
            val snapshot = store.snapshot(now)
            assertEquals(10, snapshot.summaries.single().count)
            assertTrue(snapshot.chargeSummaries.isEmpty())
            assertEquals(2, store.readableDatabase.version)
            fixture().chargeRaw.forEach { store.record(null, it, now) }
            assertEquals(10, store.snapshot(now).summaries.single().count)
            assertEquals(fixture().chargeSummaries, store.chargeSummaries(now))
        }
        context.deleteDatabase(name)
    }

    @Test fun overviewDisplaysBothModelsAndCombinedTimeDuringEarlyLearning() {
        fun <T> store(block: () -> T): T = BatteryStore.executor.submit(Callable(block)).get(15, TimeUnit.SECONDS)
        val original = store { BatteryStore.get(context).snapshot(now) }
        check(!BatteryMonitorService.isEnabled(context))
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        shell("dumpsys battery unplug"); shell("dumpsys battery set level 50")
        val output = File(context.getExternalFilesDir(null), "hybrid-verification").apply { mkdirs() }
        try {
            store { BatteryStore.get(context).replace(fixture(), now) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var loaded = false
                while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { loaded = ViewModelProvider(it)[MainActivity.Model::class.java].snapshot.value != null }
                    if (!loaded) SystemClock.sleep(50)
                }
                assertTrue(loaded)
                scenario.onActivity { activity ->
                    val expected = HybridEstimator.estimate(50, ScreenMode.ON, emptyList(), fixture().chargeSummaries, System.currentTimeMillis())
                    assertEquals(BatteryWidgetProvider.duration(context, expected.seconds), activity.findViewById<TextView>(R.id.estimate_on).text.toString())
                    assertTrue(activity.findViewById<TextView>(R.id.models_on).text.contains("Percentage: Learning"))
                    assertTrue(activity.findViewById<TextView>(R.id.models_on).text.contains("100% charge"))
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(output, "overview-charge-learning.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val blended = fixture().copy(summaries = ScreenMode.entries.map { mode ->
                    val rate = if (mode == ScreenMode.OFF) 600.0 else 120.0
                    DailySummary(now / BatteryConfig.DAY_MS, mode, 10, 10, rate * 10, rate * rate * 10)
                })
                store { BatteryStore.get(context).replace(blended, now) }
                scenario.onActivity { ViewModelProvider(it)[MainActivity.Model::class.java].refresh() }
                val blendDeadline = SystemClock.elapsedRealtime() + 15_000
                var blendedLoaded = false
                while (!blendedLoaded && SystemClock.elapsedRealtime() < blendDeadline) {
                    scenario.onActivity { blendedLoaded = ViewModelProvider(it)[MainActivity.Model::class.java].snapshot.value?.summaries == blended.summaries }
                    if (!blendedLoaded) SystemClock.sleep(50)
                }
                assertTrue(blendedLoaded)
                scenario.onActivity { activity ->
                    val estimate = HybridEstimator.estimate(50, ScreenMode.ON, blended.summaries, blended.chargeSummaries, System.currentTimeMillis())
                    val models = activity.findViewById<TextView>(R.id.models_on).text.toString()
                    assertTrue(models.contains("Percentage: " + BatteryWidgetProvider.duration(context, estimate.percentage.seconds)))
                    assertTrue(models.contains("Charge counter: " + BatteryWidgetProvider.duration(context, estimate.charge.time.seconds)))
                    assertEquals(BatteryWidgetProvider.duration(context, estimate.seconds), activity.findViewById<TextView>(R.id.estimate_on).text.toString())
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(output, "overview-blended.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                onView(withId(R.id.main_root)).perform(repeatedlyUntil(swipeUp(), hasDescendant(allOf(withId(R.id.open_dev), isCompletelyDisplayed())), 5))
                onView(withId(R.id.open_dev)).perform(click())
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(output, "developer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally {
            shell("dumpsys battery reset")
            store { BatteryStore.get(context).replace(original, System.currentTimeMillis()); BatteryWidgetProvider.updateAll(context) }
        }
    }
}
