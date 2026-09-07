# Battery Time

Android 8.0+ app with a home screen widget estimating time to 15% battery with the screen continuously off or continuously on. Percentage and charge-counter models learn in parallel for each screen state. The widget shows their reliability-weighted blend; the overview shows each model, the combined time, and both blend weights. Open the app, choose **Start learning** once, and add the widget. Learning stays enabled and resumes automatically after interruptions until you pause it. The overview also provides JSON export/import and a developer page.

The widget can shrink to two columns by one row on supported launcher grids. Compact sizes show only the two estimates and their screen labels. The title and status return at 180 × 180 dp or larger, including square widgets. Both estimates use short durations and one shared text size fitted to the launcher dimensions, with smaller units and tight spacing. Android 12+ receives layouts for the sizes reported by the launcher; older versions refresh on resize and provide portrait/landscape variants.

## Automatic start and recovery

Learning is off until you enable it. The app saves your choice locally; automatic recovery runs only while learning is enabled and does not open the overview screen.

| Event                                                     | Behavior                                                                                                               |
|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Android interrupts the app process                        | Android can restart the enabled collector as a sticky foreground service. Recovery timing is controlled by the system. |
| Phone restarts                                            | Enabled learning resumes after boot and the first unlock, without opening the app.                                     |
| App is updated                                            | Enabled learning resumes after the update.                                                                             |
| App is opened while the collector is absent               | Enabled learning resumes automatically.                                                                                |
| **Pause learning** is selected in the app or notification | Learning stops and stays paused through reboots, updates and reopening. Select **Start learning** to enable it again.  |
| App is force-stopped in Android settings                  | Collection stays stopped until you reopen the app; it then resumes if learning was enabled.                            |

Stored history survives interruptions. Both collectors start fresh measurement windows after recovery, so downtime cannot train either model. Learning resumes after unlock because its settings and history remain in credential-protected storage. Restricted battery settings or manufacturer background policies can delay boot delivery or service recovery.

## Collection

A user-started foreground service observes battery, screen, power and audio playback events. Its quiet notification provides a pause action. Collection and recovery do not poll, acquire wake locks, request battery optimization exemptions, or access usage history. No extra alarms or polling jobs are used for recovery.

Battery percentage reports are quantized. Collection starts timing at an observed percentage drop, then measures until the next consecutive 1% drop. The initial partial percentage is discarded. A window contributes only when the whole window is discharging in one screen state. Screen changes, charging, unknown audio observations, screen-off audio, skipped/increasing levels, clock changes and implausible durations exclude that window. Excluded windows are retained as diagnostic raw data. The next observed boundary starts a fresh window; skipped levels require an extra warmup boundary.

Audio starting and stopping between battery reports excludes the whole percentage window. The charge tracker closes the preceding state's window at each transition, so quiet time before playback begins can still train the screen-off model. Time spent playing audio with the screen off is excluded, including transitions back to screen on. Unknown audio observations fail closed. Screen-on playback can train both screen-on models. Playback reports are anonymous; app identities, media titles and audio content are not collected.

Every collection event also queries `BATTERY_PROPERTY_CHARGE_COUNTER`, `CURRENT_NOW` and `CURRENT_AVERAGE`, when available. Voltage and temperature come from the battery broadcast and may be older than the event. These supporting readings are diagnostic; the estimator does not integrate sparse instantaneous current samples or infer charge from voltage.

Charge samples measure counter differences over monotonic elapsed time, even when percentage stays unchanged. At least 20 seconds and the larger of 10 µAh or 0.01% of the approximate full-charge scale are required per accepted window. Smaller changes accumulate within a state, and duplicate events do not create evidence. A state change closes the preceding window and starts a new one. If the counter is unchanged at a transition, the next counter step is discarded as warmup because it may span both states. Missing counters, charging, unknown audio, screen-off audio, counter increases/resets, skipped or increasing percentages, clock changes, durations over 24 hours, consumption over 1.5% per window, and implausible average draw above 5C are excluded. Raw rejected windows explain why samples were not learned. Restart, import, pause and clock-change handling reset both trackers.

## Estimation

`BatteryConfig` in `BatteryModel.kt` and `ChargeConfig` in `ChargeModel.kt` contain the model and retention settings. Each model keeps screen on/off separate, with twenty 5% bands. An accepted 51% → 50% percentage interval contributes its elapsed seconds to the 50–55% band. UTC-day percentage summaries store count, total seconds and squared seconds for each mode/band. Variance contributes to reliability weighting; the UI does not claim a statistical confidence interval.

