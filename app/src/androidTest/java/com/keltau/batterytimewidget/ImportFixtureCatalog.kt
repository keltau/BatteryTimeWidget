package com.keltau.batterytimewidget

import org.json.JSONObject
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

/** Deterministic synthetic histories; no real user data enters exported fixtures. */
internal class ImportFixtureCatalog(val now: Long) {
    private val dayMs = BatteryConfig.DAY_MS
    data class Case(val name: String, val description: String, val exportedAt: Long,
                    val data: BatteryStore.Snapshot, val version: Int = 2)

    val cases: List<Case> by lazy {
        listOf(
            Case("01-mature-agreement", "Both algorithms agree: 600 seconds per percent off, 120 on; mature coverage and a genuine blend.", now, dual(now, 14)),
            Case("02-opposing-usage-changes", "Recent usage is weighted twice as strongly as the preceding fortnight. Percentage rates are 400/180 seconds per percent; counter rates are 500/200. Both estimates must contribute.", now,
                dual(now, 28, { age, mode -> if (mode == ScreenMode.OFF) (if (age <= 14) 300.0 else 600.0) else (if (age <= 14) 120.0 else 300.0) },
                    { age, mode -> if (mode == ScreenMode.OFF) (if (age <= 14) 600.0 else 300.0) else (if (age <= 14) 240.0 else 120.0) })),
            Case("03-realistic-bands-and-exclusions", "Six weeks of varying battery-band rates, different screen states and every exclusion reason. Removing excluded raw rows must not change either estimate, weight or learning count.", now, realistic()),
            Case("04-mixed-learning-thresholds", "Off: five percentage drops are insufficient, but three sub-percent counter windows are ready. On: six percentage drops are ready, but two counter windows are insufficient. Each screen state uses the appropriate model alone.", now, early()),
            Case("05-legacy-percentage-only", "A real version 1 backup. It restores percentage learning, clears previous counter history and uses 100% percentage weight.", now,
                dual(now, 7).copy(chargeSummaries = emptyList(), chargeRaw = emptyList()), version = 1),
            Case("06-retention-boundaries", "One-day-old export with both models at summary ages 55/56 and raw endpoints just before, exactly at and after the rolling seven-day cutoff. Expired raw rows must not erase retained learning.", now - dayMs, retention()),
            Case("07-fully-expired-to-empty", "Sixty-day-old backup: both histories expire completely. Both modes show Learning above the target and zero at/below 15%, including after replacing mature data.", now - 60 * dayMs, dual(now - 60 * dayMs, 7)),
            Case("08-reliability-cross-over", "Off: variable percentage history gives the stable counter model more influence. On: stable percentage history outweighs variable counter history. Neither ready model is switched off.", now,
                dual(now, 14, { age, mode -> if (mode == ScreenMode.ON) 120.0 else if (age % 2 == 0) 60.0 else 660.0 },
                    { age, mode -> if (mode == ScreenMode.OFF) 360.0 else if (age % 2 == 0) 20.0 else 220.0 })),
        )
    }

    // Additional scenario: generated/tested independently so the original ten files
    // and their recorded run can be preserved exactly.
    fun realisticUsage(history: RealisticUsageHistory.History = RealisticUsageHistory.generate(now)) = Case(
        "11-realistic-daily-usage",
        "Six weeks of a synthetic 4,500 mAh phone: irregular screen sessions, overnight idle, music, evening charging, partial top-ups, variable load/voltage/temperature and brief sensor outages. Both collectors process the same battery events and learn independently.",
        now, snapshot(history.percentage, history.charge, now),
    )

    fun encode(case: Case): JSONObject {
        val json = JSONObject(ByteArrayOutputStream().also { DataTransfer.write(it, case.data, 80, case.exportedAt) }.toString("UTF-8"))
        if (case.version == 1) {
            json.put("version", 1)
            json.remove("chargeModel"); json.remove("chargeDailySummaries"); json.remove("chargeRawIntervals")
        }
        return json.put("syntheticTestData", true).put("testScenario", case.description)
    }

