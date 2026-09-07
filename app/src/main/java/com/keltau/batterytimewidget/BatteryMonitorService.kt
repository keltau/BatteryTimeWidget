package com.keltau.batterytimewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class BatteryMonitorService : Service() {
    private val tracker = IntervalTracker()
    private val chargeTracker = ChargeTracker()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var audioAvailable = false
    private var lastReading: BatteryReading? = null
    private var registered = false
    private var generation = BatteryStore.generation

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIME_CHANGED) {
                tracker.reset()
                chargeTracker.reset()
            }
            capture(if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            capture(observedPlayback = configs.isNotEmpty())
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.pause_learning),
                PendingIntent.getService(this, 1, Intent(this, BatteryMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pause(this)
            return START_NOT_STICKY
        }
        if (!isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        running = true
        audio = getSystemService(AudioManager::class.java)
        audioAvailable = runCatching {
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
        }.isSuccess
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        capture()
        BatteryMaintenanceService.schedule(this)
    }

    private fun capture(batteryIntent: Intent? = null, observedPlayback: Boolean? = null) {
        val currentGeneration = BatteryStore.generation
        if (generation != currentGeneration) {
            tracker.reset()
            chargeTracker.reset()
            generation = currentGeneration
        }
        val sticky = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val percent = percent(sticky)
        val screen = if (getSystemService(PowerManager::class.java).isInteractive) ScreenMode.ON else ScreenMode.OFF
        val audioCheck = runCatching { observedPlayback ?: (audio.activePlaybackConfigurations.isNotEmpty() || audio.isMusicActive) }
        val reading = BatteryReading(
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), percent,
            isDischarging(sticky), screen, audioCheck.getOrDefault(false), audioAvailable && audioCheck.isSuccess,
            chargeUah = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong()?.takeIf { it > 0 },
            currentUa = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            averageCurrentUa = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            voltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            temperatureDeciC = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
        )
        latest = reading
        val interval = tracker.observe(reading)
        val chargeInterval = chargeTracker.observe(reading)
        val changed = lastReading?.let {
            it.percent != reading.percent || it.discharging != reading.discharging || it.screen != reading.screen ||
                it.audioActive != reading.audioActive || it.audioKnown != reading.audioKnown
        } ?: true
        val widgetChanged = lastReading?.let { it.percent != reading.percent || it.discharging != reading.discharging } ?: true
        lastReading = reading
        if (interval != null || chargeInterval != null || changed) {
            BatteryStore.executor.execute {
                runCatching {
                    if (currentGeneration == BatteryStore.generation) BatteryStore.get(this).record(interval, chargeInterval, reading.timeMs)
                    failure = null
                    if (interval != null || (chargeInterval != null && chargeInterval.exclusion == null) || widgetChanged) BatteryWidgetProvider.updateAll(this)
                }.onFailure {
                    failure = getString(R.string.storage_error)
                }
                sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
            }
        }
    }

    private fun property(id: Int): Int? = runCatching {
        getSystemService(BatteryManager::class.java).getIntProperty(id)
    }.getOrNull()?.takeUnless { it == Int.MIN_VALUE }

    override fun onDestroy() {
        running = false
        latest = null
        if (registered) unregisterReceiver(receiver)
        if (audioAvailable) audio.unregisterAudioPlaybackCallback(playbackCallback)
        tracker.reset()
        chargeTracker.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishState(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    class RestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            resumeIfEnabled(context)
            val pending = goAsync()
            BatteryMaintenanceService.schedule(context) { pending?.finish() }
        }
    }

    companion object {
        private const val CHANNEL = "battery_learning"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES = "battery_learning"
        private const val ENABLED = "enabled"
        const val ACTION_STOP = "com.keltau.batterytimewidget.STOP"
        const val ACTION_CHANGED = "com.keltau.batterytimewidget.CHANGED"
        @Volatile var running = false
            private set
        @Volatile var latest: BatteryReading? = null
            private set
        @Volatile var failure: String? = null
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(ENABLED, false)

        fun start(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit { putBoolean(ENABLED, true) }
            resumeIfEnabled(context)
        }

        fun pause(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit { putBoolean(ENABLED, false) }
            failure = null
            if (!context.stopService(Intent(context, BatteryMonitorService::class.java))) publishState(context)
        }

        fun resumeIfEnabled(context: Context) {
            if (!isEnabled(context) || running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BatteryMonitorService::class.java)) }
                .onFailure {
                    failure = context.getString(R.string.start_failed)
                    publishState(context)
                }
        }

        private fun publishState(context: Context) {
            val application = context.applicationContext
            BatteryStore.executor.execute {
                runCatching { BatteryWidgetProvider.updateAll(application) }
                BatteryMaintenanceService.schedule(application)
                application.sendBroadcast(Intent(ACTION_CHANGED).setPackage(application.packageName))
            }
        }

        fun percent(intent: Intent?): Int {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
            return if (level >= 0 && scale > 0 && level <= scale) (level.toLong() * 100 / scale).toInt() else -1
        }

        fun isDischarging(intent: Intent): Boolean =
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) == 0 &&
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) == BatteryManager.BATTERY_STATUS_DISCHARGING
    }
}
