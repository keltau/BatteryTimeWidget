package com.keltau.batterytimewidget

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.util.concurrent.Executors

class BatteryStore private constructor(context: Context) : SQLiteOpenHelper(context, "battery.db", null, 1) {
    data class Snapshot(val summaries: List<DailySummary>, val raw: List<DischargeInterval>)

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported database upgrade: $oldVersion to $newVersion")
    }

    fun record(interval: DischargeInterval, nowMs: Long) {
        val db = writableDatabase
        db.transaction {
            insertRaw(db, interval)
            if (interval.exclusion == null) {
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
            prune(db, nowMs)
        }
    }

    fun snapshot(nowMs: Long): Snapshot {
        val db = writableDatabase
        return db.transaction {
            prune(db, nowMs)
            val summaries = summaries(nowMs)
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
            Snapshot(summaries, raw)
        }
    }

    fun replace(snapshot: Snapshot, nowMs: Long) {
        val db = writableDatabase
        db.transaction {
            db.delete("raw", null, null)
            db.delete("daily", null, null)
            snapshot.summaries.forEach { row ->
                db.execSQL(
                    "INSERT INTO daily VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(row.day, row.screen.name, row.band, row.count, row.seconds, row.squaredSeconds),
                )
            }
            snapshot.raw.forEach { insertRaw(db, it) }
            prune(db, nowMs)
        }
        generation++
    }

    fun prune(nowMs: Long) = prune(writableDatabase, nowMs)

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
        db.execSQL("DELETE FROM raw WHERE id IN (SELECT id FROM raw ORDER BY end_ms DESC, id DESC LIMIT -1 OFFSET ${BatteryConfig.MAX_RAW_ROWS})")
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
