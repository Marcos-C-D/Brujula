package app.brujula.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.brujula.market.MarketStore
import app.brujula.market.PricePoint
import app.brujula.market.StoredFx
import app.brujula.market.StoredSeries
import app.brujula.model.AssetId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Datos de mercado en SQLite (base de datos separada de las cuentas). Precios y tipos de cambio se guardan como
 * texto decimal para no perder precisión.
 */
class SqliteMarketStore(context: Context, dbName: String = DEFAULT_DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION), MarketStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE series_meta (asset_id TEXT NOT NULL PRIMARY KEY, symbol TEXT NOT NULL, provider TEXT NOT NULL, " +
                "currency TEXT NOT NULL, fetched_at TEXT, last_attempt_at TEXT, last_error TEXT)",
        )
        db.execSQL(
            "CREATE TABLE price (asset_id TEXT NOT NULL REFERENCES series_meta(asset_id) ON DELETE CASCADE, date TEXT NOT NULL, " +
                "close TEXT NOT NULL, open TEXT, high TEXT, low TEXT, adj_close TEXT, PRIMARY KEY (asset_id, date))",
        )
        db.execSQL("CREATE TABLE fx_meta (currency TEXT NOT NULL PRIMARY KEY, fetched_at TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE fx_rate (currency TEXT NOT NULL REFERENCES fx_meta(currency) ON DELETE CASCADE, date TEXT NOT NULL, " +
                "rate TEXT NOT NULL, PRIMARY KEY (currency, date))",
        )
        db.execSQL("CREATE TABLE kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: aún no hay migraciones. Estos datos se pueden volver a descargar, pero nunca se borran sin avisar.
        throw IllegalStateException("Migración de base de datos no soportada: $oldVersion -> $newVersion")
    }

    override fun loadSeries(assetId: AssetId): StoredSeries? {
        val db = readableDatabase
        val meta = db.rawQuery(
            "SELECT symbol, provider, currency, fetched_at, last_attempt_at, last_error FROM series_meta WHERE asset_id = ?",
            arrayOf(assetId.value),
        ).use { c ->
            if (!c.moveToFirst()) return null
            arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
        }
        val points = ArrayList<PricePoint>()
        db.rawQuery(
            "SELECT date, close, open, high, low, adj_close FROM price WHERE asset_id = ? ORDER BY date",
            arrayOf(assetId.value),
        ).use { c ->
            while (c.moveToNext()) {
                points += PricePoint(
                    LocalDate.parse(c.getString(0)), BigDecimal(c.getString(1)),
                    c.getString(2)?.let { BigDecimal(it) }, c.getString(3)?.let { BigDecimal(it) },
                    c.getString(4)?.let { BigDecimal(it) }, c.getString(5)?.let { BigDecimal(it) },
                )
            }
        }
        return StoredSeries(
            assetId, meta[0]!!, meta[1]!!, meta[2]!!, points,
            meta[3]?.let { Instant.parse(it) }, meta[4]?.let { Instant.parse(it) }, meta[5],
        )
    }

    override fun saveSeries(series: StoredSeries) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.replaceOrThrow("series_meta", null, ContentValues().apply {
                put("asset_id", series.assetId.value)
                put("symbol", series.symbol)
                put("provider", series.provider)
                put("currency", series.currency)
                put("fetched_at", series.fetchedAt?.toString())
                put("last_attempt_at", series.lastAttemptAt?.toString())
                put("last_error", series.lastError)
            })
            db.delete("price", "asset_id = ?", arrayOf(series.assetId.value))
            val st = db.compileStatement("INSERT INTO price (asset_id, date, close, open, high, low, adj_close) VALUES (?,?,?,?,?,?,?)")
            try {
                for (p in series.points) {
                    st.clearBindings()
                    st.bindString(1, series.assetId.value)
                    st.bindString(2, p.date.toString())
                    st.bindString(3, p.close.toPlainString())
                    p.open?.let { st.bindString(4, it.toPlainString()) }
                    p.high?.let { st.bindString(5, it.toPlainString()) }
                    p.low?.let { st.bindString(6, it.toPlainString()) }
                    p.adjClose?.let { st.bindString(7, it.toPlainString()) }
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun loadFx(currency: String): StoredFx? {
        val db = readableDatabase
        val fetched = db.rawQuery("SELECT fetched_at FROM fx_meta WHERE currency = ?", arrayOf(currency)).use { c ->
            if (!c.moveToFirst()) return null
            Instant.parse(c.getString(0))
        }
        val rates = LinkedHashMap<LocalDate, BigDecimal>()
        db.rawQuery("SELECT date, rate FROM fx_rate WHERE currency = ? ORDER BY date", arrayOf(currency)).use { c ->
            while (c.moveToNext()) rates[LocalDate.parse(c.getString(0))] = BigDecimal(c.getString(1))
        }
        return StoredFx(currency, rates, fetched)
    }

    override fun saveFx(fx: StoredFx) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.replaceOrThrow("fx_meta", null, ContentValues().apply {
                put("currency", fx.currency)
                put("fetched_at", fx.fetchedAt.toString())
            })
            db.delete("fx_rate", "currency = ?", arrayOf(fx.currency))
            val st = db.compileStatement("INSERT INTO fx_rate (currency, date, rate) VALUES (?,?,?)")
            try {
                for ((d, r) in fx.rates) {
                    st.clearBindings()
                    st.bindString(1, fx.currency)
                    st.bindString(2, d.toString())
                    st.bindString(3, r.toPlainString())
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun lastSuccessfulUpdate(): Instant? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key = 'last_success'", null).use { c ->
            if (c.moveToFirst()) Instant.parse(c.getString(0)) else null
        }

    override fun saveLastSuccessfulUpdate(at: Instant) {
        writableDatabase.replaceOrThrow("kv", null, ContentValues().apply {
            put("key", "last_success")
            put("value", at.toString())
        })
    }

    companion object {
        const val DEFAULT_DB_NAME = "brujula-market.db"
        private const val DB_VERSION = 1
    }
}