At prediction time, a summary aged `a` days has weight `2^(-a/14)`. Compute the weighted overall seconds per percent for that screen mode, then each band mean:

```text
overall = sum(weight × seconds) / sum(weight × count)
band    = (sum(weight × seconds in band) + 4 × overall)
          / (sum(weight × count in band) + 4)
time    = sum(band seconds per percent for each percentage point above 15)
```

The four-sample prior stabilizes sparse bands using the same mode's history. Dense bands retain their own discharge characteristics. This integrates differing rates across battery levels instead of extrapolating only the current rate. Recent use matters more, while up to eight weeks of behavior contributes. The percentage model displays **Learning** until it has six accepted drops and at least three age-weighted samples. At/below 15%, both models and the combined time are zero.

The charge model independently estimates elapsed seconds per consumed µAh. Android does not universally expose full-charge capacity, so the model approximates a scale at each interval endpoint as `remaining µAh / (system percentage / 100)`, then averages the two endpoints. Only observations from 15–100% with scales between 100 and 30,000 mAh can train. This scale connects counter consumption to the 15% target; it is not a battery-health measurement. It permits learning without waiting for full percentage drops, but still shares the hardware gauge and percentage reference with the other model.

Charge summaries retain window count, elapsed seconds, consumed µAh, `sum(µAh² / seconds)`, `sum(scale × seconds)` and `sum(scale² × seconds)`. With the same age weights, compute:

```text
scale       = sum(weight × scale × seconds) / sum(weight × seconds)
equiv drain = sum(weight × consumed µAh) × 100 / scale
overall     = sum(weight × seconds) / equiv drain
band        = (sum(weight × seconds in band) + 4 × overall)
              / (sum(weight × consumed µAh in band) × 100 / scale + 4)
time        = sum(band seconds per percent for each percentage point above 15)
```

Rates are weighted by elapsed time rather than event count. The charge model can return a time after three accepted windows, 120 age-weighted seconds, and 0.1 age-weighted equivalent percentage points consumed. These are evidence thresholds, not a promise of learning in two minutes: usable hardware reports and the applicable screen state are also needed. It can supply the entire widget value while the percentage model learns. If only percentage history is usable, that model supplies the entire value. Both trackers continue running independently in either case.

Coverage is the fraction of the remaining percentage range whose bands have at least three effective percentage points over two days. For the counter model, this means equivalent consumed percentage, not event count. A model is marked early until coverage reaches 80% and it has data on seven distinct days. A blend remains early if any contributing model is early. During charging, the numbers describe hypothetical use after unplugging from the current percentage.

Each ready model receives a heuristic evidence score:

```text
score = evidence / (evidence + prior)
        × (0.35 + 0.65 × coverage)
        × (0.6 + 0.4 × min(days / 7, 1))
        × consistency × freshness
```

Percentage evidence is age-weighted accepted drops, with prior 6. Its consistency is `1 / (1 + CV²)` of seconds per percent. Counter evidence is age-weighted equivalent percentage consumed, with prior 4; consistency is `1 / (1.15 + rate CV² + 4 × scale CV²)`. Counter moments use elapsed-time weights. The fixed 0.15 penalty acknowledges approximate calibration. Freshness is age-weighted evidence divided by unweighted evidence (sample count for percentage, duration for counter). Unready models have zero weight. Ready scores are normalized and their time values are averaged. The scores are not accuracy probabilities, and combining two views of the same gauge does not make them statistically independent. Developer insights exposes the scores, normalized weights, coverage, calibration, bands and raw electrical readings.

## Storage and transfer

