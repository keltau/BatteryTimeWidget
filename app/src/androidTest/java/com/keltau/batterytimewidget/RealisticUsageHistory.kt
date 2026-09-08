package com.keltau.batterytimewidget

import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object RealisticUsageHistory {
    data class History(val percentage: List<DischargeInterval>, val charge: List<ChargeInterval>)

    fun generate(now: Long): History {
        val first = (Math.floorDiv(now, BatteryConfig.DAY_MS) - 42) * BatteryConfig.DAY_MS
        val percentage = IntervalTracker()
        val counter = ChargeTracker()
        val raw = mutableListOf<DischargeInterval>()
        val charge = mutableListOf<ChargeInterval>()
        val random = Random(450042)
        val capacity = 4_500_000.0
        var remaining = capacity * 0.72
        var previousCurrent = -32_000
        var averageCurrent = previousCurrent.toDouble()
        var temperature = 245.0
        var topUp = false
        var nextBroadcast = 0
        var last: BatteryReading? = null

        for (minute in 0 until 42 * 1440) {
            if (minute > 0) remaining = (remaining + previousCurrent / 60.0).coerceAtMost(capacity)
            check(remaining > capacity * 0.05) { "Simulated phone would have shut down" }
            val day = minute / 1440
            val hourMinute = minute % 1440
            val shift = (day % 5 - 2) * 6
            val local = hourMinute - shift
            val weekend = day % 7 >= 5
            if (remaining < capacity * 0.16) topUp = true
            if (remaining >= capacity * 0.55) topUp = false
            val plugged = local in 1290 until 1410 || (day % 5 == 0 && local in 720 until 745) || topUp
            val on = local in 430 until 455 || local in 740 until 785 ||
                local in 900 until 912 || local in 1065 until 1090 ||
                local in 1150 until 1265 || local in 1380 until 1400 ||
                (local in 540 until 1020 && (local + day * 7) % 53 in 0..2) ||
                (weekend && local in 600 until 660)
            val audio = local in 480 until 520 || local in 1025 until 1060 || local in 1185 until 1230
            val audioKnown = !(day % 9 == 0 && local in 845 until 850)
            val counterAvailable = !(day % 6 == 0 && local in 797 until 807)
            val fraction = remaining / capacity
            val loadMa = (if (on) 540.0 + (local / 17 % 5) * 55 else if (audio) 155.0 else if (local < 420) 22.0 else 38.0) *
                (0.88 + day % 7 * 0.04) + random.nextInt(13) - 6
            val current = if (plugged) {
                minOf((1800.0 * ((1 - fraction) / 0.18).coerceIn(0.08, 1.0)) * 1000,
                    (capacity - remaining) * 60).roundToInt()
            } else (-loadMa * 1000).roundToInt()
            averageCurrent += (current - averageCurrent) * 0.18
            temperature += (235 + day % 4 * 4 + (if (on) 65 else 0) + (if (plugged) 55 else 0) - temperature) * 0.08
            val reading = BatteryReading(
                first + minute * 60_000L, (minute + 180L) * 60_000L,
                (fraction * 100).roundToInt(), !plugged, if (on) ScreenMode.ON else ScreenMode.OFF,
                audio, audioKnown, if (counterAvailable) (remaining / 25).roundToLong() * 25 else null,
                current, averageCurrent.roundToInt(),
                (3350 + 900 * fraction + current / 1000.0 * 0.10).roundToInt(), temperature.roundToInt(),
            )
            val previous = last
            val event = previous == null || minute >= nextBroadcast || previous.percent != reading.percent ||
                previous.screen != reading.screen || previous.audioActive != reading.audioActive ||
                previous.audioKnown != reading.audioKnown || previous.discharging != reading.discharging ||
                (previous.chargeUah == null) != (reading.chargeUah == null)
            if (event) {
                percentage.observe(reading)?.let(raw::add)
                counter.observe(reading)?.let(charge::add)
                last = reading
                nextBroadcast = minute + 2 + random.nextInt(5)
            }
            previousCurrent = current
        }
        return History(raw, charge)
    }
}
