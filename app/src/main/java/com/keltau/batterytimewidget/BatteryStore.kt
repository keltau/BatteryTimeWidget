package com.keltau.batterytimewidget

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.util.concurrent.Executors

class BatteryStore internal constructor(context: Context, name: String = "battery.db") : SQLiteOpenHelper(context, name, null, 2) {
    data class Snapshot(val summaries: List<DailySummary>, val raw: List<DischargeInterval>,
                        val chargeSummaries: List<ChargeSummary> = emptyList(), val chargeRaw: List<ChargeInterval> = emptyList())

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE daily (
                day INTEGER NOT NULL, screen TEXT NOT NULL, band INTEGER NOT NULL,
                count INTEGER NOT NULL, seconds REAL NOT NULL, squared_seconds REAL NOT NULL,
                PRIMARY KEY (day, screen, band)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE raw (
                id INTEGER PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
                start_percent INTEGER NOT NULL, end_percent INTEGER NOT NULL,
                seconds REAL NOT NULL, screen TEXT NOT NULL, exclusion TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX raw_end ON raw(end_ms)")
        createChargeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createChargeTables(db)
    }

    private fun createChargeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE charge_daily (
            day INTEGER NOT NULL, screen TEXT NOT NULL, band INTEGER NOT NULL, count INTEGER NOT NULL,
            seconds REAL NOT NULL, charge_uah REAL NOT NULL, squared_rate_seconds REAL NOT NULL,
            capacity_uah_seconds REAL NOT NULL, squared_capacity_seconds REAL NOT NULL,
            PRIMARY KEY (day, screen, band))""")
        db.execSQL("CREATE TABLE charge_raw (id INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX charge_raw_end ON charge_raw(end_ms)")
    }

    fun record(interval: DischargeInterval, nowMs: Long) = record(interval, null, nowMs)

    fun record(interval: DischargeInterval?, chargeInterval: ChargeInterval?, nowMs: Long) {
        val db = writableDatabase
        db.transaction {
            if (interval != null) insertRaw(db, interval)
            if (interval != null && interval.exclusion == null) {
                val day = Math.floorDiv(interval.endMs, BatteryConfig.DAY_MS)
                db.execSQL(
                    "INSERT OR IGNORE INTO daily VALUES (?, ?, ?, 0, 0, 0)",
                    arrayOf<Any>(day, interval.screen.name, interval.band),
                )
                db.execSQL(
                    "UPDATE daily SET count = count + 1, seconds = seconds + ?, squared_seconds = squared_seconds + ? WHERE day = ? AND screen = ? AND band = ?",
                    arrayOf<Any>(interval.seconds, interval.seconds * interval.seconds, day, interval.screen.name, interval.band),
                )
            }
            if (chargeInterval != null) {
                insertChargeRaw(db, chargeInterval)
                if (chargeInterval.exclusion == null) {
                    val row = chargeInterval.summary()
                    db.execSQL("INSERT OR IGNORE INTO charge_daily VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0)", arrayOf<Any>(row.day, row.screen.name, row.band))
                    db.execSQL("""UPDATE charge_daily SET count = count + 1, seconds = seconds + ?, charge_uah = charge_uah + ?,
                        squared_rate_seconds = squared_rate_seconds + ?, capacity_uah_seconds = capacity_uah_seconds + ?,
                        squared_capacity_seconds = squared_capacity_seconds + ? WHERE day = ? AND screen = ? AND band = ?""",
                        arrayOf<Any>(row.seconds, row.chargeUah, row.squaredRateSeconds, row.capacityUahSeconds, row.squaredCapacitySeconds, row.day, row.screen.name, row.band))
                }
            }
            prune(db, nowMs)
            if (interval != null) trimRaw(db, "raw", BatteryConfig.MAX_RAW_ROWS)
            if (chargeInterval != null) trimRaw(db, "charge_raw", BatteryConfig.MAX_CHARGE_RAW_ROWS)
        }
    }

    fun snapshot(nowMs: Long, includeRaw: Boolean = true): Snapshot {
        val db = writableDatabase
        return db.transaction {
            prune(db, nowMs)
            val summaries = summaries(nowMs)
            if (!includeRaw) return@transaction Snapshot(summaries, emptyList(), chargeSummaries(nowMs))
            val raw = mutableListOf<DischargeInterval>()
            db.rawQuery("SELECT start_ms, end_ms, start_percent, end_percent, seconds, screen, exclusion FROM raw ORDER BY end_ms, id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    raw += DischargeInterval(
                        cursor.getLong(0), cursor.getLong(1), cursor.getInt(2), cursor.getInt(3), cursor.getDouble(4),
                        ScreenMode.valueOf(cursor.getString(5)),
                        if (cursor.isNull(6)) null else Exclusion.valueOf(cursor.getString(6)),
                    )
                }
            }
            val chargeRaw = mutableListOf<ChargeInterval>()
            db.rawQuery("SELECT data FROM charge_raw ORDER BY end_ms, id", null).use { cursor ->
                while (cursor.moveToNext()) chargeRaw += ChargeTransfer.interval(org.json.JSONObject(cursor.getString(0)))
            }
            Snapshot(summaries, raw, chargeSummaries(nowMs), chargeRaw)
        }
    }

    fun replace(snapshot: Snapshot, nowMs: Long) {
        val db = writableDatabase
        db.transaction {
            db.delete("raw", null, null)
            db.delete("daily", null, null)
            db.delete("charge_raw", null, null)
            db.delete("charge_daily", null, null)
            snapshot.summaries.forEach { row ->
                db.execSQL(
                    "INSERT INTO daily VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(row.day, row.screen.name, row.band, row.count, row.seconds, row.squaredSeconds),
                )
            }
            snapshot.raw.forEach { insertRaw(db, it) }
            snapshot.chargeSummaries.forEach { row ->
                db.execSQL("INSERT INTO charge_daily VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(row.day, row.screen.name, row.band, row.count, row.seconds, row.chargeUah, row.squaredRateSeconds, row.capacityUahSeconds, row.squaredCapacitySeconds))
            }
            snapshot.chargeRaw.forEach { insertChargeRaw(db, it) }
            prune(db, nowMs)
            trimRaw(db, "raw", BatteryConfig.MAX_RAW_ROWS)
            trimRaw(db, "charge_raw", BatteryConfig.MAX_CHARGE_RAW_ROWS)
        }
        generation++
    }

    fun prune(nowMs: Long) {
        val db = writableDatabase
        db.transaction { prune(db, nowMs) }
    }

    fun hasHistory(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM daily UNION ALL SELECT 1 FROM charge_daily UNION ALL SELECT 1 FROM raw UNION ALL SELECT 1 FROM charge_raw LIMIT 1",
        null,
    ).use { it.moveToFirst() }

    fun summaries(nowMs: Long): List<DailySummary> {
        val today = Math.floorDiv(nowMs, BatteryConfig.DAY_MS)
        val rows = mutableListOf<DailySummary>()
        readableDatabase.rawQuery(
            "SELECT day, screen, band, count, seconds, squared_seconds FROM daily WHERE day BETWEEN ? AND ? ORDER BY day, screen, band",
            arrayOf((today - BatteryConfig.HISTORY_DAYS + 1).toString(), today.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += DailySummary(cursor.getLong(0), ScreenMode.valueOf(cursor.getString(1)), cursor.getInt(2),
                    cursor.getInt(3), cursor.getDouble(4), cursor.getDouble(5))
            }
        }
        return rows
    }

    private fun prune(db: SQLiteDatabase, nowMs: Long) {
        db.delete("raw", "end_ms < ?", arrayOf((nowMs - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS).toString()))
        val firstDay = Math.floorDiv(nowMs, BatteryConfig.DAY_MS) - BatteryConfig.HISTORY_DAYS + 1
        db.delete("daily", "day < ?", arrayOf(firstDay.toString()))
        db.delete("charge_raw", "end_ms < ?", arrayOf((nowMs - BatteryConfig.RAW_DAYS * BatteryConfig.DAY_MS).toString()))
        db.delete("charge_daily", "day < ?", arrayOf(firstDay.toString()))
    }

    private fun trimRaw(db: SQLiteDatabase, table: String, limit: Int) {
        // Only inserts can exceed a row cap. Reads and age cleanup need no history scan.
        val overflow = DatabaseUtils.queryNumEntries(db, table) - limit
        if (overflow > 0) {
            db.execSQL("DELETE FROM $table WHERE id IN (SELECT id FROM $table ORDER BY end_ms, id LIMIT $overflow)")
        }
    }

    fun chargeSummaries(nowMs: Long): List<ChargeSummary> {
        val today = Math.floorDiv(nowMs, BatteryConfig.DAY_MS)
        val rows = mutableListOf<ChargeSummary>()
        readableDatabase.rawQuery("SELECT day, screen, band, count, seconds, charge_uah, squared_rate_seconds, capacity_uah_seconds, squared_capacity_seconds FROM charge_daily WHERE day BETWEEN ? AND ? ORDER BY day, screen, band",
            arrayOf((today - BatteryConfig.HISTORY_DAYS + 1).toString(), today.toString())).use { c ->
            while (c.moveToNext()) rows += ChargeSummary(c.getLong(0), ScreenMode.valueOf(c.getString(1)), c.getInt(2), c.getInt(3), c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8))
        }
        return rows
    }

    private fun insertChargeRaw(db: SQLiteDatabase, row: ChargeInterval) {
        db.insertOrThrow("charge_raw", null, ContentValues().apply {
            put("end_ms", row.end.timeMs)
            put("data", ChargeTransfer.json(row).toString())
        })
    }

    private fun insertRaw(db: SQLiteDatabase, row: DischargeInterval) {
        db.insertOrThrow("raw", null, ContentValues().apply {
            put("start_ms", row.startMs)
            put("end_ms", row.endMs)
            put("start_percent", row.startPercent)
            put("end_percent", row.endPercent)
            put("seconds", row.seconds)
            put("screen", row.screen.name)
            put("exclusion", row.exclusion?.name)
        })
    }

    companion object {
        val executor = Executors.newSingleThreadExecutor()
        @Volatile var generation = 0L
            private set
        @Volatile private var instance: BatteryStore? = null

        fun get(context: Context): BatteryStore = instance ?: synchronized(this) {
            instance ?: BatteryStore(context.applicationContext).also { instance = it }
        }
    }
}
