package app.brujula.engine

import app.brujula.model.AccountId
import java.time.LocalDate

/** Configuración persistente de una cuenta (todo salvo los movimientos). */
data class AccountConfig(
    val id: AccountId,
    val name: String,
    val createdOn: LocalDate,
    val incomeHistory: List<IncomeConfig>,
    val archiveIntervals: List<ArchiveInterval>,
)

/** Cuenta tal y como se guarda: configuración + todos sus movimientos, en orden. */
data class StoredAccount(val config: AccountConfig, val events: List<LedgerEvent>)

/** El almacenamiento guardado no es coherente (no se descarta nada en silencio). */
class CorruptStoreException(val accountId: AccountId, val problems: List<String>) :
    RuntimeException("Datos guardados dañados en la cuenta $accountId: ${problems.joinToString("; ")}")

/**
 * Almacenamiento duradero del [Bank]. Las implementaciones deben ser transaccionales:
 * [createAccounts] guarda todo o nada. Si un método lanza una excepción, el [Bank] no cambia su estado.
 */
interface BankStore {
    fun loadAccounts(): List<StoredAccount>

    /** Guarda una o varias cuentas nuevas, cada una con sus movimientos, en una sola transacción. */
    fun createAccounts(items: List<Pair<AccountConfig, List<LedgerRecord>>>)

    fun saveConfig(config: AccountConfig)

    fun appendEvent(accountId: AccountId, record: LedgerRecord)

    fun deleteAccount(accountId: AccountId)
}

/** Almacén en memoria: sirve para pruebas y como base para comprobar la recuperación de datos. */
open class InMemoryBankStore : BankStore {
    private val lock = Any()
    private val configs = LinkedHashMap<AccountId, AccountConfig>()
    private val records = HashMap<AccountId, MutableList<LedgerRecord>>()

    override fun loadAccounts(): List<StoredAccount> = synchronized(lock) {
        configs.values.map { c -> StoredAccount(c, records.getValue(c.id).map { it.event }) }
    }

    override fun createAccounts(items: List<Pair<AccountConfig, List<LedgerRecord>>>) = synchronized(lock) {
        items.forEach { (c, _) -> check(c.id !in configs) { "La cuenta ${c.id} ya existe" } }
        check(items.map { it.first.id }.toSet().size == items.size) { "Identificadores repetidos" }
        items.forEach { (c, r) ->
            configs[c.id] = c
            records[c.id] = r.toMutableList()
        }
    }

    override fun saveConfig(config: AccountConfig) = synchronized(lock) {
        check(config.id in configs) { "No existe la cuenta ${config.id}" }
        configs[config.id] = config
    }

    override fun appendEvent(accountId: AccountId, record: LedgerRecord) = synchronized(lock) {
        val list = records[accountId] ?: error("No existe la cuenta $accountId")
        check(record.seq == list.size + 1L) { "Secuencia inesperada ${record.seq}" }
        list += record
        Unit
    }

    override fun deleteAccount(accountId: AccountId) = synchronized(lock) {
        configs.remove(accountId)
        records.remove(accountId)
        Unit
    }
}