    private fun dual(exportedAt: Long, days: Int,
                     percentageRate: (Int, ScreenMode) -> Double = { _, mode -> if (mode == ScreenMode.OFF) 600.0 else 120.0 },
                     counterRate: (Int, ScreenMode) -> Double = percentageRate): BatteryStore.Snapshot {
        return snapshot(cycles(exportedAt, days, percentageRate), cycles(exportedAt, days, counterRate).map(::counter), exportedAt)
    }

    private fun cycles(exportedAt: Long, days: Int, rate: (Int, ScreenMode) -> Double): List<DischargeInterval> {
        val rows = mutableListOf<DischargeInterval>()
        val today = Math.floorDiv(exportedAt, dayMs)
        for (age in days downTo 1) {
            val startOfDay = (today - age) * dayMs
            var cursor = startOfDay + 3_600_000
            ScreenMode.entries.forEach { mode ->
                for (percent in 100 downTo 16) {
                    val seconds = rate(age, mode)
                    val end = cursor + (seconds * 1000).roundToLong()
                    rows += DischargeInterval(cursor, end, percent, percent - 1, seconds, mode, if (percent == 100) Exclusion.WARMUP else null)
                    cursor = end
                }
                cursor += 1_800_000 // Recharge / unobserved time is not learned.
            }
            check(cursor < startOfDay + dayMs) { "Synthetic cycles overlap days" }
        }
        return rows
    }

    private fun counter(row: DischargeInterval): ChargeInterval {
        val reason = row.exclusion?.let { when (it) {
            Exclusion.WARMUP, Exclusion.SCREEN_CHANGE -> ChargeExclusion.WARMUP
            Exclusion.CHARGING -> ChargeExclusion.CHARGING
            Exclusion.SCREEN_OFF_AUDIO -> ChargeExclusion.SCREEN_OFF_AUDIO
            Exclusion.AUDIO_UNKNOWN -> ChargeExclusion.AUDIO_UNKNOWN
            Exclusion.LEVEL_JUMP -> ChargeExclusion.LEVEL_JUMP
            Exclusion.CLOCK_CHANGE -> ChargeExclusion.CLOCK_CHANGE
            Exclusion.DURATION -> ChargeExclusion.DURATION
        } }
        fun reading(time: Long, percent: Int) = BatteryReading(time, time, percent, row.exclusion != Exclusion.CHARGING,
            row.screen, row.exclusion == Exclusion.SCREEN_OFF_AUDIO, row.exclusion != Exclusion.AUDIO_UNKNOWN,
            percent * 40_000L, (-40_000 * 3600 / row.seconds).toInt(), (-40_000 * 3600 / row.seconds).toInt(), 3900, 270)
        return ChargeInterval(reading(row.startMs, row.startPercent), reading(row.endMs, row.endPercent), reason)
    }

    private fun snapshot(raw: List<DischargeInterval>, chargeRaw: List<ChargeInterval>, exportedAt: Long): BatteryStore.Snapshot {
        val p = raw.filter { it.exclusion == null }.groupBy { Triple(Math.floorDiv(it.endMs, dayMs), it.screen, it.band) }.map { (key, rows) ->
            DailySummary(key.first, key.second, key.third, rows.size, rows.sumOf { it.seconds }, rows.sumOf { it.seconds * it.seconds })
        }.sortedWith(compareBy({ it.day }, { it.screen.name }, { it.band }))
        val c = chargeRaw.filter { it.exclusion == null }.map { it.summary() }.groupBy { Triple(it.day, it.screen, it.band) }.values.map { rows ->
            rows.reduce { a, b -> a + b }
        }.sortedWith(compareBy({ it.day }, { it.screen.name }, { it.band }))
        return BatteryStore.Snapshot(p, raw.filter { it.endMs >= exportedAt - 7 * dayMs }.sortedBy { it.endMs },
            c, chargeRaw.filter { it.end.timeMs >= exportedAt - 7 * dayMs }.sortedBy { it.end.timeMs })
    }

