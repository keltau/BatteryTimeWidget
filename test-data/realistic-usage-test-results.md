# Realistic usage sample test results

Run: 2026-09-06T00:46:39.312713Z. Result: PASS.

Android 17, API 37, sdk_gphone16k_x86_64. Only 11-realistic-daily-usage.json was tested; previous tests were not rerun.

- PASS Fixture 11 serializes and imports both collector histories without loss; original ten fixtures are not generated or exercised.
- PASS Six weeks of shared events produce distinct learning windows, accepted sub-percent screen transitions, charging/audio/unknown-sensor exclusions, varied electrical telemetry and irregular sample durations.
- PASS During the first simulated hour the counter model is ready while the percentage model is still learning; the combined value uses 100% counter weight.
- PASS At all 101 battery levels in both modes, estimates are monotonic, weights normalized and the blend lies between the two contributing estimates. At 80%, the models differ and both remain within workload-based physical bounds.
- PASS Removing excluded diagnostic rows leaves both estimates and learning weights unchanged at every battery level.
- PASS The app's import confirmation replaces both SQLite histories exactly.
- PASS Overview component estimates, displayed reliability weights and combined values match the live AppWidgetHost at 15%, 50% and 80%, for both screen states.
- PASS App export and re-import preserve summaries, raw intervals, nullable telemetry and exclusions for both algorithms.
- PASS Developer display reports separate percentage/counter raw and learned counts.
- PASS Original app data and battery simulation restored; temporary widget removed.

10 grouped checks completed. This synthetic history checks application behavior, not measured battery accuracy. Import uses an app-owned URI; the system document picker is outside this run.
