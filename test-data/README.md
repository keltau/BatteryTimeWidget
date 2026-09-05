# Importable battery test data

Synthetic fixtures generated and verified with the app's DataTransfer and BatteryEstimator on 2026-09-05T07:46:19.095Z. None of these files contains real usage data.

## Use

1. Export your current history if you want to keep it, then pause learning so incoming samples do not change the comparison.
2. Copy the numbered JSON files to your phone's Downloads folder. In Battery Time, choose Import data, select one file, and confirm replacement.
3. Begin with 01-realistic-six-weeks.json. Check the overview, widget, and Developer insights. Resize the widget to check compact duration labels.
4. Each import replaces the previous dataset. Re-importing the same file must leave sample counts and estimates unchanged.
5. Restore your own export and resume learning when finished.

The phone's current battery level determines the displayed times. Import does not change that level. The JSON batteryPercent is 80 only to provide a reference snapshot; its cached estimates are not imported as live predictions. While plugged in, the overview describes use after unplugging and the widget may show that charging status instead of its evidence label.

## Scenarios

Times below assume the phone is at 80% on the generation date. Rounded display values are followed by exact seconds in expected-results.json.

| Import file | Screen off | Screen on | Samples off/on after import | Raw intervals after import |
| --- | --- | --- | --- | --- |
| [01-realistic-six-weeks.json](01-realistic-six-weeks.json) | 22 h 37 min | 3 h 8 min | 1400 / 1428 | 565 |
| [02-constant-rates.json](02-constant-rates.json) | 10 h 50 min | 2 h 10 min | 1176 / 1176 | 1157 |
| [03-recent-usage-change.json](03-recent-usage-change.json) | 7 h 13 min | 3 h 15 min | 2352 / 2352 | 1116 |
| [04-five-samples-per-mode.json](04-five-samples-per-mode.json) | Learning | Learning | 5 / 5 | 13 |
| [05-screen-on-only.json](05-screen-on-only.json) | Learning | 2 h 10 min | 0 / 588 | 510 |
| [06-excluded-audio-and-other-intervals.json](06-excluded-audio-and-other-intervals.json) | 10 h 50 min | 2 h 10 min | 1176 / 1176 | 1311 |
| [07-raw-expired-summaries-retained.json](07-raw-expired-summaries-retained.json) | 10 h 50 min | 2 h 10 min | 1176 / 1176 | 0 |
| [08-summary-retention-boundary.json](08-summary-retention-boundary.json) | 10 h 50 min | 2 h 10 min | 252 / 252 | 0 |
| [09-entire-backup-expired.json](09-entire-backup-expired.json) | Learning | Learning | 0 / 0 | 0 |
| [10-empty-history.json](10-empty-history.json) | Learning | Learning | 0 / 0 | 0 |

- **01-realistic-six-weeks:** Start here: six weeks of varying use, different discharge speeds across battery levels, and discarded mixed/audio intervals.
- **02-constant-rates:** Analytic reference: screen off is 600 seconds per percent; screen on is 120 seconds per percent. Both modes have full coverage.
- **03-recent-usage-change:** The latest 14 days shorten the time per percent from 600 to 300 seconds off and 300 to 120 seconds on, representing faster battery drain. The older 14-day block has half the recent block's weight. Expected weighted rates: 400 seconds off, 180 seconds on.
- **04-five-samples-per-mode:** Only five accepted drops per mode. Both values stay Learning above 15%, regardless of the observed rates.
- **05-screen-on-only:** Seven days of screen-on history. Screen on has a numeric estimate; screen off stays Learning above 15%.
- **06-excluded-audio-and-other-intervals:** Same summaries as 02, with extra excluded screen-off audio, charging, mixed-state, unknown-audio, level-jump, warmup, duration and clock-change intervals. All estimates must match 02 exactly.
- **07-raw-expired-summaries-retained:** A backup exported ten days ago. Its raw intervals disappear on import; all 14 summary days remain, with the same constant-rate estimates as 02.
- **08-summary-retention-boundary:** A backup exported 52 days ago. Only summary ages 53, 54 and 55 days survive: 252 samples per mode across three days. Raw history is empty; estimates are provisional with zero well-sampled band coverage.
- **09-entire-backup-expired:** A backup exported 60 days ago. All raw intervals and summaries expire on import. Both modes return to Learning above 15%.
- **10-empty-history:** An empty valid backup. Use it to check the initial Learning state and empty developer page.