    private fun realistic(): BatteryStore.Snapshot {
        val raw = mutableListOf<DischargeInterval>()
        val charge = mutableListOf<ChargeInterval>()
        for (age in 42 downTo 1) {
            var cursor = (Math.floorDiv(now, dayMs) - age) * dayMs + 3_600_000
            for (percent in 100 downTo 16) {
                val band = (percent - 1) / 5
                val mode = if ((age + band) % 2 == 0) ScreenMode.OFF else ScreenMode.ON
                val base = if (mode == ScreenMode.OFF) 360.0 + (10 - abs(10 - band)) * 45 else 90.0 + (10 - abs(10 - band)) * 8
                val seconds = (base * (0.85 + age % 7 * 0.05)).roundToLong().toDouble()
                val reason = when {
                    percent == 100 -> Exclusion.WARMUP
                    mode == ScreenMode.OFF && age % 6 == 0 && band == 10 -> Exclusion.SCREEN_OFF_AUDIO
                    percent % 5 == 0 -> Exclusion.SCREEN_CHANGE
                    else -> null
                }
                val row = DischargeInterval(cursor, cursor + seconds.toLong() * 1000, percent, percent - 1, seconds, mode, reason)
                raw += row
                charge += counter(row)
                cursor = row.endMs
            }
        }
        // A separate recent gap holds all diagnostic reasons, without duplicating learning.
        var cursor = now - 3_600_000
        Exclusion.entries.forEach { reason ->
            raw += DischargeInterval(cursor, cursor + 30_000, 51, 50, 30.0, ScreenMode.OFF, reason)
            cursor += 60_000
        }
        cursor = now - 3_600_000
        ChargeExclusion.entries.forEach { reason ->
            val start = BatteryReading(cursor, cursor, 50, true, ScreenMode.OFF, false, chargeUah = 2_000_000, voltageMv = 3900, temperatureDeciC = 270)
            val end = start.copy(timeMs = cursor + 30_000, elapsedMs = cursor + 30_000, chargeUah = 1_980_000)
            charge += ChargeInterval(start, end, reason)
            cursor += 60_000
        }
        return snapshot(raw, charge, now)
    }

    private fun early(): BatteryStore.Snapshot {
        val raw = mutableListOf<DischargeInterval>()
        val charge = mutableListOf<ChargeInterval>()
        var cursor = now - 10_000_000
        ScreenMode.entries.forEach { mode ->
            repeat(if (mode == ScreenMode.OFF) 5 else 6) { i ->
                raw += DischargeInterval(cursor, cursor + 120_000, 55 - i, 54 - i, 120.0, mode, null)
                cursor += 120_000
            }
        }
        cursor = now - 600_000
        ScreenMode.entries.forEach { mode ->
            repeat(if (mode == ScreenMode.OFF) 3 else 2) { i ->
                val start = BatteryReading(cursor, cursor, 50, true, mode, false, chargeUah = 2_000_000L - i * 2000, currentUa = -120000, voltageMv = 3900, temperatureDeciC = 270)
                val end = start.copy(timeMs = cursor + 60_000, elapsedMs = cursor + 60_000, chargeUah = start.chargeUah!! - 2000,
                    screen = if (i == 2 && mode == ScreenMode.OFF) ScreenMode.ON else mode)
                charge += ChargeInterval(start, end, null)
                cursor += 60_000
            }
        }
        return snapshot(raw, charge, now)
    }

    private fun retention(): BatteryStore.Snapshot {
        val exportedAt = now - dayMs
        val history = cycles(exportedAt, 55) { _, mode -> if (mode == ScreenMode.OFF) 600.0 else 120.0 }
        val boundary = listOf(-600_000L, 0L, 600_000L).map { offset ->
            val end = now - 7 * dayMs + offset
            DischargeInterval(end - 120_000, end, 51, 50, 120.0, ScreenMode.ON, null)
        }
        // Summaries can outlive raw diagnostics; retaining only these raw rows isolates the cutoff.
        val data = snapshot(history + boundary, (history + boundary).map(::counter), exportedAt)
        return data.copy(raw = boundary, chargeRaw = boundary.map(::counter))
    }