- App-private SQLite schema version 2 with WAL; migration adds charge tables without changing existing percentage data. One executor serializes database work off the UI thread. Document reading, validation and writing use a separate worker that retires when idle, so a slow document provider cannot block collection or widget work. Exports take a consistent database snapshot before transfer; imports still replace both histories atomically. An event's percentage and charge updates commit in one transaction.
- Raw windows: seven rolling days, capped separately at 20,000 percentage rows and 10,000 charge rows. Charge rows include endpoint sensor readings and an exclusion reason.
- Daily summaries: today and the previous 55 UTC days, at most 2,240 rows per model. Expired raw data is not needed to retain learning.
- Pruning runs during recording, reading/exporting, importing and a daily persisted `JobScheduler` task. Android may defer maintenance in Doze; the next active access prunes immediately. Force-stopped apps cannot run maintenance until resumed.
- Row caps are checked only when inserting into the affected raw table; only overflow rows are removed. Overview refreshes read the two compact summary histories, loading full raw diagnostics when the developer page is opened or data is exported. Cancelled maintenance skips pending work and lets any in-flight database transaction finish atomically.
- The daily job is cancelled when learning is paused and all retained history is gone; enabling learning or importing history schedules it again. Paused histories continue receiving cleanup until they expire.
- The widget refreshes on percentage/charging changes, new model data, imports, and service start/stop. Android's 30-minute widget refresh provides a coarse fallback. Refreshes only read compact summaries and show an update time; there is no ticking countdown.
- Widget text fitting reuses a bounded cache keyed by dimensions, displayed text and Android configuration, including font scale and locale. Refresh timing, displayed values and diagnostic sampling are unchanged.
- JSON version 2 includes model metadata, both estimates, blend weights and scores, both models' daily sufficient statistics, and raw intervals with available electrical readings. Imported estimates are informational: predictions are recomputed from summaries against the current battery level and date. Version 1 backups remain supported and restore percentage history only; importing one clears charge history as part of replacing both histories.
- Import checks schema/version, dates, numeric bounds, duplicates, overlap within each model, the 32 MB size limit, and raw/summary consistency before showing a replacement confirmation. Accepted charge windows are checked against the charge tracker's validity rules. Replacement is atomic, does not replay raw intervals into summaries, and invalidates both ongoing measurements. Re-importing a file cannot double-count it. Expired records are dropped.
- The system document picker supplies access to the selected file without broad storage permission. App-managed backups disable Android cloud/device transfer for this history so old records are not silently reintroduced. Explicitly exported files remain wherever the user saved them.

## Testing

The importable regression catalog is documented in [test-data/README.md](test-data/README.md). It contains eight valid datasets covering both algorithms and their blend, plus two rejected imports. The generator and application runner also exercise fifteen malformed variants without creating extra permanent files. Regeneration checks analytic predictions at every battery percentage, then uses each file to verify import confirmation, both stored histories, the overview, developer counts, export/re-import and a live widget host. The latest generated results are in [test-data/application-test-results.md](test-data/application-test-results.md).

An additional [realistic daily-usage sample](test-data/realistic-usage-guide.md) brings the total to eleven import files. It simulates six weeks of one phone's battery events through both production collectors. `RealisticUsageImportTest` generates and tests only this new sample; its [separate results](test-data/realistic-usage-test-results.md) preserve the original ten files and their previous run.

## Android configuration

The existing compile/target SDK 37, minimum SDK 26, AGP 9.4 and Gradle 9.6 setup is retained. Kotlin uses AGP's built-in support. Material Views and `RemoteViews` use the existing dependencies; SQLite and JobScheduler need no new runtime library. No network permission, telemetry, exact alarms or notification-listener access is used.

Permissions have specific purposes:

| Permission                       | Purpose                                                                                                                                                            |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FOREGROUND_SERVICE`             | Keep the user-started event observer eligible to run in the background.                                                                                            |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ type for the battery-learning observer; its subtype is declared on the service. Play distribution requires review of this use case.                    |
| `POST_NOTIFICATIONS`             | Request visibility of the learning notification on Android 13+. Denial does not prevent service operation; pause remains available in the app/system task manager. |
| `RECEIVE_BOOT_COMPLETED`         | Resume previously enabled learning after reboot and unlock, and allow the daily cleanup job to persist across reboots.                                             |

## Limits

System battery broadcasts are approximate and may be delayed by firmware or sleep. Public playback callbacks cannot guarantee detection of remote, muted, vendor-specific or otherwise unreported playback. Observable screen-off audio is excluded conservatively. 

The percentage model still needs uninterrupted full drops, which can take hours with the screen off. Counter sampling can learn from shorter sessions, but hardware may report unsupported, coarse, stale or recalibrated counters. A frozen counter cannot train a zero-drain prediction. Sub-percent resolution and prediction accuracy need physical-device validation; emulator tests verify behavior using synthetic data. CPU load, radio conditions, temperature and battery aging still create variability. These predictions describe continuation of learned behavior, not a guaranteed deadline.


Useful platform references: [battery broadcasts](https://developer.android.com/reference/android/content/Intent#ACTION_BATTERY_CHANGED), [playback callbacks](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced#update-periodically), [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler).

Restart behavior follows Android's [foreground-service background-start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [force-stopped package behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state).

## Disclosure

This project was generated with the help of GPT-6 Astra.
