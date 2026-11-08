package app.brujula.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.brujula.engine.AccountConfig
import app.brujula.engine.BankStore
import app.brujula.engine.EventCodec
import app.brujula.engine.LedgerEvent
import app.brujula.engine.LedgerRecord
import app.brujula.engine.StoredAccount
import app.brujula.model.AccountId

/**
 * Almacén SQLite del [app.brujula.engine.Bank]. Guarda, por cada cuenta, su configuración (JSON) y su
 * registro de movimientos (un JSON por movimiento, con número de secuencia). Los importes viajan como texto
 * para no perder precisión. Cada operación de escritura es transaccional.
 */
class SqliteBankStore(context: Context, dbName: String = DEFAULT_DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION), BankStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE account (id TEXT NOT NULL PRIMARY KEY, config TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE ledger_event (" +
                "account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE, " +
                "seq INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "PRIMARY KEY (account_id, seq))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: aún no hay migraciones. Cuando cambie el esquema se añadirán aquí, sin borrar datos.
        throw IllegalStateException("Migración de base de datos no soportada: $oldVersion -> $newVersion")
    }

    override fun loadAccounts(): List<StoredAccount> {
        val db = readableDatabase
        val result = ArrayList<StoredAccount>()
        db.rawQuery("SELECT id, config FROM account ORDER BY rowid", null).use { accounts ->
            while (accounts.moveToNext()) {
                val config = EventCodec.configFromJson(accounts.getString(1))
                val events = ArrayList<LedgerEvent>()
                db.rawQuery(
                    "SELECT payload FROM ledger_event WHERE account_id = ? ORDER BY seq",
                    arrayOf(accounts.getString(0)),
                ).use { rows ->
                    while (rows.moveToNext()) events += EventCodec.fromJson(rows.getString(0))
                }
                result += StoredAccount(config, events)
            }
        }
        return result
    }

    override fun createAccounts(items: List<Pair<AccountConfig, List<LedgerRecord>>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((config, records) in items) {
                db.insertOrThrow("account", null, ContentValues().apply {
                    put("id", config.id.value)
                    put("config", EventCodec.configToJson(config))
                })
                for (record in records) insertEvent(db, config.id, record)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun saveConfig(config: AccountConfig) {
        val updated = writableDatabase.update(
            "account",
            ContentValues().apply { put("config", EventCodec.configToJson(config)) },
            "id = ?",
            arrayOf(config.id.value),
        )
        check(updated == 1) { "No existe la cuenta ${config.id}" }
    }

    override fun appendEvent(accountId: AccountId, record: LedgerRecord) {
        insertEvent(writableDatabase, accountId, record)
    }

    override fun deleteAccount(accountId: AccountId) {
        writableDatabase.delete("account", "id = ?", arrayOf(accountId.value))
    }

    private fun insertEvent(db: SQLiteDatabase, accountId: AccountId, record: LedgerRecord) {
        db.insertOrThrow("ledger_event", null, ContentValues().apply {
            put("account_id", accountId.value)
            put("seq", record.seq)
            put("payload", EventCodec.toJson(record.event))
        })
    }

    companion object {
        const val DEFAULT_DB_NAME = "brujula.db"
        private const val DB_VERSION = 1
    }
}
