package com.keltau.batterytimewidget

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var model: Model
    private var showingDev = false
    private var importDialog: androidx.appcompat.app.AlertDialog? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showDev(false)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = model.refresh()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startLearning() }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri)
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.prepareImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        model = ViewModelProvider(this)[Model::class.java]
        onBackPressedDispatcher.addCallback(this, back)
        findViewById<View>(R.id.open_dev).setOnClickListener { showDev(true) }
        findViewById<View>(R.id.close_dev).setOnClickListener { showDev(false) }
        findViewById<View>(R.id.toggle_learning).setOnClickListener {
            if (BatteryMonitorService.isEnabled(this)) {
                BatteryMonitorService.pause(this)
            } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startLearning()
            }
        }
        findViewById<View>(R.id.add_widget).setOnClickListener {
            val manager = AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, BatteryWidgetProvider::class.java), null, null)
            } else {
                Toast.makeText(this, R.string.add_widget_manually, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<View>(R.id.export_data).setOnClickListener { exportDocument.launch("battery-time-${java.time.LocalDate.now()}.json") }
        findViewById<View>(R.id.import_data).setOnClickListener { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        model.snapshot.observe(this) { render(it) }
        model.busy.observe(this) { busy ->
            findViewById<View>(R.id.progress).visibility = if (busy) View.VISIBLE else View.GONE
            findViewById<View>(R.id.export_data).isEnabled = !busy
            findViewById<View>(R.id.import_data).isEnabled = !busy
        }
        model.message.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                model.message.value = null
            }
        }
        model.pendingImport.observe(this) { pending ->
            importDialog?.dismiss()
            importDialog = null
            if (pending != null) {
                importDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_title)
                    .setMessage(getString(R.string.import_confirmation, pending.summaries.sumOf { it.count }, pending.chargeSummaries.sumOf { it.count }, pending.raw.size + pending.chargeRaw.size))
                    .setNegativeButton(R.string.cancel) { _, _ -> model.pendingImport.value = null }
                    .setPositiveButton(R.string.replace_data) { _, _ -> model.applyImport() }
                    .setOnCancelListener { model.pendingImport.value = null }
                    .show()
            }
        }
        showDev(savedInstanceState?.getBoolean("dev") ?: false)
        BatteryMaintenanceService.schedule(this)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(BatteryMonitorService.ACTION_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        BatteryMonitorService.resumeIfEnabled(this)
        model.refresh()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        importDialog?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("dev", showingDev)
        super.onSaveInstanceState(outState)
    }

    private fun startLearning() {
        BatteryMonitorService.start(this)
        model.refresh()
    }

    private fun showDev(show: Boolean) {
        showingDev = show
        back.isEnabled = show
        findViewById<View>(R.id.overview_panel).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dev_panel).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.main_root).scrollTo(0, 0)
        model.setDiagnosticsVisible(show)
        if (show) model.snapshot.value?.let { render(it) }
    }

    private fun render(data: BatteryStore.Snapshot) {
        val now = System.currentTimeMillis()
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = BatteryMonitorService.percent(battery)
        val offHybrid = HybridEstimator.estimate(percent, ScreenMode.OFF, data.summaries, data.chargeSummaries, now)
        val onHybrid = HybridEstimator.estimate(percent, ScreenMode.ON, data.summaries, data.chargeSummaries, now)
        val off = offHybrid.percentage
        val on = onHybrid.percentage
        text(R.id.battery_status, if (percent < 0) getString(R.string.battery_unavailable) else getString(R.string.battery_status, percent))
        text(R.id.estimate_off, BatteryWidgetProvider.duration(this, offHybrid.seconds))
        text(R.id.estimate_on, BatteryWidgetProvider.duration(this, onHybrid.seconds))
        fun modelDetail(estimate: HybridEstimate): String = getString(R.string.model_estimates,
            BatteryWidgetProvider.duration(this, estimate.percentage.seconds),
            if (estimate.charge.time.seconds == null && BatteryMonitorService.latest?.let { it.chargeUah == null } == true)
                getString(R.string.counter_unavailable) else BatteryWidgetProvider.duration(this, estimate.charge.time.seconds),
            (estimate.percentageWeight * 100).roundToInt(),
            if (estimate.seconds == null) 0 else 100 - (estimate.percentageWeight * 100).roundToInt())
        text(R.id.models_off, modelDetail(offHybrid))
        text(R.id.models_on, modelDetail(onHybrid))
        val detail = when {
            percent in 0..BatteryConfig.TARGET_PERCENT -> R.string.target_reached
            battery != null && !BatteryMonitorService.isDischarging(battery) -> R.string.charging_detail
            offHybrid.seconds == null || onHybrid.seconds == null -> R.string.learning_detail
            off.seconds == null || on.seconds == null -> R.string.charge_learning_detail
            !offHybrid.established || !onHybrid.established -> R.string.provisional_detail
            else -> R.string.estimate_explanation
        }
        text(R.id.estimate_detail, getString(detail))
        val enabled = BatteryMonitorService.isEnabled(this)
        text(R.id.collection_status, BatteryMonitorService.failure ?: getString(when {
            BatteryMonitorService.running -> R.string.collection_active
            enabled -> R.string.collection_waiting
            else -> R.string.collection_paused
        }))
        text(R.id.toggle_learning, getString(if (enabled) R.string.pause_learning else R.string.start_learning))
        if (!showingDev || !model.rawLoaded) return
        val current = BatteryMonitorService.latest
        val excluded = data.raw.groupingBy { it.exclusion }.eachCount()
        val details = buildString {
            appendLine(getString(R.string.dev_counts, data.raw.size, data.summaries.sumOf { it.count }, data.summaries.map { it.day }.distinct().size))
            appendLine(getString(R.string.dev_mode, getString(R.string.screen_off), off.sampleCount, off.days, (off.coverage * 100).toInt()))
            appendLine(getString(R.string.dev_mode, getString(R.string.screen_on), on.sampleCount, on.days, (on.coverage * 100).toInt()))
            appendLine(getString(R.string.dev_charge_counts, data.chargeRaw.size, data.chargeSummaries.sumOf { it.count }))
            appendLine()
            appendLine(getString(R.string.dev_live, current?.screen?.name ?: "—", current?.audioActive?.toString() ?: "—", current?.audioKnown?.toString() ?: "—"))
            appendLine(getString(R.string.dev_electrical, current?.chargeUah?.toString() ?: "—", current?.currentUa?.toString() ?: "—",
                current?.averageCurrentUa?.toString() ?: "—", current?.voltageMv?.toString() ?: "—", current?.temperatureDeciC?.let { (it / 10.0).toString() } ?: "—"))
            appendLine()
            listOf(ScreenMode.OFF to offHybrid, ScreenMode.ON to onHybrid).forEach { (mode, hybrid) ->
                val charge = hybrid.charge
                appendLine(getString(R.string.dev_charge_mode, mode.name, charge.time.sampleCount, charge.time.days, charge.observedSeconds,
                    charge.equivalentPercent, (charge.time.coverage * 100).toInt(), (charge.capacityUah ?: 0.0) / 1000))
                appendLine(getString(R.string.dev_weights, hybrid.percentage.reliability, charge.time.reliability,
                    hybrid.percentageWeight * 100, hybrid.chargeWeight * 100))
            }
            appendLine()
            excluded.forEach { (reason, count) -> appendLine("Percentage ${reason?.name ?: "ACCEPTED"}: $count") }
            data.chargeRaw.groupingBy { it.exclusion }.eachCount().forEach { (reason, count) -> appendLine("Counter ${reason?.name ?: "ACCEPTED"}: $count") }
        }
        text(R.id.dev_summary, details)
        text(R.id.dev_bands, buildString {
            fun table(off: TimeEstimate, on: TimeEstimate) {
                appendLine(getString(R.string.band_header))
                off.bands.zip(on.bands).forEach { (a, b) ->
                    appendLine(String.format(Locale.getDefault(), "%3d–%3d  %8s  %8s  %5.1f / %5.1f", a.band * 5, (a.band + 1) * 5,
                        if (off.sampleCount == 0) "—" else String.format(Locale.getDefault(), "%.1f", a.secondsPerPercent / 60),
                        if (on.sampleCount == 0) "—" else String.format(Locale.getDefault(), "%.1f", b.secondsPerPercent / 60),
                        a.effectiveSamples, b.effectiveSamples))
                }
            }
            appendLine(getString(R.string.percentage_model))
            table(off, on)
            appendLine()
            appendLine(getString(R.string.charge_model))
            table(offHybrid.charge.time, onHybrid.charge.time)
        })
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rawDescriptions = data.raw.takeLast(30).map {
            it.endMs to "Percentage · ${date.format(Date(it.endMs))} · ${it.startPercent}% → ${it.endPercent}%\n${it.screen.name} · ${BatteryWidgetProvider.duration(this, it.seconds.toLong())} · ${it.exclusion?.name ?: "ACCEPTED"}"
        } + data.chargeRaw.takeLast(30).map {
            it.end.timeMs to "Counter · ${date.format(Date(it.end.timeMs))} · ${it.start.chargeUah ?: "—"} → ${it.end.chargeUah ?: "—"} µAh\n${it.start.screen.name} · ${BatteryWidgetProvider.duration(this, it.seconds.toLong())} · ${it.exclusion?.name ?: "ACCEPTED"}\n${it.end.currentUa ?: "—"} µA · ${it.end.voltageMv ?: "—"} mV · ${it.end.temperatureDeciC?.let { t -> t / 10.0 } ?: "—"} °C"
        }
        text(R.id.dev_raw, rawDescriptions.sortedByDescending { it.first }.take(30).joinToString("\n\n") { it.second }.ifEmpty { getString(R.string.no_intervals) })
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    class Model(application: Application) : AndroidViewModel(application) {
        val snapshot = MutableLiveData<BatteryStore.Snapshot>()
        val busy = MutableLiveData(false)
        val message = MutableLiveData<String?>()
        val pendingImport = MutableLiveData<BatteryStore.Snapshot?>()
        private val handler = Handler(Looper.getMainLooper())
        private var activeTasks = 0
        private var refreshInFlight = false
        private var refreshPending = false
        @Volatile private var diagnosticsVisible = false
        var rawLoaded = false
            private set
        private val context: Context get() = getApplication<Application>()

        fun setDiagnosticsVisible(visible: Boolean) {
            if (diagnosticsVisible == visible) return
            diagnosticsVisible = visible
            refresh()
        }

        fun refresh() {
            if (refreshInFlight) {
                refreshPending = true
                return
            }
            refreshInFlight = true
            runTask(onComplete = {
                refreshInFlight = false
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }) { refreshSnapshot() }
        }

        private fun refreshSnapshot() {
            val includeRaw = diagnosticsVisible
            val data = BatteryStore.get(context).snapshot(System.currentTimeMillis(), includeRaw)
            handler.post {
                rawLoaded = includeRaw
                snapshot.value = data
            }
            // Opening the app or refreshing while learning is paused must also update widgets.
            BatteryWidgetProvider.updateAll(context, data)
        }

        fun export(uri: Uri) = runTask(transferExecutor) {
            // Serialize the snapshot with collection, then release the database queue before
            // encoding JSON or waiting for a potentially remote document provider.
            val (data, percent, now) = BatteryStore.executor.submit<Triple<BatteryStore.Snapshot, Int, Long>> {
                val now = System.currentTimeMillis()
                val data = BatteryStore.get(context).snapshot(now)
                val percent = BatteryMonitorService.percent(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                Triple(data, percent, now)
            }.get()
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { DataTransfer.write(it, data, percent, now) }
            message.postValue(context.getString(R.string.export_complete))
        }

        fun prepareImport(uri: Uri) = runTask(transferExecutor) {
            val data = requireNotNull(context.contentResolver.openInputStream(uri)).use { DataTransfer.read(it, System.currentTimeMillis()) }
            pendingImport.postValue(data)
        }

        fun applyImport() {
            val data = pendingImport.value ?: return
            pendingImport.value = null
            runTask {
                BatteryStore.get(context).replace(data, System.currentTimeMillis())
                BatteryMaintenanceService.schedule(context)
                refreshSnapshot()
                message.postValue(context.getString(R.string.import_complete))
            }
        }

        private fun runTask(executor: Executor = BatteryStore.executor, onComplete: () -> Unit = {}, block: () -> Unit) {
            activeTasks++
            busy.value = true
            executor.execute {
                runCatching(block).onFailure { message.postValue(context.getString(R.string.data_error, it.message?.take(160) ?: it.javaClass.simpleName)) }
                handler.post {
                    activeTasks--
                    busy.value = activeTasks > 0
                    onComplete()
                }
            }
        }

        companion object {
            // The transfer worker retires after 30 seconds without document operations.
            private val transferExecutor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
        }
    }
}
