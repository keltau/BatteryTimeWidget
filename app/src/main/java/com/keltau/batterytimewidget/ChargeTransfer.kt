package com.keltau.batterytimewidget

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Version 2 charge records. Cached estimates never participate in restored learning. */
object ChargeTransfer {
    fun json(row: ChargeSummary) = JSONObject().apply {
        put("day", row.day); put("screen", row.screen.name); put("band", row.band); put("count", row.count)
        put("seconds", row.seconds); put("chargeUah", row.chargeUah); put("squaredRateSeconds", row.squaredRateSeconds)
        put("capacityUahSeconds", row.capacityUahSeconds); put("squaredCapacitySeconds", row.squaredCapacitySeconds)
    }

    fun json(row: ChargeInterval) = JSONObject().apply {
        put("start", json(row.start)); put("end", json(row.end))
        put("exclusion", row.exclusion?.name ?: JSONObject.NULL)
    }

    private fun json(row: BatteryReading) = JSONObject().apply {
        put("timeMs", row.timeMs); put("elapsedMs", row.elapsedMs); put("percent", row.percent)
        put("discharging", row.discharging); put("screen", row.screen.name)
        put("audioActive", row.audioActive); put("audioKnown", row.audioKnown)
        put("chargeUah", row.chargeUah ?: JSONObject.NULL); put("currentUa", row.currentUa ?: JSONObject.NULL)
        put("averageCurrentUa", row.averageCurrentUa ?: JSONObject.NULL); put("voltageMv", row.voltageMv ?: JSONObject.NULL)
        put("temperatureDeciC", row.temperatureDeciC ?: JSONObject.NULL)
    }

    fun interval(row: JSONObject) = ChargeInterval(reading(row.getJSONObject("start")), reading(row.getJSONObject("end")),
        if (row.isNull("exclusion")) null else ChargeExclusion.valueOf(row.getString("exclusion")))

