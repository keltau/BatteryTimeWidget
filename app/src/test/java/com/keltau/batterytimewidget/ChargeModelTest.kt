package com.keltau.batterytimewidget

import org.junit.Assert.*
import org.junit.Test

class ChargeModelTest {
    private val now = 20_000L * BatteryConfig.DAY_MS + 43_200_000
    private fun reading(s: Long, q: Long? = 2_000_000 - s * 10, mode: ScreenMode = ScreenMode.ON, audio: Boolean = false) =
        BatteryReading(now + s * 1000, s * 1000, 50, true, mode, audio, chargeUah = q)

    private fun row(seconds: Double = 600.0, q: Double = 40_000.0, age: Long = 0, count: Int = 3, mode: ScreenMode = ScreenMode.ON) =
        ChargeSummary(now / BatteryConfig.DAY_MS - age, mode, 10, count, seconds, q, q * q / seconds,
            4_000_000.0 * seconds, 4_000_000.0 * 4_000_000.0 * seconds)

    @Test fun subPercentSamplesCloseAtScreenChangesInThePreviousState() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        val sample = tracker.observe(reading(60, mode = ScreenMode.OFF))!!
        assertNull(sample.exclusion)
        assertEquals(ScreenMode.ON, sample.start.screen)
        assertEquals(600.0, sample.chargeUah, 0.0)
        assertEquals(60.0, sample.seconds, 0.0)
        assertEquals(sample.start.percent, sample.end.percent)
        val next = tracker.observe(reading(120))!!
        assertNull(next.exclusion)
        assertEquals(ScreenMode.OFF, next.start.screen)
        assertEquals(sample.end, next.start)
    }

    @Test fun duplicatesAndTinyChangesAccumulateWithoutInventingEvidence() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        assertNull(tracker.observe(reading(0)))
        assertNull(tracker.observe(reading(10)))
        assertNull(tracker.observe(reading(30, q = 1_999_900)))
        val sample = tracker.observe(reading(60))!!
        assertEquals(60.0, sample.seconds, 0.0)
        assertEquals(600.0, sample.chargeUah, 0.0)
    }

    @Test fun tinyTransitionCannotLeakIntoTheNextScreenMode() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        assertEquals(ChargeExclusion.TOO_SMALL, tracker.observe(reading(10, mode = ScreenMode.OFF))?.exclusion)
        val sample = tracker.observe(reading(70, mode = ScreenMode.OFF))!!
        assertEquals(60.0, sample.seconds, 0.0)
        assertEquals(ScreenMode.OFF, sample.start.screen)
    }

    @Test fun staleCounterAtTransitionRequiresAFreshBoundary() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        assertEquals(ChargeExclusion.TOO_SMALL, tracker.observe(reading(60, q = 2_000_000, mode = ScreenMode.OFF))?.exclusion)
        assertEquals(ChargeExclusion.WARMUP, tracker.observe(reading(120, mode = ScreenMode.OFF))?.exclusion)
        val clean = tracker.observe(reading(180, mode = ScreenMode.OFF))!!
        assertNull(clean.exclusion)
        assertEquals(60.0, clean.seconds, 0.0)
    }

    @Test fun screenOffAudioStartClosesQuietTimeAndStopStartsFreshWindow() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0, mode = ScreenMode.OFF))
        assertNull(tracker.observe(reading(60, mode = ScreenMode.OFF, audio = true))!!.exclusion)
        assertEquals(ChargeExclusion.SCREEN_OFF_AUDIO, tracker.observe(reading(120, mode = ScreenMode.OFF))?.exclusion)
        assertNull(tracker.observe(reading(180, mode = ScreenMode.OFF))!!.exclusion)
    }

    @Test fun audioAffectedTimeCannotLeakIntoScreenOn() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0, mode = ScreenMode.OFF, audio = true))
        assertEquals(ChargeExclusion.SCREEN_OFF_AUDIO, tracker.observe(reading(60))?.exclusion)
        assertNull(tracker.observe(reading(120, audio = true))!!.exclusion)
    }

    @Test fun unavailableCounterAndUnknownAudioDoNotTrain() {
        for (bad in listOf(reading(60, q = null), reading(60).copy(audioKnown = false))) {
            val tracker = ChargeTracker()
            tracker.observe(reading(0))
            assertNotNull(tracker.observe(bad)!!.exclusion)
            assertNotNull(tracker.observe(reading(120))!!.exclusion)
            assertNull(tracker.observe(reading(180))!!.exclusion)
        }
    }

    @Test fun chargingCounterResetAndImplausibleJumpsDoNotTrain() {
        for (bad in listOf(reading(60).copy(discharging = false), reading(60, q = 2_100_000),
            reading(60, q = 1_000_000), reading(60).copy(percent = 45))) {
            val tracker = ChargeTracker()
            tracker.observe(reading(0))
            assertNotNull(tracker.observe(bad)!!.exclusion)
        }
    }

    @Test fun clocksAndResetNeverBridgeDowntime() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        assertEquals(ChargeExclusion.CLOCK_CHANGE, tracker.observe(reading(60).copy(timeMs = now + 1_000_000))?.exclusion)
        tracker.reset()
        assertNull(tracker.observe(reading(3600)))
        assertEquals(60.0, tracker.observe(reading(3660))!!.seconds, 0.0)
    }

    @Test fun frozenCounterDoesNotBecomeZeroDrainPrediction() {
        val tracker = ChargeTracker()
        tracker.observe(reading(0))
        assertNull(tracker.observe(reading(600, q = 2_000_000)))
        assertNull(tracker.observe(reading(1200, q = 2_000_000)))
        assertEquals(ChargeExclusion.DURATION, tracker.observe(reading(90_000, q = 2_000_000))?.exclusion)
        assertNull(ChargeEstimator.estimate(50, ScreenMode.ON, emptyList(), now).time.seconds)
    }

    @Test fun chargeModelCanLearnBeforeAnyFullPercentDrop() {
        val estimate = HybridEstimator.estimate(50, ScreenMode.ON, emptyList(), listOf(row(180.0, 6_000.0)), now)
        assertNull(estimate.percentage.seconds)
        assertEquals(42000L, estimate.charge.time.seconds)
        assertEquals(estimate.charge.time.seconds, estimate.seconds)
        assertEquals(1.0, estimate.chargeWeight, 0.0)
        assertFalse(estimate.established)
    }

    @Test fun chargeRequiresDurationAmountAndSeveralWindows() {
        for (data in listOf(row(119.0, 6_000.0), row(180.0, 3_000.0), row(count = 2))) {
            assertNull(ChargeEstimator.estimate(50, ScreenMode.ON, listOf(data), now).time.seconds)
        }
    }

    @Test fun eventsDoNotArtificiallyIncreaseReliability() {
        val a = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(row(count = 3)), now)
        val b = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(row(count = 30)), now)
        assertEquals(a.time.seconds, b.time.seconds)
        assertEquals(a.time.reliability, b.time.reliability, 0.0)
    }

    @Test fun variabilityAndAgeReduceInfluence() {
        val a = row()
        val clean = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(a), now)
        val noisy = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(a.copy(squaredRateSeconds = a.squaredRateSeconds * 5)), now)
        val calibration = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(a.copy(squaredCapacitySeconds = a.squaredCapacitySeconds * 2)), now)
        val old = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(row(age = 14)), now)
        assertTrue(clean.time.reliability > noisy.time.reliability)
        assertTrue(clean.time.reliability > calibration.time.reliability)
        assertTrue(clean.time.reliability > old.time.reliability)
    }

    @Test fun chargeUsesTimeWeightedRatesAndKeepsModesSeparate() {
        val a = row(seconds = 600.0, q = 40_000.0)
        val b = row(seconds = 120.0, q = 40_000.0, age = 1)
        val time = ChargeEstimator.estimate(50, ScreenMode.ON, listOf(a, b), now).time.seconds!!
        assertTrue(time in 4201..20999)
        assertNull(ChargeEstimator.estimate(50, ScreenMode.OFF, listOf(a, b), now).time.seconds)
    }

    @Test fun expiredFutureUnknownLevelAndTargetBehaveConsistently() {
        assertNull(ChargeEstimator.estimate(50, ScreenMode.ON, listOf(row(age = 56), row(age = -1)), now).time.seconds)
        for (percent in listOf(-1, 101)) assertNull(ChargeEstimator.estimate(percent, ScreenMode.ON, listOf(row()), now).time.seconds)
        for (percent in listOf(0, 15)) assertEquals(0L, HybridEstimator.estimate(percent, ScreenMode.ON, emptyList(), emptyList(), now).seconds)
    }

    @Test fun blendIsNormalizedAndUsesBothIndependentTimes() {
        val percent = TimeEstimate(1000, 0.5, 10, 3, emptyList(), 0.75)
        val charge = ChargeEstimate(TimeEstimate(2000, 0.5, 10, 3, emptyList(), 0.25), 4_000_000.0, 600.0, 1.0)
        val result = HybridEstimator.combine(percent, charge)
        assertEquals(1250L, result.seconds)
        assertEquals(0.75, result.percentageWeight, 0.0)
        assertEquals(0.25, result.chargeWeight, 0.0)
        val onlyPercent = HybridEstimator.combine(percent, charge.copy(time = charge.time.copy(seconds = null, reliability = 0.0)))
        assertEquals(1000L, onlyPercent.seconds)
        assertEquals(1.0, onlyPercent.percentageWeight, 0.0)
        val neither = HybridEstimator.estimate(50, ScreenMode.ON, emptyList(), emptyList(), now)
        assertNull(neither.seconds)
        assertEquals(0.0, neither.chargeWeight + neither.percentageWeight, 0.0)
    }

    @Test fun percentageVarianceAlsoReducesBlendReliability() {
        val a = DailySummary(now / BatteryConfig.DAY_MS, ScreenMode.ON, 10, 10, 6000.0, 3_600_000.0)
        val clean = BatteryEstimator.estimate(50, ScreenMode.ON, listOf(a), now)
        val noisy = BatteryEstimator.estimate(50, ScreenMode.ON, listOf(a.copy(squaredSeconds = a.squaredSeconds * 3)), now)
        assertEquals(clean.seconds, noisy.seconds)
        assertTrue(clean.reliability > noisy.reliability)
    }
}
