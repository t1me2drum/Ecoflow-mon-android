package app.powerhub.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.powerhub.protocol.DeviceState

data class Sample(
    val ts: Long,
    val soc: Int?,
    val inputW: Int?,
    val outputW: Int?,
    val solarW: Int?,
    val acInW: Int?,
    val grid: Boolean?,
)

/** One row per device per minute; used for charts and energy totals. */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples (
                sn TEXT NOT NULL, ts INTEGER NOT NULL, soc INTEGER, in_w INTEGER, out_w INTEGER,
                solar_w INTEGER, ac_in_w INTEGER, grid INTEGER, PRIMARY KEY (sn, ts))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(sn: String, ts: Long, s: DeviceState) {
        val v = ContentValues().apply {
            put("sn", sn)
            put("ts", ts)
            put("soc", s.soc)
            put("in_w", s.inputW)
            put("out_w", s.outputW)
            put("solar_w", s.solarW)
            put("ac_in_w", s.acInW)
            put("grid", s.gridConnected?.let { if (it) 1 else 0 })
        }
        writableDatabase.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun query(sn: String, fromTs: Long): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT ts, soc, in_w, out_w, solar_w, ac_in_w, grid FROM samples WHERE sn = ? AND ts >= ? ORDER BY ts",
            arrayOf(sn, fromTs.toString()),
        ).use { c ->
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i)
            while (c.moveToNext()) {
                out += Sample(c.getLong(0), int(1), int(2), int(3), int(4), int(5), int(6)?.let { it == 1 })
            }
        }
        return out
    }

    fun prune(olderThanTs: Long) {
        writableDatabase.delete("samples", "ts < ?", arrayOf(olderThanTs.toString()))
    }
}