    fun validate(restored: Map<String, BatteryStore.Snapshot>) {
        fun estimate(index: Int, percent: Int, mode: ScreenMode): HybridEstimate {
            val data = restored.getValue(cases[index].name)
            return HybridEstimator.estimate(percent, mode, data.summaries, data.chargeSummaries, now)
        }
        // Independent analytic oracles for all percentages: no production prediction is reused.
        for (percent in 0..100) for (mode in ScreenMode.entries) {
            val points = (percent - 15).coerceAtLeast(0)
            val fixed = points * if (mode == ScreenMode.OFF) 600L else 120L
            val constant = estimate(0, percent, mode)
            assertEquals(fixed, constant.percentage.seconds)
            assertEquals(fixed, constant.charge.time.seconds)
            assertEquals(fixed, constant.seconds)
            val changing = estimate(1, percent, mode)
            val p = points * if (mode == ScreenMode.OFF) 400L else 180L
            val c = points * if (mode == ScreenMode.OFF) 500L else 200L
            assertEquals(p, changing.percentage.seconds)
            assertEquals(c, changing.charge.time.seconds)
            assertEquals((p * changing.percentageWeight + c * changing.chargeWeight).roundToLong(), changing.seconds)
            if (points > 0) {
                assertTrue(constant.established)
                assertEquals(1.0, constant.percentage.coverage, 0.0)
                assertEquals(1.0, constant.charge.time.coverage, 0.0)
                assertTrue(changing.percentageWeight > 0 && changing.chargeWeight > 0)
                assertTrue(changing.seconds!! in p..c)
            }
            val legacy = estimate(4, percent, mode)
            assertEquals(fixed, legacy.seconds)
            if (points > 0) assertEquals(1.0, legacy.percentageWeight, 0.0)
            val expired = estimate(6, percent, mode)
            assertEquals(if (points == 0) 0L else null, expired.seconds)
        }
        val off = estimate(3, 80, ScreenMode.OFF)
        assertNull(off.percentage.seconds); assertNotNull(off.charge.time.seconds)
        assertEquals(1.0, off.chargeWeight, 0.0)
        val on = estimate(3, 80, ScreenMode.ON)
        assertNotNull(on.percentage.seconds); assertNull(on.charge.time.seconds)
        assertEquals(1.0, on.percentageWeight, 0.0)
        assertTrue(estimate(7, 80, ScreenMode.OFF).chargeWeight > 0.5)
        assertTrue(estimate(7, 80, ScreenMode.ON).percentageWeight > 0.5)
        val varied = restored.getValue(cases[2].name)
        assertEquals(Exclusion.entries.toSet(), varied.raw.mapNotNull { it.exclusion }.toSet())
        assertEquals(ChargeExclusion.entries.toSet(), varied.chargeRaw.mapNotNull { it.exclusion }.toSet())
        for (mode in ScreenMode.entries) {
            val bands = estimate(2, 80, mode)
            assertTrue(bands.percentage.bands[10].secondsPerPercent > bands.percentage.bands[3].secondsPerPercent)
            assertTrue(bands.charge.time.bands[10].secondsPerPercent > bands.charge.time.bands[3].secondsPerPercent)
        }
        val retained = restored.getValue(cases[5].name)
        val cutoffDay = Math.floorDiv(now, dayMs) - 55
        assertEquals(cutoffDay, retained.summaries.minOf { it.day })
        assertEquals(cutoffDay, retained.chargeSummaries.minOf { it.day })
        assertEquals(2, retained.raw.size); assertEquals(2, retained.chargeRaw.size)
        assertEquals(BatteryStore.Snapshot(emptyList(), emptyList()), restored.getValue(cases[6].name))
    }
}
