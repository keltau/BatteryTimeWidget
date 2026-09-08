package com.keltau.batterytimewidget

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong

object ChargeConfig {
    const val MIN_CAPACITY_UAH = 100_000.0
    const val MAX_CAPACITY_UAH = 30_000_000.0
    const val MIN_SAMPLES = 3
    const val MIN_LEARNING_SECONDS = 120.0
    const val MIN_LEARNING_PERCENT = 0.1
    const val MIN_DROP_UAH = 10.0
    const val MIN_DROP_FRACTION = 0.0001
    const val MAX_DROP_PERCENT = 1.5
}

enum class ChargeExclusion {
    UNAVAILABLE, CHARGING, AUDIO_UNKNOWN, SCREEN_OFF_AUDIO, CLOCK_CHANGE,
    DURATION, COUNTER_RESET, LEVEL_JUMP, CALIBRATION, TOO_SMALL, WARMUP,
}

data class ChargeInterval(val start: BatteryReading, val end: BatteryReading, val exclusion: ChargeExclusion?) {
    val seconds: Double get() = ((end.elapsedMs - start.elapsedMs) / 1000.0).coerceAtLeast(0.0)
    val chargeUah: Double get() = (start.chargeUah ?: 0).toDouble() - (end.chargeUah ?: 0).toDouble()
    val capacityUah: Double get() = if (start.percent > 0 && end.percent > 0)
        ((start.chargeUah ?: 0) * 100.0 / start.percent + (end.chargeUah ?: 0) * 100.0 / end.percent) / 2 else 0.0
    val band: Int get() = (end.percent / BatteryConfig.BAND_WIDTH).coerceIn(0, 19)
    fun summary(): ChargeSummary = ChargeSummary(
        Math.floorDiv(end.timeMs, BatteryConfig.DAY_MS), start.screen, band, 1, seconds,
        chargeUah, chargeUah * chargeUah / seconds, capacityUah * seconds, capacityUah * capacityUah * seconds,
    )
}

class ChargeTracker {
    private var anchor: BatteryReading? = null
    private var last: BatteryReading? = null
    private var awaitingCounterBoundary = false

    fun reset() { anchor = null; last = null; awaitingCounterBoundary = false }

    fun observe(reading: BatteryReading): ChargeInterval? {
        val previous = last
        last = reading
        val start = anchor
        if (start == null || previous == null) { anchor = reading; return null }
        val interval = ChargeInterval(start, reading, null)
        val changed = previous.screen != reading.screen || previous.audioActive != reading.audioActive ||
            previous.audioKnown != reading.audioKnown || previous.discharging != reading.discharging
        val clockChanged = reading.elapsedMs < previous.elapsedMs ||
            abs((reading.timeMs - previous.timeMs) - (reading.elapsedMs - previous.elapsedMs)) > 120_000
        val reason = when {
            clockChanged -> ChargeExclusion.CLOCK_CHANGE
            start.chargeUah == null || reading.chargeUah == null -> ChargeExclusion.UNAVAILABLE
            !start.discharging || !reading.discharging -> ChargeExclusion.CHARGING
            !start.audioKnown || !reading.audioKnown -> ChargeExclusion.AUDIO_UNKNOWN
            start.screen == ScreenMode.OFF && start.audioActive -> ChargeExclusion.SCREEN_OFF_AUDIO
            start.percent !in 15..100 || reading.percent !in 15..100 -> ChargeExclusion.CALIBRATION
            reading.chargeUah > (previous.chargeUah ?: reading.chargeUah) -> ChargeExclusion.COUNTER_RESET
            start.percent - reading.percent !in 0..1 -> ChargeExclusion.LEVEL_JUMP
            interval.capacityUah !in ChargeConfig.MIN_CAPACITY_UAH..ChargeConfig.MAX_CAPACITY_UAH -> ChargeExclusion.CALIBRATION
            interval.chargeUah / interval.capacityUah * 100 > ChargeConfig.MAX_DROP_PERCENT -> ChargeExclusion.COUNTER_RESET
            interval.seconds > BatteryConfig.MAX_INTERVAL_SECONDS -> ChargeExclusion.DURATION
            interval.seconds < BatteryConfig.MIN_INTERVAL_SECONDS -> ChargeExclusion.TOO_SMALL
            interval.chargeUah < max(ChargeConfig.MIN_DROP_UAH, interval.capacityUah * ChargeConfig.MIN_DROP_FRACTION) -> ChargeExclusion.TOO_SMALL
            interval.chargeUah / interval.seconds * 3600 > interval.capacityUah * 5 -> ChargeExclusion.COUNTER_RESET
            awaitingCounterBoundary -> ChargeExclusion.WARMUP
            else -> null
        }
        if (reason == ChargeExclusion.TOO_SMALL && !changed) return null
        awaitingCounterBoundary = when {
            changed && reading.chargeUah == previous.chargeUah -> true
            reading.chargeUah != start.chargeUah -> false
            else -> awaitingCounterBoundary
        }
        anchor = reading
        if (interval.seconds == 0.0 && !clockChanged) return null
        return interval.copy(exclusion = reason)
    }
}

