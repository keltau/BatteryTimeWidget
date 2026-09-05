package com.keltau.batterytimewidget

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong

object BatteryConfig {
    const val TARGET_PERCENT = 15
    const val BAND_WIDTH = 5
    const val RAW_DAYS = 7
    const val HISTORY_DAYS = 56
    const val HALF_LIFE_DAYS = 14.0
    const val MIN_SAMPLES = 6
    const val BAND_PRIOR_SAMPLES = 4.0
    const val MIN_INTERVAL_SECONDS = 20.0
    const val MAX_INTERVAL_SECONDS = 86_400.0
    const val MAX_RAW_ROWS = 20_000
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    const val DAY_MS = 86_400_000L
}

enum class ScreenMode { OFF, ON }

data class BatteryReading(
    val timeMs: Long,
    val elapsedMs: Long,
    val percent: Int,
    val discharging: Boolean,
    val screen: ScreenMode,
    val audioActive: Boolean,
    val audioKnown: Boolean = true,
)

enum class Exclusion {
    WARMUP, CHARGING, SCREEN_CHANGE, SCREEN_OFF_AUDIO, AUDIO_UNKNOWN,
    LEVEL_JUMP, CLOCK_CHANGE, DURATION,
}

data class DischargeInterval(
    val startMs: Long,
    val endMs: Long,
    val startPercent: Int,
    val endPercent: Int,
    val seconds: Double,
    val screen: ScreenMode,
    val exclusion: Exclusion?,
) {
    val band: Int get() = endPercent / BatteryConfig.BAND_WIDTH
}

class IntervalTracker {
    private var anchor: BatteryReading? = null
    private var last: BatteryReading? = null
    private var exclusion: Exclusion? = Exclusion.WARMUP

    fun reset() {
        anchor = null
        last = null
        exclusion = Exclusion.WARMUP
    }

    fun observe(reading: BatteryReading): DischargeInterval? {
        if (reading.percent !in 0..100) {
            reset()
            return null
        }
        val previous = last
        last = reading
        val start = anchor
        if (start == null || previous == null) {
            anchor = reading
            exclusion = stateExclusion(reading) ?: Exclusion.WARMUP
            return null
        }
        val stateProblem = stateExclusion(reading) ?: stateExclusion(previous)
        if (stateProblem != null) exclusion = stateProblem
        if (previous.screen != reading.screen && exclusion == null) {
            exclusion = Exclusion.SCREEN_CHANGE
        }
        val elapsedDelta = reading.elapsedMs - previous.elapsedMs
        val wallDelta = reading.timeMs - previous.timeMs
        if (elapsedDelta < 0 || abs(wallDelta - elapsedDelta) > 120_000) {
            anchor = reading
            exclusion = Exclusion.CLOCK_CHANGE
            return null
        }
        if (reading.percent == start.percent) return null
        val seconds = (reading.elapsedMs - start.elapsedMs) / 1000.0
        val drop = start.percent - reading.percent
        val reason = exclusion ?: when {
            drop != 1 -> Exclusion.LEVEL_JUMP
            seconds !in BatteryConfig.MIN_INTERVAL_SECONDS..BatteryConfig.MAX_INTERVAL_SECONDS -> Exclusion.DURATION
            else -> null
        }
        val interval = DischargeInterval(
            start.timeMs, reading.timeMs, start.percent, reading.percent,
            seconds.coerceAtLeast(0.0), start.screen, reason,
        )
        anchor = reading
        exclusion = stateExclusion(reading) ?: if (drop == 1) null else Exclusion.WARMUP
        return interval
    }

    private fun stateExclusion(reading: BatteryReading): Exclusion? = when {
        !reading.discharging -> Exclusion.CHARGING
        !reading.audioKnown -> Exclusion.AUDIO_UNKNOWN
        reading.screen == ScreenMode.OFF && reading.audioActive -> Exclusion.SCREEN_OFF_AUDIO
        else -> null
    }
}

data class DailySummary(
    val day: Long,
    val screen: ScreenMode,
    val band: Int,
    val count: Int,
    val seconds: Double,
    val squaredSeconds: Double,
)

data class BandEstimate(
    val band: Int,
    val secondsPerPercent: Double,
    val effectiveSamples: Double,
    val days: Int,
)

data class TimeEstimate(
    val seconds: Long?,
    val coverage: Double,
    val sampleCount: Int,
    val days: Int,
    val bands: List<BandEstimate>,
) {
    val established: Boolean get() = seconds != null && coverage >= 0.8 && days >= 7
}

object BatteryEstimator {
    fun estimate(percent: Int, screen: ScreenMode, summaries: List<DailySummary>, nowMs: Long): TimeEstimate {
        val today = Math.floorDiv(nowMs, BatteryConfig.DAY_MS)
        val data = summaries.filter {
            it.screen == screen && it.day in (today - BatteryConfig.HISTORY_DAYS + 1)..today &&
                it.count > 0 && it.seconds.isFinite() && it.seconds > 0
        }
        fun weight(row: DailySummary) = exp(-ln(2.0) * (today - row.day) / BatteryConfig.HALF_LIFE_DAYS)
        val count = data.sumOf { it.count }
        val days = data.map { it.day }.distinct().size
        val totalWeight = data.sumOf { it.count * weight(it) }
        val global = if (totalWeight > 0) data.sumOf { it.seconds * weight(it) } / totalWeight else 0.0
        val bands = (0 until 100 / BatteryConfig.BAND_WIDTH).map { band ->
            val rows = data.filter { it.band == band }
            val n = rows.sumOf { it.count * weight(it) }
            val seconds = rows.sumOf { it.seconds * weight(it) }
            BandEstimate(
                band, (seconds + BatteryConfig.BAND_PRIOR_SAMPLES * global) / (n + BatteryConfig.BAND_PRIOR_SAMPLES),
                n, rows.map { it.day }.distinct().size,
            )
        }
        if (percent !in 0..100) return TimeEstimate(null, 0.0, count, days, bands)
        if (percent <= BatteryConfig.TARGET_PERCENT) return TimeEstimate(0, 1.0, count, days, bands)
        val remaining = BatteryConfig.TARGET_PERCENT until percent
        val covered = remaining.count {
            val band = bands[it / BatteryConfig.BAND_WIDTH]
            band.effectiveSamples >= 3 && band.days >= 2
        }
        val enough = count >= BatteryConfig.MIN_SAMPLES && totalWeight >= 3
        val seconds = if (enough) remaining.sumOf { bands[it / BatteryConfig.BAND_WIDTH].secondsPerPercent }.roundToLong() else null
        return TimeEstimate(seconds, covered.toDouble() / max(1, remaining.count()), count, days, bands)
    }
}
