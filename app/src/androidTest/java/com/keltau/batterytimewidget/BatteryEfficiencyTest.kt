package com.keltau.batterytimewidget

import android.database.DatabaseUtils
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class BatteryEfficiencyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    private fun percentage(end: Long) = DischargeInterval(end - 60_000, end, 51, 50, 60.0, ScreenMode.ON, null)

    private fun charge(end: Long): ChargeInterval {
        val start = BatteryReading(end - 60_000, end - 60_000, 50, true, ScreenMode.ON, false, chargeUah = 2_000_000)
        return ChargeInterval(start, start.copy(timeMs = end, elapsedMs = end, chargeUah = 1_999_000), null)
    }

    @Test fun overviewSkipsRawDecodingAndPreservesExactRetentionBoundaries() = withStore { store ->
        val end = now - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS
        store.record(percentage(end), charge(end), now)
        val full = store.snapshot(now)
        assertEquals(1, full.raw.size)
        assertEquals(1, full.chargeRaw.size)
        val overview = full.copy(raw = emptyList(), chargeRaw = emptyList())
        assertEquals(overview, store.snapshot(now, includeRaw = false))

        // A summaries-only read must not even decode the raw JSON column.
        store.writableDatabase.execSQL("UPDATE charge_raw SET data = 'not JSON'")
        assertEquals(overview, store.snapshot(now, includeRaw = false))
        assertEquals(overview, store.snapshot(now + 1, includeRaw = false))
        assertEquals(0L, DatabaseUtils.queryNumEntries(store.readableDatabase, "raw"))
        assertEquals(0L, DatabaseUtils.queryNumEntries(store.readableDatabase, "charge_raw"))
        assertEquals(overview, store.snapshot(now + 1))
        val expired = store.snapshot(now + BatteryConfig.HISTORY_DAYS * BatteryConfig.DAY_MS, includeRaw = false)
        assertTrue(expired.summaries.isEmpty())
        assertTrue(expired.chargeSummaries.isEmpty())
    }

    @Test fun rowCapsKeepNewestRowsThroughReplacementAndOutOfOrderInserts() = withStore { store ->
        val raw = (0 until BatteryConfig.MAX_RAW_ROWS + 2).map { percentage(now - 30_000 + it) }
        val chargeRaw = (0 until BatteryConfig.MAX_CHARGE_RAW_ROWS + 2).map { charge(now - 30_000 + it) }
        store.replace(BatteryStore.Snapshot(emptyList(), raw, emptyList(), chargeRaw), now)
        fun oldest(table: String): Long = DatabaseUtils.longForQuery(store.readableDatabase,
            "SELECT MIN(end_ms) FROM $table", null)
        fun assertCaps() {
            assertEquals(BatteryConfig.MAX_RAW_ROWS.toLong(), DatabaseUtils.queryNumEntries(store.readableDatabase, "raw"))
            assertEquals(BatteryConfig.MAX_CHARGE_RAW_ROWS.toLong(), DatabaseUtils.queryNumEntries(store.readableDatabase, "charge_raw"))
        }
        assertCaps()
        assertEquals(raw[2].endMs, oldest("raw"))
        assertEquals(chargeRaw[2].end.timeMs, oldest("charge_raw"))

        store.record(percentage(now), null, now)
        assertCaps()
        assertEquals(raw[3].endMs, oldest("raw"))
        assertEquals(chargeRaw[2].end.timeMs, oldest("charge_raw"))
        store.record(null, charge(now), now)
        assertCaps()
        assertEquals(chargeRaw[3].end.timeMs, oldest("charge_raw"))

        // Old inserts must be discarded instead of displacing newer retained history.
        store.record(percentage(now - 60_000), charge(now - 60_000), now)
        store.record(null, null, now)
        store.prune(now)
        assertCaps()
        assertEquals(raw[3].endMs, oldest("raw"))
        assertEquals(chargeRaw[3].end.timeMs, oldest("charge_raw"))
    }

    @Test fun historyChecksIncludeSummaryOnlyAndDiagnosticOnlyStorage() = withStore { store ->
        val empty = BatteryStore.Snapshot(emptyList(), emptyList())
        assertFalse(store.hasHistory())
        val charge = charge(now)
        for (data in listOf(
            empty.copy(summaries = listOf(DailySummary(now / BatteryConfig.DAY_MS, ScreenMode.ON, 10, 1, 60.0, 3600.0))),
            empty.copy(raw = listOf(percentage(now).copy(exclusion = Exclusion.WARMUP))),
            empty.copy(chargeSummaries = listOf(charge.summary())),
            empty.copy(chargeRaw = listOf(charge.copy(exclusion = ChargeExclusion.CHARGING))),
        )) {
            store.replace(data, now)
            assertTrue(store.hasHistory())
            store.replace(empty, now)
            assertFalse(store.hasHistory())
        }
    }

    private fun withStore(block: (BatteryStore) -> Unit) {
        val name = "efficiency-test.db"
        context.deleteDatabase(name)
        try {
            BatteryStore(context, name).use(block)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