data class ChargeSummary(
    val day: Long, val screen: ScreenMode, val band: Int, val count: Int,
    val seconds: Double, val chargeUah: Double, val squaredRateSeconds: Double,
    val capacityUahSeconds: Double, val squaredCapacitySeconds: Double,
) {
    operator fun plus(other: ChargeSummary): ChargeSummary {
        require(day == other.day && screen == other.screen && band == other.band)
        return copy(count = count + other.count, seconds = seconds + other.seconds,
            chargeUah = chargeUah + other.chargeUah, squaredRateSeconds = squaredRateSeconds + other.squaredRateSeconds,
            capacityUahSeconds = capacityUahSeconds + other.capacityUahSeconds,
            squaredCapacitySeconds = squaredCapacitySeconds + other.squaredCapacitySeconds)
    }
}

data class ChargeEstimate(
    val time: TimeEstimate, val capacityUah: Double?, val observedSeconds: Double,
    val equivalentPercent: Double,
)

object ChargeEstimator {
    fun estimate(percent: Int, screen: ScreenMode, summaries: List<ChargeSummary>, nowMs: Long): ChargeEstimate {
        val today = Math.floorDiv(nowMs, BatteryConfig.DAY_MS)
        val data = summaries.filter { it.screen == screen && it.day in (today - BatteryConfig.HISTORY_DAYS + 1)..today && it.seconds > 0 && it.chargeUah > 0 }
        fun weight(row: ChargeSummary) = exp(-ln(2.0) * (today - row.day) / BatteryConfig.HALF_LIFE_DAYS)
        val seconds = data.sumOf { it.seconds * weight(it) }
        val charge = data.sumOf { it.chargeUah * weight(it) }
        val capacity = if (seconds > 0) data.sumOf { it.capacityUahSeconds * weight(it) } / seconds else 0.0
        val equivalent = if (capacity > 0) charge / capacity * 100 else 0.0
        val rate = if (seconds > 0) charge / seconds else 0.0
        val count = data.sumOf { it.count }
        val days = data.map { it.day }.distinct().size
        val global = if (rate > 0) capacity / 100 / rate else 0.0
        val bands = (0..19).map { band ->
            val rows = data.filter { it.band == band }
            val q = rows.sumOf { it.chargeUah * weight(it) }
            val t = rows.sumOf { it.seconds * weight(it) }
            val n = if (capacity > 0) q / capacity * 100 else 0.0
            BandEstimate(band, (t + 4 * global) / (n + 4), n, rows.map { it.day }.distinct().size)
        }
        val remaining = BatteryConfig.TARGET_PERCENT until percent.coerceIn(0, 100)
        val coverage = if (remaining.isEmpty()) 0.0 else remaining.count {
            bands[it / 5].effectiveSamples >= 3 && bands[it / 5].days >= 2
        }.toDouble() / remaining.count()
        val enough = count >= ChargeConfig.MIN_SAMPLES && seconds >= ChargeConfig.MIN_LEARNING_SECONDS &&
            equivalent >= ChargeConfig.MIN_LEARNING_PERCENT && capacity in ChargeConfig.MIN_CAPACITY_UAH..ChargeConfig.MAX_CAPACITY_UAH
        val time = when {
            percent !in 0..100 -> null
            percent <= BatteryConfig.TARGET_PERCENT -> 0L
            enough -> remaining.sumOf { bands[it / 5].secondsPerPercent }.roundToLong()
            else -> null
        }
        val rateCv = if (rate > 0) max(0.0, data.sumOf { it.squaredRateSeconds * weight(it) } / seconds / (rate * rate) - 1) else 0.0
        val capacityCv = if (capacity > 0) max(0.0, data.sumOf { it.squaredCapacitySeconds * weight(it) } / seconds / (capacity * capacity) - 1) else 0.0
        val consistency = 1 / (1.15 + rateCv + 4 * capacityCv)
        val reliability = if (time == null || seconds == 0.0) 0.0 else evidenceReliability(
            equivalent, 4.0, coverage, days, consistency, seconds / data.sumOf { it.seconds })
        return ChargeEstimate(TimeEstimate(time, coverage, count, days, bands, reliability), capacity.takeIf { it > 0 }, seconds, equivalent)
    }
}

data class HybridEstimate(val percentage: TimeEstimate, val charge: ChargeEstimate, val seconds: Long?, val percentageWeight: Double, val chargeWeight: Double) {
    val established: Boolean get() = seconds != null &&
        (percentageWeight == 0.0 || percentage.established) && (chargeWeight == 0.0 || charge.time.established)
}

object HybridEstimator {
    fun estimate(percent: Int, screen: ScreenMode, summaries: List<DailySummary>, chargeSummaries: List<ChargeSummary>, nowMs: Long): HybridEstimate =
        combine(BatteryEstimator.estimate(percent, screen, summaries, nowMs), ChargeEstimator.estimate(percent, screen, chargeSummaries, nowMs))

    fun combine(percentage: TimeEstimate, charge: ChargeEstimate): HybridEstimate {
        val p = percentage.seconds
        val c = charge.time.seconds
        val total = percentage.reliability + charge.time.reliability
        val pw = when { p == null -> 0.0; c == null -> 1.0; total > 0 -> percentage.reliability / total; else -> 0.5 }
        val cw = if (c == null) 0.0 else 1 - pw
        val result = if (p == null && c == null) null else ((p ?: 0) * pw + (c ?: 0) * cw).roundToLong()
        return HybridEstimate(percentage, charge, result, pw, cw)
    }
}
