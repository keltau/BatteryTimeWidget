# Application fixture checks

Run on 2026-09-05T07:47:08.771359Z using the Android emulator, actual import confirmation dialog, MainActivity.Model, SQLite store and overview views.

- PASS 01-realistic-six-weeks.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 01-realistic-six-weeks.json: app export round trip.
- PASS 02-constant-rates.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 02-constant-rates.json: app export round trip.
- PASS repeated constant-rate import: no duplicate samples.
- PASS 03-recent-usage-change.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 03-recent-usage-change.json: app export round trip.
- PASS 04-five-samples-per-mode.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 04-five-samples-per-mode.json: app export round trip.
- PASS 05-screen-on-only.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 05-screen-on-only.json: app export round trip.
- PASS 06-excluded-audio-and-other-intervals.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 06-excluded-audio-and-other-intervals.json: app export round trip.
- PASS 07-raw-expired-summaries-retained.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 07-raw-expired-summaries-retained.json: app export round trip.
- PASS 08-summary-retention-boundary.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 08-summary-retention-boundary.json: app export round trip.
- PASS 09-entire-backup-expired.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 09-entire-backup-expired.json: app export round trip.
- PASS 10-empty-history.json: confirmation, stored dataset and both overview estimates at 80%.
- PASS 10-empty-history.json: app export round trip.
- PASS cancelled replacement: existing history preserved.
- PASS invalid-unsupported-version.json: application rejects file and preserves existing history.
- PASS invalid-duplicate-summary.json: application rejects file and preserves existing history.
- PASS invalid-overlapping-intervals.json: application rejects file and preserves existing history.
- PASS actual device level 15%: both overview estimates are zero.

All 26 checks passed. The original database and real battery reporting were restored after the run. File access in this automated test uses app-owned file URIs; the system document picker is checked separately.

## Manual application checks

Verified on the same Android 17 emulator on 2026-09-05:

- PASS imported 02-constant-rates.json from Downloads using the system document picker and confirmed replacement.
- PASS overview at 80%: screen off 10 h 50 min, screen on 2 h 10 min, matching the analytic reference.
- PASS installed 2×1 widget: only the two labels and estimates (10h 50m and 2h 10m), both fully visible without clipping.

The manual checks left the constant-rate fixture loaded in the test emulator. Real battery reporting was restored afterward.

## Build checks

The debug app and instrumentation APKs built successfully. All 19 unit tests passed. Android lint reported no errors and three existing compatibility warnings for API 31 widget attributes.
