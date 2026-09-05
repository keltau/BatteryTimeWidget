package com.keltau.batterytimewidget

import org.junit.Assert.*
import org.junit.Test

class BatteryModelTest {
    private val now = 20000L * BatteryConfig.DAY_MS + 43_200_000
    private val today = now / BatteryConfig.DAY_MS

    private fun reading(second: Long, percent: Int, mode: ScreenMode = ScreenMode.OFF, audio: Boolean = false, discharging: Boolean = true) =
        BatteryReading(now + second * 1000, second * 1000, percent, discharging, mode, audio)

    private fun summary(mode: ScreenMode, band: Int, seconds: Double, count: Int = 10, age: Long = 0) =
        DailySummary(today - age, mode, band, count, seconds * count, seconds * seconds * count)

    @Test fun firstPartialPercentIsNeverLearned() {
        val tracker = IntervalTracker()
        assertNull(tracker.observe(reading(0, 80)))
        assertEquals(Exclusion.WARMUP, tracker.observe(reading(30, 79))?.exclusion)
        val clean = tracker.observe(reading(630, 78))!!
        assertNull(clean.exclusion)
        assertEquals(600.0, clean.seconds, 0.0)
    }

    @Test fun repeatedBatteryEventsDoNotResetThePercentBoundary() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(200, 79))
        tracker.observe(reading(400, 79))
        assertEquals(600.0, tracker.observe(reading(660, 78))!!.seconds, 0.0)
    }

    @Test fun audioThatStartsAndStopsBetweenBatteryEventsIsExcluded() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(100, 79, audio = true))
        tracker.observe(reading(400, 79, audio = false))
        assertEquals(Exclusion.SCREEN_OFF_AUDIO, tracker.observe(reading(660, 78))?.exclusion)
        assertNull(tracker.observe(reading(1260, 77))?.exclusion)
    }

    @Test fun screenOffAudioCannotEnterScreenOnModelAfterScreenTransition() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80, audio = true))
        tracker.observe(reading(60, 79, audio = true))
        tracker.observe(reading(120, 79, ScreenMode.ON))
        assertEquals(Exclusion.SCREEN_OFF_AUDIO, tracker.observe(reading(300, 78, ScreenMode.ON))?.exclusion)
        val clean = tracker.observe(reading(600, 77, ScreenMode.ON))!!
        assertEquals(ScreenMode.ON, clean.screen)
        assertNull(clean.exclusion)
    }

    @Test fun audioWithScreenOnCanTrainScreenOn() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80, ScreenMode.ON, true))
        tracker.observe(reading(60, 79, ScreenMode.ON, true))
        assertNull(tracker.observe(reading(360, 78, ScreenMode.ON, true))?.exclusion)
    }

    @Test fun changingScreenBackDoesNotMakeMixedIntervalClean() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(120, 79, ScreenMode.ON))
        tracker.observe(reading(180, 79))
        assertEquals(Exclusion.SCREEN_CHANGE, tracker.observe(reading(660, 78))?.exclusion)
    }

    @Test fun chargingAndFirstPartialIntervalAfterUnpluggingAreExcluded() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(100, 79, discharging = false))
        tracker.observe(reading(200, 79))
        assertEquals(Exclusion.CHARGING, tracker.observe(reading(500, 78))?.exclusion)
        assertNull(tracker.observe(reading(1000, 77))?.exclusion)
    }

    @Test fun levelJumpsAndTheirUncertainBoundaryAreExcluded() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        assertEquals(Exclusion.LEVEL_JUMP, tracker.observe(reading(600, 75))?.exclusion)
        assertEquals(Exclusion.WARMUP, tracker.observe(reading(900, 74))?.exclusion)
        assertNull(tracker.observe(reading(1200, 73))?.exclusion)
    }

    @Test fun restartNeedsANewBoundary() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.reset()
        tracker.observe(reading(300, 79))
        assertEquals(Exclusion.WARMUP, tracker.observe(reading(600, 78))?.exclusion)
    }

    @Test fun clockChangeDoesNotBecomeDischargeTime() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(300, 79).copy(timeMs = now + 3_900_000))
        assertEquals(Exclusion.CLOCK_CHANGE, tracker.observe(reading(600, 78).copy(timeMs = now + 4_200_000))?.exclusion)
    }

    @Test fun unavailableAudioObservationFailsClosed() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        tracker.observe(reading(90, 79).copy(audioKnown = false))
        assertEquals(Exclusion.AUDIO_UNKNOWN, tracker.observe(reading(600, 78))?.exclusion)
    }

    @Test fun implausiblyShortIntervalIsExcluded() {
        val tracker = IntervalTracker()
        tracker.observe(reading(0, 80))
        tracker.observe(reading(60, 79))
        assertEquals(Exclusion.DURATION, tracker.observe(reading(61, 78))?.exclusion)
    }

    @Test fun noDataAndSparseDataDoNotInventEstimates() {
        assertNull(BatteryEstimator.estimate(80, ScreenMode.OFF, emptyList(), now).seconds)
        assertNull(BatteryEstimator.estimate(80, ScreenMode.OFF, listOf(summary(ScreenMode.OFF, 15, 600.0, 5)), now).seconds)
    }

    @Test fun targetIsZeroEvenWithoutHistory() {
        assertEquals(0L, BatteryEstimator.estimate(15, ScreenMode.ON, emptyList(), now).seconds)
        assertEquals(0L, BatteryEstimator.estimate(5, ScreenMode.OFF, emptyList(), now).seconds)
        assertNull(BatteryEstimator.estimate(-1, ScreenMode.ON, emptyList(), now).seconds)
    }

    @Test fun modesDoNotShareSamples() {
        val data = listOf(summary(ScreenMode.OFF, 10, 1200.0), summary(ScreenMode.ON, 10, 120.0))
        assertEquals(42000L, BatteryEstimator.estimate(50, ScreenMode.OFF, data, now).seconds)
        assertEquals(4200L, BatteryEstimator.estimate(50, ScreenMode.ON, data, now).seconds)
    }

    @Test fun integratesDifferentRatesAcrossBandsAndPartialBand() {
        val data = listOf(summary(ScreenMode.OFF, 3, 600.0, 100), summary(ScreenMode.OFF, 4, 1200.0, 100))
        val result = BatteryEstimator.estimate(23, ScreenMode.OFF, data, now)
        val low = (60000 + 4 * 900.0) / 104
        val high = (120000 + 4 * 900.0) / 104
        assertEquals(kotlin.math.round(5 * low + 3 * high).toLong(), result.seconds)
        assertTrue(result.bands[4].secondsPerPercent > result.bands[3].secondsPerPercent)
    }

    @Test fun recentWeeksHaveMoreInfluence() {
        val data = listOf(summary(ScreenMode.OFF, 3, 600.0, age = 14), summary(ScreenMode.OFF, 3, 1200.0))
        assertEquals(1000L, BatteryEstimator.estimate(16, ScreenMode.OFF, data, now).seconds)
    }

    @Test fun expiredAndFutureSummariesAreIgnored() {
        val data = listOf(summary(ScreenMode.OFF, 3, 600.0), summary(ScreenMode.OFF, 3, 6000.0, age = 56), summary(ScreenMode.OFF, 3, 6000.0, age = -1))
        val result = BatteryEstimator.estimate(16, ScreenMode.OFF, data, now)
        assertEquals(600L, result.seconds)
        assertEquals(10, result.sampleCount)
    }

    @Test fun coverageRequiresBothEvidenceAndDifferentDays() {
        val data = listOf(summary(ScreenMode.OFF, 3, 600.0), summary(ScreenMode.OFF, 3, 600.0, age = 1))
        val result = BatteryEstimator.estimate(25, ScreenMode.OFF, data, now)
        assertEquals(0.5, result.coverage, 0.0)
        assertFalse(result.established)
    }
}
