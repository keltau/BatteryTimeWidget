# Import fixture application test results

Run: 2026-09-05T20:20:55.840547Z. Result: PASS.

Android 17, API 37, sdk_gphone16k_x86_64. Eight valid and two invalid import files.

- PASS Independent analytic agreement/recency oracles pass at every percentage 0–100 in both modes for both algorithms. Blends, coverage, learning thresholds and opposite reliability preferences pass.
- PASS Both models enforce inclusive seven-day raw and 56-day summary boundaries; raw expiry retains learning, and all history expires later.
- PASS 01-mature-agreement.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 01-mature-agreement.json: app export and re-import are lossless and idempotent.
- PASS 01-mature-agreement.json: developer statistics show correct separate model counts.
- PASS 02-opposing-usage-changes.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 02-opposing-usage-changes.json: app export and re-import are lossless and idempotent.
- PASS 02-opposing-usage-changes.json: developer statistics show correct separate model counts.
- PASS 03-realistic-bands-and-exclusions.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 03-realistic-bands-and-exclusions.json: app export and re-import are lossless and idempotent.
- PASS 03-realistic-bands-and-exclusions.json: developer statistics show correct separate model counts.
- PASS 04-mixed-learning-thresholds.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 04-mixed-learning-thresholds.json: app export and re-import are lossless and idempotent.
- PASS 04-mixed-learning-thresholds.json: developer statistics show correct separate model counts.
- PASS 05-legacy-percentage-only.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 05-legacy-percentage-only.json: app export and re-import are lossless and idempotent.
- PASS 05-legacy-percentage-only.json: developer statistics show correct separate model counts.
- PASS 06-retention-boundaries.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 06-retention-boundaries.json: app export and re-import are lossless and idempotent.
- PASS 06-retention-boundaries.json: developer statistics show correct separate model counts.
- PASS 07-fully-expired-to-empty.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 07-fully-expired-to-empty.json: app export and re-import are lossless and idempotent.
- PASS 07-fully-expired-to-empty.json: developer statistics show correct separate model counts.
- PASS 08-reliability-cross-over.json: confirmation and both stored histories match; component estimates, blend weights and live widget values pass at 15%, 50% and 80%.
- PASS 08-reliability-cross-over.json: app export and re-import are lossless and idempotent.
- PASS 08-reliability-cross-over.json: developer statistics show correct separate model counts.
- PASS Cancelled replacement preserves both histories.
- PASS 09-invalid-version.json: app rejects import and preserves both histories.
- PASS 10-invalid-counter-reset.json: app rejects import and preserves both histories.
- PASS Transient malformed variant rejected without data loss: duplicate percentage summary.
- PASS Transient malformed variant rejected without data loss: duplicate counter summary.
- PASS Transient malformed variant rejected without data loss: duplicate percentage raw.
- PASS Transient malformed variant rejected without data loss: duplicate counter raw.
- PASS Transient malformed variant rejected without data loss: percentage overlap.
- PASS Transient malformed variant rejected without data loss: counter overlap.
- PASS Transient malformed variant rejected without data loss: fractional integer.
- PASS Transient malformed variant rejected without data loss: negative percentage variance.
- PASS Transient malformed variant rejected without data loss: negative counter variance.
- PASS Transient malformed variant rejected without data loss: negative counter calibration.
- PASS Transient malformed variant rejected without data loss: impossible calibration variance.
- PASS Transient malformed variant rejected without data loss: missing percentage summaries.
- PASS Transient malformed variant rejected without data loss: missing counter summaries.
- PASS Transient malformed variant rejected without data loss: unsupported counter algorithm.
- PASS Transient malformed variant rejected without data loss: future export.
- PASS Imported cached predictions are ignored; both algorithms and widget values are recomputed.
- PASS Removing excluded raw rows preserves both model times, weights and sample counts at every battery level; raw diagnostics are not replayed as learning.

46 grouped checks completed. Data and battery simulation restored; temporary widget removed.

Scope: real import confirmation, MainActivity.Model, SQLite, developer/overview views, app export and AppWidgetHost updates. File access uses app-owned URIs; this run does not automate the system document picker. Synthetic histories test implementation behavior, not physical battery accuracy.

## Additional regression verification

The same revision also passed all 37 JVM model tests and the other 26 Android tests on 2026-09-05. Together with this catalog runner, all 27 Android tests passed. The remaining tests cover event tracking, stale/missing/reset counters, audio and screen transitions, service restart/pause behavior, database migration and rollback, malformed/oversized imports, widget layout and refresh behavior, and the hybrid overview.

The saved screenshots were inspected for readable component estimates, weights and combined times. The fixture manifest contains exactly eight valid and two invalid import files; all references resolve. The previous generated datasets and reports were replaced. The temporary headless emulator was closed after verification without saving disk changes.
