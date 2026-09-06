package com.keltau.batterytimewidget

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.repeatedlyUntil
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import org.hamcrest.Matchers.allOf
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BatteryDataTest {
    private val now = System.currentTimeMillis()
    private val interval = DischargeInterval(now - 600_000, now, 51, 50, 600.0, ScreenMode.OFF, null)
    private val row = DailySummary(now / BatteryConfig.DAY_MS, ScreenMode.OFF, 10, 1, 600.0, 360000.0)
    private val snapshot = BatteryStore.Snapshot(listOf(row), listOf(interval))

    @Test fun overviewAndDeveloperPageOpen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.estimate_off)).check(matches(isDisplayed()))
            onView(withId(R.id.estimate_on)).check(matches(isDisplayed()))
            onView(withId(R.id.main_root)).perform(repeatedlyUntil(swipeUp(), hasDescendant(allOf(withId(R.id.open_dev), isCompletelyDisplayed())), 5))
            onView(withId(R.id.open_dev)).perform(click())
            onView(withId(R.id.close_dev)).check(matches(isDisplayed())).perform(click())
            onView(withId(R.id.estimate_off)).check(matches(isDisplayed()))
        }
    }

    @Test fun collectorObservesScreenAndPlaybackEvents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val samples = ShortArray(8000) { (1000 * kotlin.math.sin(2 * Math.PI * 440 * it / 8000)).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { BatteryMonitorService.start(it) }
                awaitCondition { BatteryMonitorService.running && BatteryMonitorService.latest?.audioKnown == true }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                awaitCondition { BatteryMonitorService.latest?.screen == ScreenMode.OFF }
                assertEquals(samples.size, track.write(samples, 0, samples.size))
                track.setLoopPoints(0, samples.size, -1)
                track.play()
                awaitCondition { BatteryMonitorService.latest?.audioActive == true }
                track.stop()
                awaitCondition { BatteryMonitorService.latest?.audioActive == false }
            } finally {
                track.release()
                BatteryMonitorService.pause(context)
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                awaitCondition { !BatteryMonitorService.running }
            }
        }
    }

    @Test fun learningResumesAfterInterruptionAndPausePersists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!BatteryMonitorService.isEnabled(context)) { "Pause learning before running restart tests." }
        val receiver = BatteryMonitorService.RestartReceiver()
        fun deliver(action: String) = instrumentation.runOnMainSync { receiver.onReceive(context, Intent(action)) }
        fun interrupted() {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            awaitCondition { !BatteryMonitorService.running }
            assertTrue(BatteryMonitorService.isEnabled(context))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                deliver(Intent.ACTION_BOOT_COMPLETED)
                deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
                instrumentation.waitForIdleSync()
                assertFalse(BatteryMonitorService.running)
                scenario.onActivity { BatteryMonitorService.start(it) }
                awaitCondition { BatteryMonitorService.running && BatteryMonitorService.latest != null }
                deliver(Intent.ACTION_BOOT_COMPLETED)
                assertTrue(BatteryMonitorService.running)
                interrupted()
                deliver(Intent.ACTION_BOOT_COMPLETED)
                awaitCondition { BatteryMonitorService.running && BatteryMonitorService.latest != null }
                interrupted()
                deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
                awaitCondition { BatteryMonitorService.running }
                interrupted()
                deliver("com.keltau.batterytimewidget.UNRELATED")
                instrumentation.waitForIdleSync()
                assertFalse(BatteryMonitorService.running)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitCondition { BatteryMonitorService.running }
                scenario.onActivity { it.startService(Intent(it, BatteryMonitorService::class.java).setAction(BatteryMonitorService.ACTION_STOP)) }
                awaitCondition { !BatteryMonitorService.running && !BatteryMonitorService.isEnabled(context) }
                deliver(Intent.ACTION_BOOT_COMPLETED)
                deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
                instrumentation.waitForIdleSync()
                assertFalse(BatteryMonitorService.running)
                scenario.onActivity { BatteryMonitorService.start(it) }
                awaitCondition { BatteryMonitorService.running }
                onView(withId(R.id.toggle_learning)).perform(scrollTo(), click())
                awaitCondition { !BatteryMonitorService.running && !BatteryMonitorService.isEnabled(context) }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.collection_status)).check(matches(withText(R.string.collection_paused)))
                assertFalse(BatteryMonitorService.running)
                assertFalse(BatteryMonitorService.isEnabled(context))
            }
        } finally {
            BatteryMonitorService.pause(context)
            awaitCondition { !BatteryMonitorService.running }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Expected system event was not observed", condition())
    }

    private fun encoded(data: BatteryStore.Snapshot = snapshot): ByteArray = ByteArrayOutputStream().also {
        DataTransfer.write(it, data, 50, now)
    }.toByteArray()

    @Test fun roundTripPreservesDataAndDoesNotTrustCachedEstimates() {
        val json = JSONObject(String(encoded(), Charsets.UTF_8))
        json.getJSONObject("estimates").put("OFF", JSONObject().put("secondsToTarget", 1))
        val restored = DataTransfer.read(ByteArrayInputStream(json.toString().toByteArray()), now)
        assertEquals(snapshot, restored)
        assertNull(BatteryEstimator.estimate(50, ScreenMode.OFF, restored.summaries, now).seconds)
    }

    @Test fun expiredDataIsNotImported() {
        val restored = DataTransfer.read(ByteArrayInputStream(encoded()), now + BatteryConfig.DAY_MS * 57)
        assertTrue(restored.raw.isEmpty())
        assertTrue(restored.summaries.isEmpty())
    }

    @Test fun rawExpiresBeforeSummaries() {
        val restored = DataTransfer.read(ByteArrayInputStream(encoded()), now + BatteryConfig.DAY_MS * 8)
        assertTrue(restored.raw.isEmpty())
        assertEquals(1, restored.summaries.size)
    }

    @Test fun unsupportedVersionAndInvalidDurationsAreRejected() {
        val version = JSONObject(String(encoded(), Charsets.UTF_8)).put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { DataTransfer.read(ByteArrayInputStream(version.toString().toByteArray()), now) }
        val duration = JSONObject(String(encoded(), Charsets.UTF_8))
        duration.getJSONArray("rawIntervals").getJSONObject(0).put("seconds", -1)
        assertThrows(IllegalArgumentException::class.java) { DataTransfer.read(ByteArrayInputStream(duration.toString().toByteArray()), now) }
    }

    @Test fun malformedAndOversizedFilesAreRejected() {
        assertThrows(org.json.JSONException::class.java) { DataTransfer.read(ByteArrayInputStream("{".toByteArray()), now) }
        assertThrows(IllegalArgumentException::class.java) { DataTransfer.read(ByteArrayInputStream(ByteArray(BatteryConfig.MAX_IMPORT_BYTES + 1)), now) }
    }

    @Test fun duplicateSummariesAndOverlappingIntervalsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DataTransfer.read(ByteArrayInputStream(encoded(snapshot.copy(summaries = listOf(row, row)))), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataTransfer.read(ByteArrayInputStream(encoded(snapshot.copy(raw = listOf(interval, interval)))), now)
        }
    }

    @Test fun databaseReplacementIsIdempotentAndAtomic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BatteryStore.get(context)
        val original = store.snapshot(now)
        try {
            store.replace(snapshot, now)
            store.replace(snapshot, now)
            assertEquals(snapshot, store.snapshot(now))
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                store.replace(snapshot.copy(summaries = listOf(row, row)), now)
            }
            assertEquals(snapshot, store.snapshot(now))
        } finally {
            store.replace(original, now)
        }
    }

    @Test fun recordingAggregatesOnceAndPrunesOldRaw() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BatteryStore.get(context)
        val original = store.snapshot(now)
        try {
            store.replace(BatteryStore.Snapshot(emptyList(), emptyList()), now)
            store.record(interval, now)
            assertEquals(snapshot, store.snapshot(now))
            val later = store.snapshot(now + 8 * BatteryConfig.DAY_MS)
            assertTrue(later.raw.isEmpty())
            assertEquals(1, later.summaries.size)
            assertTrue(store.snapshot(now + 57 * BatteryConfig.DAY_MS).summaries.isEmpty())
        } finally {
            store.replace(original, now)
        }
    }
}