    private fun reading(row: JSONObject): BatteryReading {
        fun optional(name: String): Long? = if (row.isNull(name)) null else row.integer(name)
        fun optionalInt(name: String): Int? = optional(name)?.also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }?.toInt()
        val percent = row.integer("percent").also { require(it in -1..100) }.toInt()
        val charge = optional("chargeUah").also { require(it == null || it in 1..Int.MAX_VALUE.toLong()) }
        fun boolean(name: String) = row.get(name).also { require(it is Boolean) } as Boolean
        return BatteryReading(row.integer("timeMs"), row.integer("elapsedMs"), percent, boolean("discharging"),
            ScreenMode.valueOf(row.getString("screen")), boolean("audioActive"), boolean("audioKnown"), charge,
            optionalInt("currentUa"), optionalInt("averageCurrentUa"), optionalInt("voltageMv"), optionalInt("temperatureDeciC"))
    }

    fun read(rows: JSONArray, intervals: JSONArray, exportedAt: Long, nowMs: Long): Pair<List<ChargeSummary>, List<ChargeInterval>> {
        require(rows.length() <= BatteryConfig.HISTORY_DAYS * 40 && intervals.length() <= BatteryConfig.MAX_CHARGE_RAW_ROWS) { "Too many charge records." }
        val day = Math.floorDiv(exportedAt, BatteryConfig.DAY_MS)
        val keys = hashSetOf<Triple<Long, ScreenMode, Int>>()
        val summaries = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val d = r.integer("day"); val band = r.integer("band"); val count = r.integer("count")
            require(d in (day - BatteryConfig.HISTORY_DAYS + 1)..day && band in 0..19 && count in 1..4320) { "Invalid charge summary." }
            val row = ChargeSummary(d, ScreenMode.valueOf(r.getString("screen")), band.toInt(), count.toInt(),
                r.number("seconds"), r.number("chargeUah"), r.number("squaredRateSeconds"), r.number("capacityUahSeconds"), r.number("squaredCapacitySeconds"))
            require(keys.add(Triple(row.day, row.screen, row.band))) { "Duplicate charge summary." }
            require(row.seconds in count * BatteryConfig.MIN_INTERVAL_SECONDS..count * BatteryConfig.MAX_INTERVAL_SECONDS) { "Invalid charge duration." }
            val maxQ = ChargeConfig.MAX_CAPACITY_UAH * ChargeConfig.MAX_DROP_PERCENT / 100
            require(row.chargeUah in count * ChargeConfig.MIN_DROP_UAH..count * maxQ) { "Invalid charge amount." }
            require(row.capacityUahSeconds / row.seconds in ChargeConfig.MIN_CAPACITY_UAH..ChargeConfig.MAX_CAPACITY_UAH) { "Invalid charge calibration." }
            require(row.squaredRateSeconds + 0.01 >= row.chargeUah * row.chargeUah / row.seconds &&
                row.squaredRateSeconds <= count * maxQ * maxQ / BatteryConfig.MIN_INTERVAL_SECONDS + 0.01) { "Invalid charge variance." }
            require(row.squaredCapacitySeconds + row.squaredCapacitySeconds * 1e-9 >= row.capacityUahSeconds * row.capacityUahSeconds / row.seconds &&
                row.squaredCapacitySeconds <= row.seconds * ChargeConfig.MAX_CAPACITY_UAH * ChargeConfig.MAX_CAPACITY_UAH * (1 + 1e-9)) { "Invalid calibration variance." }
            row
        }
        val rawKeys = hashSetOf<Pair<Long, Long>>()
        val raw = (0 until intervals.length()).map { i ->
            val row = interval(intervals.getJSONObject(i))
            require(row.start.timeMs in 0..(exportedAt + 300_000) && row.end.timeMs in (exportedAt - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS)..exportedAt &&
                row.start.elapsedMs >= 0 && row.end.elapsedMs >= 0) { "Invalid charge interval date." }
            require(rawKeys.add(row.start.timeMs to row.end.timeMs)) { "Duplicate charge interval." }
            if (row.exclusion != ChargeExclusion.CLOCK_CHANGE) {
                require(row.start.timeMs <= row.end.timeMs && row.start.elapsedMs <= row.end.elapsedMs &&
                    abs((row.end.timeMs - row.start.timeMs) / 1000.0 - row.seconds) <= 120) { "Inconsistent charge interval clocks." }
            }
            if (row.exclusion == null) {
                val tracker = ChargeTracker()
                tracker.observe(row.start)
                val checked = tracker.observe(row.end)
                require(checked != null && checked.exclusion == null) { "Invalid charge learning interval." }
            }
            row
        }.sortedBy { it.end.timeMs }
        require(raw.filter { it.exclusion != ChargeExclusion.CLOCK_CHANGE }.zipWithNext().all { (a, b) -> a.end.timeMs <= b.start.timeMs }) { "Overlapping charge intervals." }
        val byKey = summaries.associateBy { Triple(it.day, it.screen, it.band) }
        raw.filter { it.exclusion == null }.map { it.summary() }.groupBy { Triple(it.day, it.screen, it.band) }.forEach { (key, group) ->
            val summary = requireNotNull(byKey[key]) { "Missing charge summary." }
            val sum = group.reduce { a, b -> a + b }
            fun fits(a: Double, b: Double) = a <= b + maxOf(0.01, abs(b) * 1e-9)
            require(sum.count <= summary.count && fits(sum.seconds, summary.seconds) && fits(sum.chargeUah, summary.chargeUah) &&
                fits(sum.squaredRateSeconds, summary.squaredRateSeconds) && fits(sum.capacityUahSeconds, summary.capacityUahSeconds) &&
                fits(sum.squaredCapacitySeconds, summary.squaredCapacitySeconds)) { "Charge raw data disagrees with summaries." }
        }
        val firstDay = Math.floorDiv(nowMs, BatteryConfig.DAY_MS) - BatteryConfig.HISTORY_DAYS + 1
        return summaries.filter { it.day >= firstDay } to raw.filter { it.end.timeMs >= nowMs - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS }
    }

    private fun JSONObject.number(name: String): Double {
        val value = get(name)
        require(value is Number && value.toDouble().isFinite()) { "Invalid number: $name." }
        return value.toDouble()
    }

    private fun JSONObject.integer(name: String): Long {
        val value = number(name)
        require(value == value.toLong().toDouble()) { "Invalid integer: $name." }
        return value.toLong()
    }
}
