# Battery Time

Android 8.0+ app with a home screen widget estimating time to 15% battery with the screen continuously off or continuously on. Open the app, start learning, and add the widget. The overview also provides JSON export/import and a developer page.

The widget can shrink to two columns by one row on supported launcher grids. Compact sizes show only the two estimates and their screen labels. The title and status return at 180 × 180 dp or larger, including square widgets. Both estimates use short durations and one shared text size fitted to the launcher dimensions, with smaller units and tight spacing. Android 12+ receives layouts for the sizes reported by the launcher; older versions refresh on resize and provide portrait/landscape variants.

## Collection

A user-started foreground service observes battery, screen, power and audio playback events. It does not poll, acquire wake locks, request battery optimization exemptions, or access usage history. Its quiet notification provides a pause action. The enabled setting is saved in app-private preferences. Android can restart the sticky service after process interruption; after reboot and the first unlock, or after an app update, a receiver resumes it only if learning was enabled. Opening the app also resumes enabled learning if the service is absent. Pausing in the app or notification persists across restarts. An interrupted in-memory interval is discarded so downtime cannot train the model.

Android force-stop prevents automatic restart until the app is opened again. Restricted battery settings or manufacturer background policies can also delay boot delivery or service recovery. No extra alarms, polling jobs or battery-optimization exemptions are requested to circumvent those controls. Learning resumes after unlock because its settings and history remain in credential-protected storage.

Battery percentage reports are quantized. Collection starts timing at an observed percentage drop, then measures until the next consecutive 1% drop. The initial partial percentage is discarded. A window contributes only when the whole window is discharging in one screen state. Screen changes, charging, unknown audio observations, screen-off audio, skipped/increasing levels, clock changes and implausible durations exclude that window. Excluded windows are retained as diagnostic raw data. The next observed boundary starts a fresh window; skipped levels require an extra warmup boundary.

Audio starting and stopping between battery reports marks the entire window as excluded. Audio-affected screen-off windows never train either model, including when the screen turns on before the next battery report. Screen-on playback can train the screen-on model. Playback reports are anonymous; app identities, media titles and audio content are not collected.

## Estimation

`BatteryConfig` in `BatteryModel.kt` contains the model and retention settings. There are separate models for screen on/off, with twenty 5% bands. An accepted 51% → 50% interval contributes its elapsed seconds to the 50–55% band. UTC-day summaries store count, total seconds and squared seconds for each mode/band. Squared seconds are retained for diagnostic/future uncertainty analysis; the current UI does not claim a statistical confidence interval.

At prediction time, a summary aged `a` days has weight `2^(-a/14)`. Compute the weighted overall seconds per percent for that screen mode, then each band mean:

```text
overall = sum(weight × seconds) / sum(weight × count)
band    = (sum(weight × seconds in band) + 4 × overall)
          / (sum(weight × count in band) + 4)
time    = sum(band seconds per percent for each percentage point above 15)
```

The four-sample prior stabilizes sparse bands using the same mode's history. Dense bands retain their own discharge characteristics. This integrates differing rates across battery levels instead of extrapolating only the current rate. Recent use matters more, while up to eight weeks of behavior contributes. No device-independent discharge rate is invented: a mode displays **Learning** until it has six accepted drops and at least three age-weighted samples. At/below 15%, time is zero.

Coverage is the fraction of the remaining percentage range whose bands have at least three effective samples over two days. Estimates are marked early until coverage reaches 80% and the mode has data on seven distinct days. This is evidence coverage, not a probability of accuracy. During charging, the numbers are hypothetical use after unplugging from the current percentage.

## Storage and transfer

- App-private SQLite with WAL; one executor serializes database work and file transfers off the UI thread.
- Raw observed discharge/charge intervals: seven rolling days, capped at 20,000 rows.
- Daily summaries: today and the previous 55 UTC days, at most 2,240 rows. Expired raw data is not needed to retain learning.
- Pruning runs during recording, reading/exporting, importing and a daily persisted `JobScheduler` task. Android may defer maintenance in Doze; the next active access prunes immediately. Force-stopped apps cannot run maintenance until resumed.
- The widget refreshes on percentage/charging changes, new model data, imports, and service start/stop. Android's 30-minute widget refresh provides a coarse fallback. Refreshes only read compact summaries and show an update time; there is no ticking countdown.
- JSON version 1 includes model metadata, leading estimates at export time, daily sufficient statistics and raw intervals. Imported estimates are informational: predictions are recomputed from summaries against the current battery level and date.
- Import checks schema/version, dates, numeric bounds, duplicates, overlap, size and raw/summary consistency before showing a replacement confirmation. Replacement is atomic, does not replay raw intervals into summaries, and invalidates any ongoing measurement. Re-importing a file cannot double-count it. Expired records are dropped.
- The system document picker supplies access to the selected file without broad storage permission. App-managed backups disable Android cloud/device transfer for this history so old records are not silently reintroduced. Explicitly exported files remain wherever the user saved them.

## Android configuration

The existing compile/target SDK 37, minimum SDK 26, AGP 9.4 and Gradle 9.6 setup is retained. Kotlin uses AGP's built-in support. Material Views and `RemoteViews` use the existing dependencies; SQLite and JobScheduler need no new runtime library. No network permission, telemetry, exact alarms or notification-listener access is used.

Permissions have specific purposes:

| Permission | Purpose |
| --- | --- |
| `FOREGROUND_SERVICE` | Keep the user-started event observer eligible to run in the background. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ type for the battery-learning observer; its subtype is declared on the service. Play distribution requires review of this use case. |
| `POST_NOTIFICATIONS` | Request visibility of the learning notification on Android 13+. Denial does not prevent service operation; pause remains available in the app/system task manager. |
| `RECEIVE_BOOT_COMPLETED` | Resume previously enabled learning after reboot and unlock, and allow the daily cleanup job to persist across reboots. |

## Limits

System battery broadcasts are approximate and may be delayed by firmware or sleep. Public playback callbacks cannot guarantee detection of remote, muted, vendor-specific or otherwise unreported playback. Observable screen-off audio is excluded conservatively. 

Discarding mixed windows trades learning speed and short-session representativeness for trustworthy separation. Screen-off drops can take hours; short screen-on sessions may contribute no full clean percentage. Both modes may need days of use before returning a number. CPU load, radio conditions, temperature and battery aging still create variability. These predictions describe continuation of learned behavior, not a guaranteed deadline. Physical-device collection, audio routing, Doze behavior and accuracy need validation over several weeks.


Useful platform references: [battery broadcasts](https://developer.android.com/reference/android/content/Intent#ACTION_BATTERY_CHANGED), [playback callbacks](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), [widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced#update-periodically), [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler).

Restart behavior follows Android's [foreground-service background-start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [force-stopped package behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state).

## Disclosure

This project was generated with the help of GPT-6 Astra.
