# Realistic daily usage sample

[11-realistic-daily-usage.json](11-realistic-daily-usage.json) is an additional valid import. Generated 2026-09-06T00:45:44.237Z.

Six weeks of a synthetic 4,500 mAh phone: irregular screen sessions, overnight idle, music, evening charging, partial top-ups, variable load/voltage/temperature and brief sensor outages. Both collectors process the same battery events and learn independently. This is a simulation, not a real person's recorded data. Seed 450042 reproduces the usage pattern. Day schedules use UTC hours as a fixed local-day stand-in; the data covers the 42 complete days before generation.

The battery starts at 72%. Charge is integrated continuously from net current, with a 25 µAh counter resolution and rounded integer percentage. Short checks, longer evening use, additional weekend activity, two music sessions, evening charging with taper near full and occasional partial top-ups produce different learning windows for each model. Voltage changes with state of charge and load; temperature and average current change gradually. Brief counter and audio-status outages exercise exclusions.

One-minute integration emits simulated battery broadcasts every 2–6 minutes and at percentage, screen, audio, charging and sensor-availability changes. Both production trackers receive exactly the same event stream. Only accepted intervals create summaries; the export retains six weeks of learning and the most recent seven days of raw diagnostics.

Short screen checks interrupt many full-percentage quiet windows. The percentage model therefore learns much of its screen-off history during long overnight idle, while the counter model also captures shorter daytime idle. The screen-off estimates can differ substantially and remain provisional after six weeks because battery-band coverage is sparse.

## Expected at 80% on the generation date

| Screen | Percentage | Counter | Combined/widget | P / C weight |
| --- | --- | --- | --- | --- |
| OFF | 5 d 1 h | 3 d 11 h | 4 d 0 h | 33% / 67% |
| ON | 4 h 20 min | 4 h 21 min | 4 h 20 min | 53% / 47% |

Exact seconds, scores, coverage and sample counts at 0/15/16/25/50/80/100% are in [realistic-usage-expected-results.json](realistic-usage-expected-results.json). This report is not an import file. The app uses the phone's current percentage; neither time is a mixed-use daily-runtime forecast. Missing bands are estimated from learned rates.

Export your own history, pause learning, import file 11 and confirm replacement. Both models and their combination should appear in the overview; the widget shows the combination. Restore your backup afterwards. Raw diagnostics age out after seven days and daily learning after 56 days, so later imports may show fewer records.

## Run only this sample

Build/install the debug app and test APK, then select an emulator with learning paused:

```powershell
adb -s SERIAL shell am instrument -w -e class com.keltau.batterytimewidget.RealisticUsageImportTest com.keltau.batterytimewidget.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL pull /sdcard/Android/data/com.keltau.batterytimewidget/files/realistic-usage-fixture/. test-data/
```

This generates only file 11 and its separate report/screenshots; it does not invoke ImportFixturesTest or write any of its files. See [realistic-usage-test-results.md](realistic-usage-test-results.md) for the actual run. Simulated current/voltage are diagnostic inputs; the algorithms learn from percentage changes and charge-counter changes, respectively.
