package com.keltau.batterytimewidget

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

object DataTransfer {
    private const val FORMAT = "battery-time-widget"
    private const val VERSION = 1

    fun write(output: OutputStream, snapshot: BatteryStore.Snapshot, percent: Int, nowMs: Long) {
        val estimates = JSONObject()
        ScreenMode.entries.forEach { screen ->
            val estimate = BatteryEstimator.estimate(percent, screen, snapshot.summaries, nowMs)
            estimates.put(screen.name, JSONObject().apply {
                put("secondsToTarget", estimate.seconds ?: JSONObject.NULL)
                put("coverage", estimate.coverage)
                put("samples", estimate.sampleCount)
                put("days", estimate.days)
                put("bands", JSONArray().apply {
                    estimate.bands.forEach { band ->
                        put(JSONObject().apply {
                            put("band", band.band)
                            put("secondsPerPercent", band.secondsPerPercent)
                            put("effectiveSamples", band.effectiveSamples)
                        })
                    }
                })
            })
        }
        val root = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAtMs", nowMs)
            put("batteryPercent", percent)
            put("targetPercent", BatteryConfig.TARGET_PERCENT)
            put("bandWidth", BatteryConfig.BAND_WIDTH)
            put("rawRetentionDays", BatteryConfig.RAW_DAYS)
            put("summaryRetentionDays", BatteryConfig.HISTORY_DAYS)
            put("halfLifeDays", BatteryConfig.HALF_LIFE_DAYS)
            put("estimates", estimates)
            put("dailySummaries", JSONArray().apply {
                snapshot.summaries.forEach { row ->
                    put(JSONObject().apply {
                        put("day", row.day)
                        put("screen", row.screen.name)
                        put("band", row.band)
                        put("count", row.count)
                        put("seconds", row.seconds)
                        put("squaredSeconds", row.squaredSeconds)
                    })
                }
            })
            put("rawIntervals", JSONArray().apply {
                snapshot.raw.forEach { row ->
                    put(JSONObject().apply {
                        put("startMs", row.startMs)
                        put("endMs", row.endMs)
                        put("startPercent", row.startPercent)
                        put("endPercent", row.endPercent)
                        put("seconds", row.seconds)
                        put("screen", row.screen.name)
                        put("exclusion", row.exclusion?.name ?: JSONObject.NULL)
                    })
                }
            })
        }
        output.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    fun read(input: InputStream, nowMs: Long): BatteryStore.Snapshot {
        val buffer = ByteArray(8192)
        val content = ByteArrayOutputStream()
        while (true) {
            val size = input.read(buffer, 0, minOf(buffer.size, BatteryConfig.MAX_IMPORT_BYTES + 1 - content.size()))
            if (size < 0) break
            content.write(buffer, 0, size)
            require(content.size() <= BatteryConfig.MAX_IMPORT_BYTES) { "The file exceeds 8 MB." }
        }
        val bytes = content.toByteArray()
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == FORMAT && root.integer("version") == VERSION.toLong()) { "Unsupported backup format or version." }
        require(root.integer("targetPercent") == BatteryConfig.TARGET_PERCENT.toLong() && root.integer("bandWidth") == BatteryConfig.BAND_WIDTH.toLong()) { "The backup uses a different battery model." }
        val exportedAt = root.integer("exportedAtMs")
        require(exportedAt in 0..(nowMs + 300_000)) { "The backup date is in the future." }
        require(root.integer("batteryPercent") in -1..100)
        val rows = root.getJSONArray("dailySummaries")
        val intervals = root.getJSONArray("rawIntervals")
        require(rows.length() <= BatteryConfig.HISTORY_DAYS * 40 && intervals.length() <= BatteryConfig.MAX_RAW_ROWS) { "The backup contains too many records." }
        val exportDay = Math.floorDiv(exportedAt, BatteryConfig.DAY_MS)
        val keys = hashSetOf<Triple<Long, ScreenMode, Int>>()
        val summaries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val day = row.integer("day")
            val band = row.integer("band")
            val count = row.integer("count")
            val seconds = row.finite("seconds")
            val squared = row.finite("squaredSeconds")
            val screen = ScreenMode.valueOf(row.getString("screen"))
            require(day in (exportDay - BatteryConfig.HISTORY_DAYS + 1)..exportDay && band in 0..19 && count in 1..4320) { "Invalid daily summary." }
            val min = BatteryConfig.MIN_INTERVAL_SECONDS
            val max = BatteryConfig.MAX_INTERVAL_SECONDS
            require(seconds in (count * min)..(count * max)) { "Invalid discharge duration." }
            require(squared >= seconds * seconds / count - 0.01 && squared <= count * max * max + 0.01) { "Invalid discharge variance." }
            require(keys.add(Triple(day, screen, band.toInt()))) { "Duplicate daily summary." }
            DailySummary(day, screen, band.toInt(), count.toInt(), seconds, squared)
        }
        val rawKeys = hashSetOf<Pair<Long, Long>>()
        val raw = (0 until intervals.length()).map { index ->
            val row = intervals.getJSONObject(index)
            val start = row.integer("startMs")
            val end = row.integer("endMs")
            val from = row.integer("startPercent")
            val to = row.integer("endPercent")
            val seconds = row.finite("seconds")
            require(start in 0..end && end in (exportedAt - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS)..exportedAt) { "Invalid raw interval date." }
            require(from in 0..100 && to in 0..100 && from != to && seconds >= 0 && seconds <= (end - start) / 1000.0 + 120) { "Invalid raw interval." }
            require(abs((end - start) / 1000.0 - seconds) <= 120) { "Inconsistent interval clocks." }
            require(rawKeys.add(start to end)) { "Duplicate raw interval." }
            val exclusion = if (row.isNull("exclusion")) null else Exclusion.valueOf(row.getString("exclusion"))
            if (exclusion == null) {
                require(from - to == 1L && seconds in BatteryConfig.MIN_INTERVAL_SECONDS..BatteryConfig.MAX_INTERVAL_SECONDS) { "Invalid learning interval." }
            }
            DischargeInterval(start, end, from.toInt(), to.toInt(), seconds, ScreenMode.valueOf(row.getString("screen")), exclusion)
        }.sortedBy { it.endMs }
        require(raw.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) { "Overlapping raw intervals." }
        val byKey = summaries.associateBy { Triple(it.day, it.screen, it.band) }
        raw.filter { it.exclusion == null }.groupBy {
            Triple(Math.floorDiv(it.endMs, BatteryConfig.DAY_MS), it.screen, it.band)
        }.forEach { (key, group) ->
            val summary = requireNotNull(byKey[key]) { "Missing summary for learning intervals." }
            require(group.size <= summary.count && group.sumOf { it.seconds } <= summary.seconds + 0.01) { "Raw data disagrees with daily summaries." }
        }
        val firstDay = Math.floorDiv(nowMs, BatteryConfig.DAY_MS) - BatteryConfig.HISTORY_DAYS + 1
        return BatteryStore.Snapshot(
            summaries.filter { it.day >= firstDay },
            raw.filter { it.endMs >= nowMs - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS },
        )
    }

    private fun JSONObject.integer(name: String): Long {
        val value = get(name)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "Invalid integer: $name." }
        return value.toLong()
    }

    private fun JSONObject.finite(name: String): Double {
        val value = get(name)
        require(value is Number && value.toDouble().isFinite()) { "Invalid number: $name." }
        return value.toDouble()
    }
}