## Known numerical results

For 02, 06 and 07, at percentage P above 15, screen-off time is (P - 15) × 600 seconds and screen-on time is (P - 15) × 120 seconds. For 03, those rates are 400 and 180 seconds. These formulas are checked against the actual estimator during generation.

| Battery | Constant off | Constant on | Recent usage change off | Recent usage change on |
| --- | --- | --- | --- | --- |
| 15% | 0 min | 0 min | 0 min | 0 min |
| 16% | 10 min | 2 min | 7 min | 3 min |
| 25% | 1 h 40 min | 20 min | 1 h 7 min | 30 min |
| 50% | 5 h 50 min | 1 h 10 min | 3 h 53 min | 1 h 45 min |
| 80% | 10 h 50 min | 2 h 10 min | 7 h 13 min | 3 h 15 min |
| 100% | 14 h 10 min | 2 h 50 min | 9 h 27 min | 4 h 15 min |

At or below 15%, every scenario must show zero for both modes, including empty history. File 01 has intentionally uneven band rates: look at Developer insights for faster discharge near 15% and slower discharge near 50–60%. Expected values, coverage, days and counts at 0%, 15%, 16%, 25%, 50%, 80% and 100% are in expected-results.json; that file is a test report, not an import file.

## Exclusion and retention checks

File 06 adds 30-second screen-off audio drops and other rejected intervals to 02 without altering its summaries. Developer insights should show the extra rejection reasons, while all estimates and accepted-sample counts remain identical. Raw intervals are already classified in this backup format: importing them does not replay audio events or retrain summaries. Use the existing BatteryModelTest and collectorObservesScreenAndPlaybackEvents test to check live event classification and playback detection.

Files 07–09 deliberately have older export dates. The reader first validates each historical backup, then applies retention relative to the import date. File 07 retains summaries but no raw rows. File 08 keeps only three summary days on the generation date; one more expires each UTC day, so this boundary case needs regeneration if tested later. File 09 imports as empty history.

All fixtures age naturally. Coverage, counts and predictions can change as weights decay or rows expire. Regenerate for reproducible comparisons on a later date; do not only change exportedAtMs, because row dates and retention must remain consistent.

## Files that must be rejected

Import each invalid-* file while a valid dataset is loaded. The app must show the error below and preserve the current history.

- **invalid-unsupported-version.json:** Unsupported backup format or version.
- **invalid-duplicate-summary.json:** Duplicate daily summary.
- **invalid-overlapping-intervals.json:** Overlapping raw intervals.

## Regenerate

The generator is app/src/androidTest/java/com/keltau/batterytimewidget/ImportFixturesTest.kt. It does not modify the app database. With an emulator or development phone connected, run these commands from the project root; select the intended device with adb -s SERIAL if more than one is connected:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class 'com.keltau.batterytimewidget.ImportFixturesTest#generateAndValidateImportFixtures' com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.keltau.batterytimewidget/files/import-fixtures/. test-data/
```

The generator checks exact constant/recency formulas, audio-pair equality at every battery percentage, learning thresholds, retention boundaries and all three rejection errors. Values in the JSON estimates sections come directly from the production estimator.

To repeat the application checks on an emulator, run the same instrumentation command with #importFixturesThroughApplication instead. That test regenerates the fixtures, exercises the import confirmation dialog and overview, verifies persisted data and export round trips, and restores the original database and battery reporting afterward. Pause learning first. Its report is application-test-results.md in the same output directory. Use the SDK's platform-tools/adb executable if adb is not on your PATH.
