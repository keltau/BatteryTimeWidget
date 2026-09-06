# Importable battery test data

**Eleven import files total: nine valid scenarios and two rejection examples.** All histories are synthetic. Files 01–10 and their results were generated 2026-09-05T20:18:56.805Z and are preserved. File 11 is an additional realistic daily-usage sample with its own generation date, expectations and test results.

For the new sample, use [11-realistic-daily-usage.json](11-realistic-daily-usage.json). Its [guide](realistic-usage-guide.md) describes the six-week simulation and expected values; its [separate test report](realistic-usage-test-results.md) covers only file 11. The original ten tests were not rerun when adding it.

## Use

Export your own history first, pause learning, then select one numbered JSON file with Import data and confirm replacement. Each import replaces both histories. The current phone percentage determines times; 80% is only the export reference. Restore your own backup and resume learning afterwards. Files 09 and 10 must be rejected without replacing data.

For the original files below, at 80% on their generation date, P = percentage, C = counter, B = blended/widget. Detailed seconds, scores, weights, coverage, days and counts at 0/15/16/25/50/80/100% are in expected-results.json (a report, not an import file). File 11 has its own [expected-results report](realistic-usage-expected-results.json).

| File | Screen off P / C / B | Screen on P / C / B |
| --- | --- | --- |
| [01-mature-agreement.json](01-mature-agreement.json) | 10 h 50 min / 10 h 50 min / 10 h 50 min | 2 h 10 min / 2 h 10 min / 2 h 10 min |
| [02-opposing-usage-changes.json](02-opposing-usage-changes.json) | 7 h 13 min / 9 h 2 min / 8 h 7 min | 3 h 15 min / 3 h 37 min / 3 h 26 min |
| [03-realistic-bands-and-exclusions.json](03-realistic-bands-and-exclusions.json) | 11 h 47 min / 11 h 47 min / 11 h 47 min | 2 h 34 min / 2 h 34 min / 2 h 34 min |
| [04-mixed-learning-thresholds.json](04-mixed-learning-thresholds.json) | Learning / 21 h 38 min / 21 h 38 min | 2 h 10 min / Learning / 2 h 10 min |
| [05-legacy-percentage-only.json](05-legacy-percentage-only.json) | 10 h 50 min / Learning / 10 h 50 min | 2 h 10 min / Learning / 2 h 10 min |
| [06-retention-boundaries.json](06-retention-boundaries.json) | 10 h 50 min / 10 h 50 min / 10 h 50 min | 2 h 10 min / 2 h 10 min / 2 h 10 min |
| [07-fully-expired-to-empty.json](07-fully-expired-to-empty.json) | Learning / Learning / Learning | Learning / Learning / Learning |
| [08-reliability-cross-over.json](08-reliability-cross-over.json) | 6 h 38 min / 6 h 30 min / 6 h 33 min | 2 h 10 min / 2 h 13 min / 2 h 11 min |

- **01-mature-agreement:** Both algorithms agree: 600 seconds per percent off, 120 on; mature coverage and a genuine blend.
- **02-opposing-usage-changes:** Recent usage is weighted twice as strongly as the preceding fortnight. Percentage rates are 400/180 seconds per percent; counter rates are 500/200. Both estimates must contribute.
- **03-realistic-bands-and-exclusions:** Six weeks of varying battery-band rates, different screen states and every exclusion reason. Removing excluded raw rows must not change either estimate, weight or learning count.
- **04-mixed-learning-thresholds:** Off: five percentage drops are insufficient, but three sub-percent counter windows are ready. On: six percentage drops are ready, but two counter windows are insufficient. Each screen state uses the appropriate model alone.
- **05-legacy-percentage-only:** A real version 1 backup. It restores percentage learning, clears previous counter history and uses 100% percentage weight.
- **06-retention-boundaries:** One-day-old export with both models at summary ages 55/56 and raw endpoints just before, exactly at and after the rolling seven-day cutoff. Expired raw rows must not erase retained learning.
- **07-fully-expired-to-empty:** Sixty-day-old backup: both histories expire completely. Both modes show Learning above the target and zero at/below 15%, including after replacing mature data.
- **08-reliability-cross-over:** Off: variable percentage history gives the stable counter model more influence. On: stable percentage history outweighs variable counter history. Neither ready model is switched off.
- **09-invalid-version:** unsupported version 99.
- **10-invalid-counter-reset:** an accepted counter interval increases charge.
- **11-realistic-daily-usage:** six weeks of shared battery events from a simulated 4,500 mAh phone, including irregular screen sessions, overnight idle, music, evening charging, partial top-ups, changing electrical readings and brief sensor outages. The actual percentage and charge collectors build their own learning histories from those events.

## Rigor and limits

01 and 02 have independent closed-form oracles checked for every percentage 0–100. In 02, recent history is twice as influential as the preceding fortnight: P off/on = 400/180 seconds per percent; C off/on = 500/200. The blend must lie between the corresponding estimates. 08 reverses reliability preference between modes. 04 tests different learning thresholds and mode isolation.

03 varies battery-band rates and contains every preclassified exclusion reason. Reimporting it with excluded rows removed must leave predictions and weights unchanged. Live event classification is covered by the model/collector tests; importing raw rows does not retrain summaries.

06 combines retention boundaries. A raw row exactly seven days old survives at generation and expires one millisecond later; later imports can retain fewer rows. Summary age 55 survives, age 56 does not. 07 replaces mature history with empty histories after expiry. Both models return zero at/below 15%, even when empty. Fixtures age naturally; regenerate for reproducible later comparisons.

Fifteen additional malformed variants are tested through the app without permanent files: duplicates/overlaps in both models, invalid numbers/variances/calibration, missing summaries, unknown counter model and future dates. Tests also cover cancellation, ignored cached predictions, export/re-import idempotence, developer counts and live widget updates at 15/50/80%. The system document picker itself is outside this automated run.

## Regenerate and exercise

To regenerate and test **only file 11**, follow [Run only this sample](realistic-usage-guide.md#run-only-this-sample). It uses RealisticUsageImportTest and a separate output folder, preserving files 01–10 and both original reports.

The following commands are for the **original ten files only**. ImportFixturesTest#importFixturesThroughApplication generates those files with the production serializer, validates them, imports all ten files in the app and writes application-test-results.md. Use an emulator with learning paused, then copy its import-fixtures folder. These tests were not rerun when adding file 11.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class 'com.keltau.batterytimewidget.ImportFixturesTest#importFixturesThroughApplication' com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.keltau.batterytimewidget/files/import-fixtures/. test-data/
```

Select the intended emulator with adb -s SERIAL if necessary. If the Windows wrapper cannot find Java, invoke the installed JDK's java.exe with -jar gradle/wrapper/gradle-wrapper.jar and the same tasks. The catalog is ImportFixtureCatalog.kt; the app/storage/widget runner is ImportFixturesTest.kt.
